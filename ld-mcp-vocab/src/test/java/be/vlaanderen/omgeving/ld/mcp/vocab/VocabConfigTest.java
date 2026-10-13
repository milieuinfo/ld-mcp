package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class VocabConfigTest {

    @Test
    void defaults() {
        VocabConfig c = VocabConfig.parse(new String[] {"--ontologies", "o"});
        assertEquals(List.of(Path.of("o")), c.ontologies());
        assertEquals(List.of(), c.rules());
        assertFalse(c.generateShapes());
        assertNull(c.prefixRegistry());
        assertEquals(LovClient.DEFAULT_BASE_URL, c.lovUrl());
        assertEquals(VocabularyProfiler.DEFAULT_CORE, c.core());
        assertTrue(c.coreIsDefault());
        assertEquals(GuardedFetcher.DEFAULT_MAX_BYTES, c.maxResponseBytes());
        assertEquals(Duration.ofSeconds(30), c.networkBudget());
        assertEquals(Path.of(""), c.dataRoot());
    }

    @Test
    void readsEverything() {
        VocabConfig c = VocabConfig.parse(new String[] {"--ontologies", "a", "--ontologies", "b",
                "--rules", "r1", "--rules", "r2", "--shapes", "s", "--generate-shapes", "--core-namespace", "urn:c#",
                "--offline", "--allow-private-hosts", "--max-response-bytes", "10", "--network-budget", "5",
                "--data-root", "d", "--alignments", "al.ttl", "--prefix-registry", "p.ttl", "--lov-url", "http://l"});
        assertEquals(2, c.ontologies().size());
        assertEquals(List.of(Path.of("r1"), Path.of("r2")), c.rules());
        assertEquals(List.of("urn:c#"), c.core());
        assertFalse(c.coreIsDefault());
        assertTrue(c.offline() && c.allowPrivateHosts() && c.generateShapes());
        assertEquals(10, c.maxResponseBytes());
        assertEquals(Duration.ofSeconds(5), c.networkBudget());
    }

    @Test
    void rejectsMalformedNumbersAndMissingOntologies() {
        assertEquals("--network-budget must be a positive whole number, not '0'", assertThrows(IllegalArgumentException.class,
                () -> VocabConfig.parse(new String[] {"--ontologies", "o", "--network-budget", "0"})).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> VocabConfig.parse(new String[] {"--ontologies", "o", "--max-response-bytes", "lots"}));
        assertThrows(IllegalArgumentException.class, () -> VocabConfig.parse(new String[] {"--offline"}));
    }
}
