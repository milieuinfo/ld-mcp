package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        return new Workspace(List.of(dir), rules, null, false);
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
}
