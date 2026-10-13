package be.vlaanderen.omgeving.ld.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import be.vlaanderen.omgeving.ld.mcp.core.ServerArgs.Kind;

class ServerArgsTest {

    private static final Map<String, Kind> KNOWN = Map.of(
            "ontologies", Kind.REPEATABLE, "rules", Kind.SINGLE, "offline", Kind.FLAG);

    private static ServerArgs parse(String... args) {
        return ServerArgs.parse(args, KNOWN);
    }

    private static String error(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> parse(args)).getMessage();
    }

    @Test
    void repeatedKeysKeepAllValuesInOrder() {
        ServerArgs a = parse("--ontologies", "a", "--ontologies", "b");
        assertEquals(List.of("a", "b"), a.all("ontologies"));
        assertEquals(Optional.of("a"), a.first("ontologies"));
    }

    @Test
    void aFlagTakesNoValue() {
        ServerArgs a = parse("--offline", "--ontologies", "x");
        assertTrue(a.flag("offline"));
        assertFalse(a.flag("rules"));
        assertEquals("--offline takes no value, but got 'yes'", error("--offline", "yes"));
    }

    @Test
    void anUnknownKeyIsRejectedWithTheKnownOnes() {
        assertEquals("Unknown argument: --rule. Known: --offline, --ontologies, --rules", error("--rule", "r"));
    }

    @Test
    void aSingleValuedKeyMayNotBeRepeated() {
        assertEquals("--rules may be given only once", error("--rules", "a", "--rules", "b"));
    }

    @Test
    void aKeyThatNeedsAValueMustHaveOne() {
        assertEquals("--rules needs a value", error("--rules", "--offline"));
        assertEquals("--ontologies needs a value", error("--ontologies"));
    }

    @Test
    void aValueWithoutKeyIsRejected() {
        assertEquals("Unexpected argument: x", error("x"));
    }

    @Test
    void aMissingRequiredKeyIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> parse().required("ontologies"));
        assertEquals("Required argument missing: --ontologies", e.getMessage());
    }

    @Test
    void positionalArgumentsWhenAllowed() {
        ServerArgs a = ServerArgs.parse(new String[] {"a.ttl", "--rules", "r", "--offline", "dir", "b.ttl"}, KNOWN, true);
        assertEquals(List.of("a.ttl", "dir", "b.ttl"), a.positional());
        assertEquals(List.of("r"), a.all("rules"));
        assertTrue(a.flag("offline"));
        assertEquals("Unknown argument: --x. Known: --offline, --ontologies, --rules",
                assertThrows(IllegalArgumentException.class,
                        () -> ServerArgs.parse(new String[] {"a", "--x"}, KNOWN, true)).getMessage());
    }
}

