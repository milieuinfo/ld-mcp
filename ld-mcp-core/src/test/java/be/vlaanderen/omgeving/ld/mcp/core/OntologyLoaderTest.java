package be.vlaanderen.omgeving.ld.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OntologyLoaderTest {

    @TempDir
    Path dir;

    @Test
    void walksDirectoriesAndSkipsUnsupportedExtensions() throws IOException {
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("a.ttl"), "<urn:a> <urn:p> <urn:o> .");
        Files.writeString(dir.resolve("sub/b.nt"), "<urn:b> <urn:p> <urn:o> .\n");
        Files.writeString(dir.resolve("c.trig"), "<urn:g> { <urn:c> <urn:p> <urn:o> . }");
        Files.writeString(dir.resolve("logback.xml"), "<configuration/>");
        assertEquals(List.of(dir.resolve("a.ttl"), dir.resolve("sub/b.nt")), OntologyLoader.files(List.of(dir)));
        assertEquals(2, OntologyLoader.load(List.of(dir)).size());
    }

    @Test
    void excludedFilesAreNotLoaded() throws IOException {
        Files.writeString(dir.resolve("a.ttl"), "<urn:a> <urn:p> <urn:o> .");
        Files.writeString(dir.resolve("prefixes.ttl"), "@prefix x: <urn:x#> . <urn:x> <urn:p> <urn:o> .");
        Model m = OntologyLoader.load(List.of(dir), Set.of(dir.resolve("prefixes.ttl")));
        assertEquals(1, m.size());
        assertNull(m.getNsPrefixURI("x"));
    }

    @Test
    void aBrokenFileIsSkippedAndTheRestLoaded() throws IOException {
        Files.writeString(dir.resolve("a.ttl"), "<urn:a> <urn:p> <urn:o> .");
        Files.writeString(dir.resolve("b.ttl"), "this is not turtle");
        assertEquals(1, OntologyLoader.load(List.of(dir)).size());
    }

    @Test
    void theFirstPrefixDeclarationWinsAndTheEmptyPrefixIsDropped() throws IOException {
        Files.writeString(dir.resolve("a.ttl"), "@prefix ex: <urn:first#> . @prefix : <urn:e#> . :a ex:p :o .");
        Files.writeString(dir.resolve("b.ttl"), "@prefix ex: <urn:second#> . ex:b ex:p ex:o .");
        Model m = OntologyLoader.load(List.of(dir));
        assertEquals("urn:first#", m.getNsPrefixURI("ex"));
        assertNull(m.getNsPrefixURI(""));
    }

    @Test
    void aMissingPathIsIgnored() {
        assertEquals(List.of(), OntologyLoader.files(List.of(dir.resolve("nope"))));
    }

    @Test
    void aJsonLdOntologyWithARemoteContextIsSkippedWithoutARequest() throws IOException {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            requests.incrementAndGet();
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.start();
        try {
            Files.writeString(dir.resolve("a.jsonld"), "{\"@context\": \"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/c\", \"@id\": \"urn:a\", \"urn:p\": \"x\"}");
            Files.writeString(dir.resolve("b.ttl"), "<urn:b> <urn:p> <urn:o> .");
            assertEquals(1, OntologyLoader.load(List.of(dir)).size());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }
}
