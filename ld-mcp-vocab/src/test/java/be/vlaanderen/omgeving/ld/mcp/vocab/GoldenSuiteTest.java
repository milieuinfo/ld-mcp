package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import be.vlaanderen.omgeving.ld.mcp.vocab.support.McpHarness;

/**
 * Regression suite on the data of a real project (e.g. OSLOthema-SSNSOSA-VL): validates every
 * {@code .ttl} under {@code src/main/input/} with the server arguments from the project's
 * {@code .mcp.json}, and compares the reports with the expected files in
 * {@code src/test/golden/<project directory name>/}.
 *
 * <pre>
 * mvn test -Dgolden.project=/path/to/project              # compare
 * mvn test -Dgolden.project=/path/to/project -Dgolden.update=true   # write the expected files
 * </pre>
 *
 * Skipped without {@code golden.project}. After a deliberate change in behaviour, update the
 * expected files and review the diff in git. The expected files depend on the state of the
 * project as well: record its commit in {@code PROJECT_COMMIT} next to them.
 */
class GoldenSuiteTest {

    /** Arguments of the server that name a file or directory. */
    private static final Set<String> PATH_ARGUMENTS =
            Set.of("ontologies", "rules", "shapes", "prefix-registry", "alignments", "data-root");

    private static McpHarness harness;

    @AfterAll
    static void close() {
        if (harness != null) {
            harness.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> reportsMatchTheExpectedFiles() throws IOException {
        String project = System.getProperty("golden.project");
        Assumptions.assumeTrue(project != null && !project.isBlank(), "golden.project not set");
        Path root = Path.of(project).toAbsolutePath().normalize();
        boolean update = Boolean.getBoolean("golden.update");
        Path expected = Path.of(System.getProperty("golden.expected", "src/test/golden"))
                .resolve(root.getFileName().toString());

        VocabServer.Setup setup = VocabServer.setup(serverArguments(root));
        harness = new McpHarness(setup.instructions(), setup.tools());

        List<Path> files;
        try (Stream<Path> s = Files.walk(root.resolve("src/main/input"))) {
            files = s.filter(p -> p.toString().endsWith(".ttl")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "no .ttl files under src/main/input");
        return files.stream().map(file -> {
            String rel = root.relativize(file).toString();
            return DynamicTest.dynamicTest(rel, () -> {
                McpHarness.ToolResult r = harness.callTool("validate_file",
                        McpHarness.args(Map.of("path", file.toString())));
                String actual = normalise(r.text().replace(root + "/", ""));
                Path want = expected.resolve(rel + ".txt");
                if (update) {
                    Files.createDirectories(want.getParent());
                    Files.writeString(want, actual);
                } else {
                    Assumptions.assumeTrue(Files.exists(want), "no expected file " + want);
                    assertEquals(Files.readString(want), actual, rel);
                }
            });
        });
    }

    /**
     * Leaves out what changes without a change in behaviour. The identity, as long as the report
     * contains nothing that depends on the environment.
     */
    static String normalise(String report) {
        return report;
    }

    /** The arguments after the jar in the project's .mcp.json, with paths made absolute. */
    private static String[] serverArguments(Path root) throws IOException {
        JsonObject mcp = JSON.parse(Files.readString(root.resolve(".mcp.json")));
        JsonObject servers = mcp.get("mcpServers").getAsObject();
        JsonObject server = servers.get(servers.keys().iterator().next()).getAsObject();
        JsonArray all = server.get("args").getAsArray();
        List<String> raw = new ArrayList<>();
        for (JsonValue v : all) {
            raw.add(v.getAsString().value());
        }
        List<String> args = new ArrayList<>(raw.subList(raw.indexOf("-jar") + 2, raw.size()));
        for (int i = 0; i + 1 < args.size(); i++) {
            if (args.get(i).startsWith("--") && PATH_ARGUMENTS.contains(args.get(i).substring(2))
                    && !args.get(i + 1).startsWith("--")) {
                args.set(i + 1, root.resolve(args.get(i + 1)).toString());
            }
        }
        if (!args.contains("--data-root")) {
            args.add("--data-root");
            args.add(root.toString());
        }
        return args.toArray(String[]::new);
    }
}
