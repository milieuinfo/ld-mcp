package be.vlaanderen.omgeving.ld.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonLdTest {

    @TempDir
    Path dir;

    @Test
    void aDatasetKeepsItsNamedGraphs() throws IOException {
        Path f = Files.writeString(dir.resolve("d.trig"), """
                @prefix ex: <https://example.org/> .
                ex:a ex:p ex:b .
                ex:g { ex:c ex:p ex:d . }
                """);
        Model m = JsonLd.load(f);
        assertEquals(2, m.size());
        assertEquals("https://example.org/", m.getNsPrefixURI("ex"));
    }

    @Test
    void turtleStillLoads() throws IOException {
        Path f = Files.writeString(dir.resolve("d.ttl"), "<urn:a> <urn:p> <urn:b> .");
        assertTrue(JsonLd.load(f).contains(null, null, (org.apache.jena.rdf.model.RDFNode) null));
    }
}
