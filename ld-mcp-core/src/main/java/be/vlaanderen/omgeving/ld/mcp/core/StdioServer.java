package be.vlaanderen.omgeving.ld.mcp.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Starts an MCP server over stdio. Stdout is reserved for JSON-RPC: all logging must go to
 * stderr (see logback.xml in the server modules). The server stops when the client closes stdin.
 */
public final class StdioServer {

    private StdioServer() {
    }

    /**
     * Runs the server until the client closes stdin, then exits the JVM.
     *
     * @param name         server name reported to the client
     * @param version      server version reported to the client
     * @param instructions guidance for the model on when and how to use the tools
     * @param tools        the tools the server offers
     * @throws InterruptedException if the thread is interrupted while waiting for stdin to close
     */
    public static void run(String name, String version, String instructions,
                           List<SyncToolSpecification> tools) throws InterruptedException {
        CountDownLatch stdinClosed = new CountDownLatch(1);
        McpSyncServer server = start(name, version, instructions, tools,
                new EofSignallingStream(System.in, stdinClosed), System.out);
        stdinClosed.await();
        server.closeGracefully();
        System.exit(0);
    }

    /**
     * Starts a server on the given streams and returns at once; the server answers on its own
     * threads until it is closed. {@link #run} uses it with stdin and stdout; tests use it with
     * in-memory streams.
     *
     * @param name         server name reported to the client
     * @param version      server version reported to the client
     * @param instructions guidance for the model on when and how to use the tools
     * @param tools        the tools the server offers
     * @param in           where the client's JSON-RPC messages arrive
     * @param out          where the server writes its JSON-RPC messages
     * @return the running server; close it with {@link McpSyncServer#closeGracefully()}
     */
    public static McpSyncServer start(String name, String version, String instructions,
                                      List<SyncToolSpecification> tools, InputStream in, OutputStream out) {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), in, out);
        return McpServer.sync(transport)
                .serverInfo(name, version)
                .instructions(instructions)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();
    }

    /** Signals the end of stdin (client gone), so that the process does not linger. */
    private static final class EofSignallingStream extends FilterInputStream {

        private final CountDownLatch eof;

        EofSignallingStream(InputStream in, CountDownLatch eof) {
            super(in);
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            return signal(super.read());
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return signal(super.read(b, off, len));
        }

        private int signal(int n) {
            if (n < 0) {
                eof.countDown();
            }
            return n;
        }
    }
}
