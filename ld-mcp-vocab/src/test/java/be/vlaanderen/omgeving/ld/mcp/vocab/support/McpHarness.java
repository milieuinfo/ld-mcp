package be.vlaanderen.omgeving.ld.mcp.vocab.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;

import be.vlaanderen.omgeving.ld.mcp.core.StdioServer;

/**
 * Talks JSON-RPC to an MCP server started with {@link StdioServer#start} on in-memory streams, as
 * a client would over stdio: {@code initialize}, {@code tools/list}, {@code tools/call}.
 */
public final class McpHarness implements AutoCloseable {

    private static final long TIMEOUT_SECONDS = 60;

    private final Pipe toServer = new Pipe();
    private final Pipe toClient = new Pipe();
    private final McpSyncServer server;
    private final BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
    private final Thread reader;
    private int nextId = 1;

    /**
     * Starts the server and performs the handshake.
     *
     * @param instructions the server instructions
     * @param tools        the tools
     */
    public McpHarness(String instructions, List<SyncToolSpecification> tools) {
        server = StdioServer.start("test", "0", instructions, tools, toServer.in(), toClient.out());
        reader = new Thread(this::readLoop, "mcp-harness-reader");
        reader.setDaemon(true);
        reader.start();
        JsonObject init = request("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"harness\",\"version\":\"0\"}}");
        if (!init.hasKey("result")) {
            throw new IllegalStateException("initialize failed: " + init);
        }
        send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    /**
     * Lists the tools.
     *
     * @return the full JSON-RPC answer to {@code tools/list}
     */
    public JsonObject listTools() {
        return request("tools/list", "{}");
    }

    /**
     * Calls a tool.
     *
     * @param name      the tool name
     * @param arguments the arguments as a JSON object literal
     * @return the full JSON-RPC answer (with {@code result} or {@code error})
     */
    public JsonObject call(String name, String arguments) {
        return request("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}");
    }

    /**
     * Calls a tool and returns its text, failing if the answer is not a tool result.
     *
     * @param name      the tool name
     * @param arguments the arguments as a JSON object literal
     * @return the result
     */
    public ToolResult callTool(String name, String arguments) {
        JsonObject answer = call(name, arguments);
        if (!answer.hasKey("result")) {
            throw new AssertionError("Expected a tool result, got " + answer);
        }
        return ToolResult.of(answer.get("result").getAsObject());
    }

    /**
     * Calls a tool once per argument object, sending all requests before reading any answer, as
     * a client does that calls tools in parallel.
     *
     * @param name      the tool name
     * @param arguments the arguments of each call, as JSON object literals
     * @return the full JSON-RPC answers, in the order of the arguments
     * @throws AssertionError if an answer does not arrive within the timeout
     */
    public synchronized List<JsonObject> callAll(String name, List<String> arguments) {
        int first = nextId;
        for (String a : arguments) {
            send("{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"tools/call\",\"params\":"
                    + "{\"name\":\"" + name + "\",\"arguments\":" + a + "}}");
        }
        JsonObject[] answers = new JsonObject[arguments.size()];
        int missing = arguments.size();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (missing > 0) {
            JsonObject m = poll(deadline);
            if (m == null) {
                throw new AssertionError(missing + " of " + arguments.size() + " calls of " + name
                        + " unanswered after " + TIMEOUT_SECONDS + " s");
            }
            if (m.hasKey("id") && m.get("id").isNumber()) {
                int i = m.get("id").getAsNumber().value().intValue() - first;
                if (i >= 0 && i < answers.length && answers[i] == null) {
                    answers[i] = m;
                    missing--;
                }
            }
        }
        return List.of(answers);
    }

    /**
     * A tool result.
     *
     * @param text    the concatenated text content
     * @param isError whether the tool reported an error
     * @param result  the raw result object
     */
    public record ToolResult(String text, boolean isError, JsonObject result) {

        static ToolResult of(JsonObject result) {
            StringBuilder sb = new StringBuilder();
            JsonArray content = result.get("content").getAsArray();
            for (JsonValue c : content) {
                JsonObject o = c.getAsObject();
                if (o.hasKey("text")) {
                    sb.append(o.get("text").getAsString().value());
                }
            }
            boolean error = result.hasKey("isError") && result.get("isError").getAsBoolean().value();
            return new ToolResult(sb.toString(), error, result);
        }
    }

    private synchronized JsonObject request(String method, String params) {
        int id = nextId++;
        send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (true) {
            JsonObject m = poll(deadline);
            if (m == null) {
                throw new AssertionError("No answer to " + method + " within " + TIMEOUT_SECONDS + " s");
            }
            if (m.hasKey("id") && m.get("id").isNumber() && m.get("id").getAsNumber().value().intValue() == id) {
                return m;
            }
        }
    }

    /** The next message from the server, or null if none arrives before the deadline. */
    private JsonObject poll(long deadline) {
        long left = deadline - System.nanoTime();
        try {
            return left <= 0 ? null : messages.poll(left, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void send(String line) {
        try {
            OutputStream out = toServer.out();
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(toClient.in(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    messages.add(JSON.parse(line));
                }
            }
        } catch (IOException e) {
            // closed
        }
    }

    @Override
    public void close() {
        toServer.close();
        server.closeGracefully();
        toClient.close();
    }

    /** Builds a JSON object literal from key/value pairs; values are strings or numbers. */
    public static String args(Map<String, Object> values) {
        JsonObject o = new JsonObject();
        values.forEach((k, v) -> {
            if (v instanceof Number n) {
                o.put(k, n.longValue());
            } else {
                o.put(k, String.valueOf(v));
            }
        });
        return JSON.toStringFlat(o);
    }

    /** An in-memory pipe that, unlike {@link java.io.PipedInputStream}, does not care about threads. */
    static final class Pipe {

        private static final byte[] EOF = new byte[0];
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private volatile boolean closed;

        void close() {
            if (!closed) {
                closed = true;
                chunks.add(EOF);
            }
        }

        OutputStream out() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    write(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    if (!closed && len > 0) {
                        byte[] copy = new byte[len];
                        System.arraycopy(b, off, copy, 0, len);
                        chunks.add(copy);
                    }
                }
            };
        }

        InputStream in() {
            return new InputStream() {
                private byte[] current = new byte[0];
                private int pos;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (len == 0) {
                        return 0;
                    }
                    while (pos >= current.length) {
                        if (current == EOF) {
                            return -1;
                        }
                        try {
                            current = chunks.take();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                        pos = 0;
                        if (current == EOF) {
                            chunks.add(EOF);
                            return -1;
                        }
                    }
                    int n = Math.min(len, current.length - pos);
                    System.arraycopy(current, pos, b, off, n);
                    pos += n;
                    return n;
                }
            };
        }
    }
}
