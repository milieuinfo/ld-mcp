package be.vlaanderen.omgeving.ld.mcp.vocab.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * A local HTTP server for network tests: counts requests, and serves fixed bodies, redirects,
 * bodies that trickle in slowly, and bodies that are too large. Binds to 127.0.0.1 on a free port.
 */
public final class HttpFixture implements AutoCloseable {

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final Map<String, HttpHandler> routes = new ConcurrentHashMap<>();

    /** Starts the server. */
    public HttpFixture() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-fixture");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            paths.add(exchange.getRequestURI().toString());
            HttpHandler h = routes.get(exchange.getRequestURI().getPath());
            if (h == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            } else {
                try {
                    h.handle(exchange);
                } catch (IOException e) {
                    // client gone
                } finally {
                    exchange.close();
                }
            }
        });
        server.start();
    }

    /**
     * Returns the base URL.
     *
     * @return {@code http://127.0.0.1:<port>}
     */
    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Returns the number of requests received so far.
     *
     * @return the count
     */
    public int requests() {
        return requests.get();
    }

    /**
     * Returns the request URIs received so far.
     *
     * @return path and query of each request
     */
    public List<String> paths() {
        return List.copyOf(paths);
    }

    /**
     * Serves a body.
     *
     * @param path        the path
     * @param status      the status code
     * @param contentType the content type
     * @param body        the body
     * @return this fixture
     */
    public HttpFixture body(String path, int status, String contentType, String body) {
        routes.put(path, ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType);
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        return this;
    }

    /**
     * Serves a response with extra headers and no body.
     *
     * @param path    the path
     * @param status  the status code
     * @param headers the headers
     * @return this fixture
     */
    public HttpFixture status(String path, int status, Map<String, String> headers) {
        routes.put(path, ex -> {
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(status, -1);
        });
        return this;
    }

    /**
     * Redirects to another URL.
     *
     * @param path     the path
     * @param location the target
     * @return this fixture
     */
    public HttpFixture redirect(String path, String location) {
        return status(path, 302, Map.of("Location", location));
    }

    /**
     * Sends the headers at once and then one byte per interval, forever (until the client gives
     * up or the fixture is closed).
     *
     * @param path     the path
     * @param interval milliseconds between bytes
     * @return this fixture
     */
    public HttpFixture trickle(String path, long interval) {
        routes.put(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/turtle");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            try {
                for (int i = 0; i < 100_000; i++) {
                    out.write('#');
                    out.flush();
                    Thread.sleep(interval);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return this;
    }

    /**
     * Sends a body of the given size, in chunks, as fast as possible.
     *
     * @param path  the path
     * @param bytes the size of the body
     * @return this fixture
     */
    public HttpFixture large(String path, long bytes) {
        routes.put(path, ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/turtle");
            ex.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            java.util.Arrays.fill(chunk, (byte) '#');
            OutputStream out = ex.getResponseBody();
            for (long sent = 0; sent < bytes; sent += chunk.length) {
                out.write(chunk, 0, (int) Math.min(chunk.length, bytes - sent));
            }
        });
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
