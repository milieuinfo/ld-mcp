package be.vlaanderen.omgeving.ld.mcp.core;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

/**
 * Start een MCP-server over stdio. Stdout is voorbehouden voor JSON-RPC: alle logging moet naar
 * stderr (zie logback.xml in de servermodules). De server stopt wanneer de client stdin sluit.
 */
public final class StdioServer {

    private StdioServer() {
    }

    public static void run(String name, String version, String instructions,
                           List<SyncToolSpecification> tools) throws InterruptedException {
        CountDownLatch stdinClosed = new CountDownLatch(1);
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), new EofSignallingStream(System.in, stdinClosed), System.out);

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(name, version)
                .instructions(instructions)
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools)
                .build();

        stdinClosed.await();
        server.closeGracefully();
        System.exit(0);
    }

    /** Signaleert het einde van stdin (client weg), zodat het proces niet blijft hangen. */
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
