package be.vlaanderen.omgeving.ld.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ServerArgsTest {

    @Test
    void repeatedKeysKeepAllValuesInOrder() {
        ServerArgs a = ServerArgs.parse(new String[] {"--ontologies", "a", "--ontologies", "b"});
        assertEquals(List.of("a", "b"), a.all("ontologies"));
        assertEquals(Optional.of("a"), a.first("ontologies"));
    }

    @Test
    void aKeyWithoutValueIsAFlag() {
        ServerArgs a = ServerArgs.parse(new String[] {"--offline", "--ontologies", "x"});
        assertTrue(a.flag("offline"));
        assertFalse(a.flag("generate-shapes"));
        assertEquals(List.of(), a.all("offline"));
    }

    @Test
    void aValueWithoutKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ServerArgs.parse(new String[] {"x"}));
    }

    @Test
    void aMissingRequiredKeyIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ServerArgs.parse(new String[0]).required("ontologies"));
        assertEquals("Required argument missing: --ontologies", e.getMessage());
    }
}
