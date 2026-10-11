package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrefixRegistryTest {

    @TempDir
    Path dir;

    @Test
    void turtleKeepsDeclarationOrderAndIgnoresTriples() throws IOException {
        PrefixRegistry registry = PrefixRegistry.load(Files.writeString(dir.resolve("p.ttl"), """
                @prefix geo: <http://www.opengis.net/ont/geosparql#> .
                @prefix gsp: <http://www.opengis.net/ont/geosparql#> .
                @prefix wgs: <http://www.w3.org/2003/01/geo/wgs84_pos#> .
                geo:a geo:b geo:c .
                """));
        assertEquals(List.of("geo", "gsp"), registry.prefixes("http://www.opengis.net/ont/geosparql#"));
        assertEquals(List.of(), registry.prefixes("http://example.org/"));
        assertEquals(2, registry.size());
    }

    @Test
    void jsonLdContextIsRead() throws IOException {
        PrefixRegistry registry = PrefixRegistry.load(Files.writeString(dir.resolve("p.jsonld"), """
                {"@context": {"dcat": "http://www.w3.org/ns/dcat#"}}
                """));
        assertEquals(List.of("dcat"), registry.prefixes("http://www.w3.org/ns/dcat#"));
    }

    @Test
    void unreadableFileIsError() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.ttl"), "@prefix x <oops");
        assertThrows(IllegalArgumentException.class, () -> PrefixRegistry.load(broken));
        assertThrows(IllegalArgumentException.class, () -> PrefixRegistry.load(dir.resolve("missing.ttl")));
    }
}
