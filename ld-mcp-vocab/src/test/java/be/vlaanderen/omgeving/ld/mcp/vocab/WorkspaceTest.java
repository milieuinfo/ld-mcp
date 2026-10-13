package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    private static final String PREFIXES = """
            @prefix sosa: <http://www.w3.org/ns/sosa/> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            """;

    @TempDir
    Path dir;

    private Workspace workspace(Path rules) throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        return new Workspace(List.of(dir), rules == null ? List.of() : List.of(rules), List.of(), false, null);
    }

    /** Move the modification time explicitly: some file systems have a coarse resolution. */
    private static void touch(Path file) throws IOException {
        Files.setLastModifiedTime(file,
                FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
    }

    @Test
    void unchangedTreeKeepsState() throws IOException {
        Workspace ws = workspace(null);
        assertSame(ws.current(), ws.current());
    }

    @Test
    void changedOntologyIsReloaded() throws IOException {
        Workspace ws = workspace(null);
        Workspace.State before = ws.current();
        assertTrue(before.index().lookup("sosa:Platform").startsWith("Not found"));

        Path onto = dir.resolve("onto.ttl");
        Files.writeString(onto, PREFIXES + "sosa:Sample a owl:Class . sosa:Platform a owl:Class .");
        touch(onto);

        Workspace.State after = ws.current();
        assertNotSame(before, after);
        assertTrue(after.index().lookup("sosa:Platform").startsWith("sosa:Platform"));
    }

    @Test
    void newFileInTreeIsReloaded() throws IOException {
        Workspace ws = workspace(null);
        Workspace.State before = ws.current();
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub/extra.ttl"), PREFIXES + "sosa:Sensor a owl:Class .");
        assertNotSame(before, ws.current());
    }

    @Test
    void otherFilesAreIgnored() throws IOException {
        Workspace ws = workspace(null);
        Workspace.State before = ws.current();
        Files.writeString(dir.resolve("README.md"), "not an ontology");
        assertSame(before, ws.current());
    }

    @Test
    void changedRulesAreReloaded() throws IOException {
        Path rules = Files.writeString(dir.resolve("r.rules"), "");
        Workspace ws = workspace(rules);
        Workspace.State before = ws.current();
        Files.writeString(rules, "[r: (?x rdf:type ?c) -> (?x rdf:type ?c)]");
        touch(rules);
        assertNotSame(before, ws.current());
    }

    @Test
    void changedPrefixRegistryIsReloaded(@TempDir Path elsewhere) throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        Path registry = Files.writeString(elsewhere.resolve("prefixes.ttl"), "");
        Workspace ws = new Workspace(List.of(dir), List.of(), List.of(), false, registry);
        Workspace.State before = ws.current();
        Files.writeString(registry, "@prefix dcat: <http://www.w3.org/ns/dcat#> .");
        touch(registry);
        assertNotSame(before, ws.current());
    }

    @Test
    void registryUnderOntologiesIsNotLoadedAsOntology() throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        Path registry = Files.writeString(dir.resolve("prefixes.ttl"), "@prefix dcat: <http://www.w3.org/ns/dcat#> .");
        Workspace ws = new Workspace(List.of(dir), List.of(), List.of(), false, registry);
        // Its prefixes would otherwise end up in the ontology model and resolve dcat:.
        assertThrows(IllegalArgumentException.class, () -> ws.current().index().lookup("dcat:Dataset"));
    }

    @Test
    void brokenPrefixRegistryIsSkipped(@TempDir Path elsewhere) throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        Path registry = Files.writeString(elsewhere.resolve("prefixes.ttl"), "@prefix x <oops");
        Workspace ws = new Workspace(List.of(dir), List.of(), List.of(), false, registry);
        assertTrue(ws.current().index().lookup("sosa:Sample").startsWith("sosa:Sample"));
    }

    private static final String RULES = "[r: (?x rdf:type <http://www.w3.org/ns/sosa/Sample>) -> (?x rdf:type owl:Thing)]";
    private static final String DATA = "@prefix sosa: <http://www.w3.org/ns/sosa/> . <urn:x> a sosa:Sample .";

    @Test
    void aBrokenOntologyFileIsSkippedAndReported() throws IOException {
        Workspace ws = workspace(null);
        Files.writeString(dir.resolve("broken.ttl"), "this is not turtle");
        Workspace.State state = ws.current();
        assertTrue(state.index().lookup("sosa:Sample").startsWith("sosa:Sample"));
        assertEquals(1, state.problems().size(), state.problems().toString());
        assertTrue(state.problems().get(0).startsWith("skipped " + dir.resolve("broken.ttl") + " (parse error:"),
                state.problems().toString());
        assertTrue(state.withProblems("report").startsWith("Configuration problems:\n- skipped "));
    }

    @Test
    void rulesBrokenDuringASessionFallBackToThePreviousRules(@TempDir Path elsewhere) throws IOException {
        Path rules = Files.writeString(elsewhere.resolve("r.rules"), RULES);
        Workspace ws = workspace(rules);
        Path data = Files.writeString(elsewhere.resolve("d.ttl"), DATA);
        assertTrue(ws.current().validator().validate(data.toString()).contains("[INFERENCE] 1 inferred triples"));

        Files.writeString(rules, "[broken: (?x rdf:type ?c) -> ");
        touch(rules);
        Workspace.State state = ws.current();
        assertTrue(state.problems().get(0).contains("using the previous rules"), state.problems().toString());
        assertTrue(state.validator().validate(data.toString()).contains("[INFERENCE] 1 inferred triples"));
        assertTrue(state.index().lookup("sosa:Sample").startsWith("sosa:Sample"));
        assertSame(state, ws.current(), "a failed load is not retried until the next change");

        Files.writeString(rules, RULES);
        touch(rules);
        assertTrue(ws.current().problems().isEmpty());
    }

    @Test
    void rulesBrokenAtStartSkipInference(@TempDir Path elsewhere) throws IOException {
        Path rules = Files.writeString(elsewhere.resolve("r.rules"), "[broken: -> ");
        Workspace.State state = workspace(rules).current();
        assertTrue(state.problems().get(0).endsWith("; inference skipped"), state.problems().toString());
        Path data = Files.writeString(elsewhere.resolve("d.ttl"), DATA);
        assertTrue(state.validator().validate(data.toString()).contains("[INFERENCE] skipped"));
    }

    @Test
    void shapesBrokenDuringASessionFallBackToThePreviousShapes(@TempDir Path elsewhere) throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        Path shapes = Files.writeString(elsewhere.resolve("s.ttl"), """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix sosa: <http://www.w3.org/ns/sosa/> .
                [] a sh:NodeShape ; sh:targetClass sosa:Sample ;
                   sh:property [ sh:path sosa:isSampleOf ; sh:minCount 1 ] .
                """);
        Workspace ws = new Workspace(List.of(dir), List.of(), List.of(shapes), false, null);
        Path data = Files.writeString(elsewhere.resolve("d.ttl"), DATA);
        assertTrue(ws.current().validator().validate(data.toString()).contains("[SHACL] 1 violation(s)"));

        Files.writeString(shapes, "@prefix sh: <oops");
        touch(shapes);
        Workspace.State state = ws.current();
        assertTrue(state.problems().get(0).contains("using the previous shapes"), state.problems().toString());
        assertTrue(state.validator().validate(data.toString()).contains("[SHACL] 1 violation(s)"));
    }

    @Test
    void shapesBrokenAtStartAreSkipped(@TempDir Path elsewhere) throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        Path shapes = Files.writeString(elsewhere.resolve("s.ttl"), "@prefix sh: <oops");
        Workspace.State state = new Workspace(List.of(dir), List.of(), List.of(shapes), true, null).current();
        assertTrue(state.problems().get(0).endsWith("; its shapes skipped"), state.problems().toString());
        Path data = Files.writeString(elsewhere.resolve("d.ttl"), DATA);
        // The generated shapes are still there.
        assertTrue(state.validator().validate(data.toString()).contains("[SHACL] conforms"));
    }

    @Test
    void aFailedReloadKeepsThePreviousState() throws IOException {
        Workspace ws = workspace(null);
        Workspace.State before = ws.current();
        Path sub = Files.createDirectories(dir.resolve("sub"));
        Files.writeString(sub.resolve("x.ttl"), PREFIXES + "sosa:Sensor a owl:Class .");
        assertTrue(sub.toFile().setReadable(false));
        try {
            Workspace.State state = ws.current();
            assertSame(before.index(), state.index());
            assertTrue(state.problems().get(0).startsWith("reload failed"), state.problems().toString());
            assertSame(state, ws.current());
        } finally {
            sub.toFile().setReadable(true);
        }
    }

    @Test
    void aMissingPathIsAStartupError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Workspace(List.of(dir), List.of(dir.resolve("nope.rules")), List.of(), false, null));
        assertTrue(e.getMessage().startsWith("--rules: path does not exist"), e.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new Workspace(List.of(dir.resolve("missing")), List.of(), List.of(), false, null));
    }

    @Test
    void aBrokenPrefixRegistryIsReported(@TempDir Path elsewhere) throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        Path registry = Files.writeString(elsewhere.resolve("prefixes.ttl"), "@prefix x <oops");
        Workspace.State state = new Workspace(List.of(dir), List.of(), List.of(), false, registry).current();
        assertTrue(state.problems().get(0).startsWith("prefix registry skipped"), state.problems().toString());
    }

    @Test
    void theShapesFileIsNotLoadedAsAnOntology() throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + "sosa:Sample a owl:Class .");
        Path shapes = Files.writeString(dir.resolve("shapes.ttl"), """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <https://example.org/shapes/S> a sh:NodeShape ; sh:targetClass <http://www.w3.org/ns/sosa/Sample> .
                """);
        Workspace.State state = new Workspace(List.of(dir), List.of(), List.of(shapes), false, null).current();
        assertTrue(state.index().search("S", 10).contains("sosa:Sample"));
        assertTrue(state.index().lookup("https://example.org/shapes/S").startsWith("Not found"));
    }

    @Test
    void shapesInTheOntologiesAreNoTerms() throws IOException {
        Files.writeString(dir.resolve("onto.ttl"), PREFIXES + """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                sosa:Sample a owl:Class .
                sosa:SampleShape a sh:NodeShape ; sh:targetClass sosa:Sample .
                """);
        Workspace.State state = new Workspace(List.of(dir), List.of(), List.of(), false, null).current();
        assertTrue(state.index().search("SampleShape", 10).startsWith("No terms found"));
    }
}

