package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import be.vlaanderen.omgeving.ld.mcp.vocab.support.HttpFixture;
import be.vlaanderen.omgeving.ld.mcp.vocab.support.McpHarness;

/** End-to-end tests over the MCP protocol: the server as a client sees it. */
class ProtocolTest {

    static final String ONTOLOGY = """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix ex:   <https://example.org/voc/> .
            ex:Thing a owl:Class ; rdfs:label "Thing"@en .
            ex:name a owl:DatatypeProperty ; rdfs:label "name"@en .
            """;

    @TempDir
    Path dir;

    private McpHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    /** Starts the server with the given extra arguments after {@code --ontologies <dir>/onto}. */
    McpHarness start(String... extra) throws IOException {
        Path onto = dir.resolve("onto");
        Files.createDirectories(onto);
        Files.writeString(onto.resolve("voc.ttl"), ONTOLOGY);
        List<String> args = new ArrayList<>(List.of("--ontologies", onto.toString(), "--data-root", dir.toString()));
        args.addAll(Arrays.asList(extra));
        VocabServer.Setup setup = VocabServer.setup(args.toArray(String[]::new));
        harness = new McpHarness(setup.instructions(), setup.tools());
        return harness;
    }

    @Test
    void listsTheFiveTools() throws IOException {
        JsonObject answer = start().listTools();
        List<String> names = new ArrayList<>();
        for (JsonValue t : answer.get("result").getAsObject().get("tools").getAsArray()) {
            names.add(t.getAsObject().get("name").getAsString().value());
        }
        assertEquals(List.of("lookup_term", "search_terms", "validate_file", "lov_search", "vocabulary_profile"),
                names);
    }

    @Test
    void looksUpATerm() throws IOException {
        McpHarness.ToolResult r = start().callTool("lookup_term", McpHarness.args(Map.of("term", "https://example.org/voc/Thing")));
        assertFalse(r.isError());
        assertTrue(r.text().contains("owl:Class"), r.text());
    }

    @Test
    void validatesAFile() throws IOException {
        start();
        Path data = dir.resolve("data.ttl");
        Files.writeString(data, """
                @prefix ex: <https://example.org/voc/> .
                <https://example.org/d/1> a ex:Thing ; ex:name "x" .
                """);
        McpHarness.ToolResult r = harness.callTool("validate_file", McpHarness.args(Map.of("path", data.toString())));
        assertFalse(r.isError(), r.text());
        assertTrue(r.text().contains("[VOCAB] ok"), r.text());
    }

    @Test
    void reportsAnInputErrorAsToolError() throws IOException {
        McpHarness.ToolResult r = start().callTool("lookup_term", McpHarness.args(Map.of("term", "nope:X")));
        assertTrue(r.isError());
        assertTrue(r.text().startsWith("Unknown prefix 'nope'"), r.text());
    }

    @Test
    void vocabularyProfileDoesNotFetchFromLocalhost() throws IOException {
        try (HttpFixture http = new HttpFixture()) {
            http.body("/voc", 200, "text/turtle", "<urn:a> <urn:b> <urn:c> .");
            start("--lov-url", http.base() + "/lov");
            McpHarness.ToolResult r = harness.callTool("vocabulary_profile",
                    McpHarness.args(Map.of("vocabulary", http.base() + "/voc")));
            assertTrue(r.text().contains("refused: 127.0.0.1 resolves to a loopback address"), r.text());
            assertEquals(0, http.requests(), http.paths().toString());
        }
    }

    @Test
    void allowPrivateHostsLetsIntranetVocabulariesThrough() throws IOException {
        try (HttpFixture http = new HttpFixture()) {
            http.body("/voc", 200, "text/turtle", "<urn:a> <urn:b> <urn:c> .");
            start("--lov-url", http.base() + "/lov", "--allow-private-hosts");
            McpHarness.ToolResult r = harness.callTool("vocabulary_profile",
                    McpHarness.args(Map.of("vocabulary", http.base() + "/voc")));
            assertTrue(r.text().contains("200, text/turtle, 1 triples"), r.text());
        }
    }

    private static final String REMOTE_CONTEXT_DATA = """
            {"@context": "%s/ctx.jsonld",
             "@id": "https://example.org/d/1", "@type": "https://example.org/voc/Thing"}
            """;

    @Test
    void validateFileDoesNotFetchARemoteJsonLdContext() throws IOException {
        try (HttpFixture http = new HttpFixture()) {
            http.body("/ctx.jsonld", 200, "application/ld+json", "{\"@context\": {}}");
            start("--allow-private-hosts");
            Path data = dir.resolve("data.jsonld");
            Files.writeString(data, REMOTE_CONTEXT_DATA.formatted(http.base()));
            McpHarness.ToolResult r = harness.callTool("validate_file", McpHarness.args(Map.of("path", data.toString())));
            assertTrue(r.text().startsWith("PARSE ERROR") && r.text().contains("remote @context not allowed"), r.text());
            assertEquals(0, http.requests());
        }
    }

    @Test
    void offlineMakesNoRequestsAtAll() throws IOException {
        try (HttpFixture http = new HttpFixture()) {
            http.body("/ctx.jsonld", 200, "application/ld+json", "{\"@context\": {}}");
            http.body("/voc", 200, "text/turtle", "<urn:a> <urn:b> <urn:c> .");
            start("--offline", "--lov-url", http.base() + "/lov", "--allow-private-hosts");
            Path data = dir.resolve("data.jsonld");
            Files.writeString(data, REMOTE_CONTEXT_DATA.formatted(http.base()));
            harness.callTool("validate_file", McpHarness.args(Map.of("path", data.toString())));
            assertTrue(harness.callTool("vocabulary_profile",
                    McpHarness.args(Map.of("vocabulary", http.base() + "/voc"))).isError());
            assertTrue(harness.callTool("lov_search", McpHarness.args(Map.of("query", "x"))).isError());
            assertEquals(0, http.requests());
        }
    }

    @Test
    void jsonLdWithAnInlineContextStillValidates() throws IOException {
        start();
        Path data = dir.resolve("data.jsonld");
        Files.writeString(data, """
                {"@context": {"ex": "https://example.org/voc/"},
                 "@id": "https://example.org/d/1", "@type": "ex:Thing", "ex:name": "x"}
                """);
        McpHarness.ToolResult r = harness.callTool("validate_file", McpHarness.args(Map.of("path", data.toString())));
        assertTrue(r.text().contains("(2 triples)") && r.text().contains("[VOCAB] ok"), r.text());
    }

    @Test
    void brokenRulesDuringASessionDoNotTakeTheToolsDown() throws IOException {
        Path rules = Files.writeString(dir.resolve("r.rules"), "");
        start("--rules", rules.toString());
        Files.writeString(rules, "[broken: (?x rdf:type ?c) -> ");
        Files.setLastModifiedTime(rules, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Path data = Files.writeString(dir.resolve("data.ttl"), "<urn:a> a <https://example.org/voc/Thing> .");

        McpHarness.ToolResult lookup = harness.callTool("lookup_term",
                McpHarness.args(Map.of("term", "https://example.org/voc/Thing")));
        assertFalse(lookup.isError(), lookup.text());
        assertTrue(lookup.text().startsWith("Configuration problems:\n- rules file "), lookup.text());
        McpHarness.ToolResult report = harness.callTool("validate_file", McpHarness.args(Map.of("path", data.toString())));
        assertFalse(report.isError(), report.text());
        assertTrue(report.text().contains("using the previous rules") && report.text().contains("[VOCAB] ok"),
                report.text());
    }

    @Test
    void unexpectedLovJsonIsAToolErrorNotAProtocolError() throws IOException {
        try (HttpFixture http = new HttpFixture()) {
            http.body("/lov/term/search", 200, "application/json", "{\"results\": \"not an array\"}");
            start("--lov-url", http.base() + "/lov", "--allow-private-hosts");
            JsonObject answer = harness.call("lov_search", McpHarness.args(Map.of("query", "x")));
            assertTrue(answer.hasKey("result"), answer.toString());
            McpHarness.ToolResult r = harness.callTool("lov_search", McpHarness.args(Map.of("query", "x")));
            assertTrue(r.isError(), r.text());
            assertTrue(r.text().contains("JsonException"), r.text());
        }
    }

    @Test
    void anOutOfRangeLimitIsAReadableToolError() throws IOException {
        McpHarness.ToolResult r = start().callTool("search_terms", McpHarness.args(Map.of("query", "thing", "limit", -1)));
        // The SDK checks the schema (minimum/maximum) before the handler does.
        assertTrue(r.isError());
        assertTrue(r.text().contains("/limit: must have a minimum value of 1"), r.text());
        assertTrue(harness.callTool("lov_search", McpHarness.args(Map.of("query", "x", "limit", 500))).isError());
    }

    @Test
    void toolsDeclareAnnotationsAndBounds() throws IOException {
        JsonObject answer = start().listTools();
        for (JsonValue t : answer.get("result").getAsObject().get("tools").getAsArray()) {
            JsonObject tool = t.getAsObject();
            String name = tool.get("name").getAsString().value();
            JsonObject ann = tool.get("annotations").getAsObject();
            assertTrue(ann.get("readOnlyHint").getAsBoolean().value(), name);
            boolean network = name.equals("lov_search") || name.equals("vocabulary_profile");
            assertEquals(network, ann.get("openWorldHint").getAsBoolean().value(), name);
            if (name.equals("search_terms")) {
                JsonObject limit = tool.get("inputSchema").getAsObject().get("properties").getAsObject()
                        .get("limit").getAsObject();
                assertEquals(200, limit.get("maximum").getAsNumber().value().intValue());
            }
        }
    }

    @Test
    void aTypingErrorInTheArgumentsStopsTheServer() {
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> start("--shape", "x.ttl"));
        assertTrue(e.getMessage().startsWith("Unknown argument: --shape. Known: "), e.getMessage());
    }

    @Test
    void severalShapesAndRulesFilesAllCount() throws IOException {
        Path shapes1 = Files.writeString(dir.resolve("s1.ttl"), """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix ex: <https://example.org/voc/> .
                [] a sh:NodeShape ; sh:targetClass ex:Thing ; sh:property [ sh:path ex:name ; sh:minCount 1 ] .
                """);
        Path shapes2 = Files.writeString(dir.resolve("s2.ttl"), """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                @prefix ex: <https://example.org/voc/> .
                [] a sh:NodeShape ; sh:targetClass ex:Thing ; sh:property [ sh:path ex:name ; sh:maxCount 0 ] .
                """);
        Path rules1 = Files.writeString(dir.resolve("r1.rules"),
                "[a: (?x rdf:type <https://example.org/voc/Thing>) -> (?x rdf:type <urn:A>)]");
        Path rules2 = Files.writeString(dir.resolve("r2.rules"),
                "[b: (?x rdf:type <https://example.org/voc/Thing>) -> (?x rdf:type <urn:B>)]");
        start("--shapes", shapes1.toString(), "--shapes", shapes2.toString(),
                "--rules", rules1.toString(), "--rules", rules2.toString());
        Path data = Files.writeString(dir.resolve("d.ttl"), "<urn:x> a <https://example.org/voc/Thing> .");
        String report = harness.callTool("validate_file", McpHarness.args(Map.of("path", data.toString()))).text();
        assertTrue(report.contains("[INFERENCE] 2 inferred triples"), report);
        assertTrue(report.contains("[SHACL] 1 violation(s)"), report); // minCount from s1; s2's maxCount 0 holds
        Files.writeString(data, "<urn:x> a <https://example.org/voc/Thing> ; <https://example.org/voc/name> \"n\" .");
        report = harness.callTool("validate_file", McpHarness.args(Map.of("path", data.toString()))).text();
        assertTrue(report.contains("[SHACL] 1 violation(s)") && report.contains("maxCount[0]"), report);
    }

    @Test
    void validateFileStaysInsideTheDataRoot(@TempDir Path outside) throws IOException {
        start();
        Path secret = Files.writeString(outside.resolve("secret.ttl"), "<urn:a> <urn:b> \"secret\" .");
        Files.createSymbolicLink(dir.resolve("link.ttl"), secret);
        Files.writeString(dir.resolve("inside.ttl"), "<urn:a> a <https://example.org/voc/Thing> .");
        for (String path : List.of(secret.toString(), "../" + outside.getFileName() + "/secret.ttl", "link.ttl")) {
            McpHarness.ToolResult r = harness.callTool("validate_file", McpHarness.args(Map.of("path", path)));
            assertTrue(r.isError() && r.text().startsWith("Refused: " + path + " lies outside the data root"),
                    path + ": " + r.text());
        }
        McpHarness.ToolResult ok = harness.callTool("validate_file", McpHarness.args(Map.of("path", "inside.ttl")));
        assertFalse(ok.isError(), ok.text());
        assertTrue(ok.text().startsWith("Validation: inside.ttl (1 triples)"), ok.text());
        McpHarness.ToolResult missing = harness.callTool("validate_file", McpHarness.args(Map.of("path", "nope.ttl")));
        assertTrue(missing.isError() && missing.text().startsWith("File not found: "), missing.text());
    }

    @Test
    void theReportNamesTheConfiguration() throws IOException {
        Path rules = Files.writeString(dir.resolve("r.rules"), "");
        start("--rules", rules.toString(), "--generate-shapes", "--offline");
        Path data = Files.writeString(dir.resolve("d.ttl"), "<urn:a> a <https://example.org/voc/Thing> .");
        String report = harness.callTool("validate_file", McpHarness.args(Map.of("path", "d.ttl"))).text();
        String line = report.lines().skip(1).findFirst().orElseThrow();
        assertTrue(line.matches("Configuration: ld-mcp-vocab dev; 1 ontology file\\(s\\), 1 rules file\\(s\\), "
                + "generate-shapes, offline; content hash [0-9a-f]{12}"), line);
        // The same files give the same hash.
        String again = harness.callTool("validate_file", McpHarness.args(Map.of("path", "d.ttl"))).text();
        assertEquals(line, again.lines().skip(1).findFirst().orElseThrow());
        assertTrue(data.toFile().exists());
    }

    @Test
    void theContentHashFollowsTheContentNotThePaths() {
        ValidationResult.Configuration a = new ValidationResult.Configuration("v", List.of(
                new ValidationResult.LoadedFile("ontology", "/a/x.ttl", "11"),
                new ValidationResult.LoadedFile("rules", "/a/r.rules", "22")), Map.of(), List.of());
        ValidationResult.Configuration b = new ValidationResult.Configuration("v", List.of(
                new ValidationResult.LoadedFile("rules", "/b/r.rules", "22"),
                new ValidationResult.LoadedFile("ontology", "/b/y.ttl", "11")), Map.of(), List.of());
        ValidationResult.Configuration c = new ValidationResult.Configuration("v", List.of(
                new ValidationResult.LoadedFile("ontology", "/a/x.ttl", "12")), Map.of(), List.of());
        assertEquals(TextReport.contentHash(a), TextReport.contentHash(b));
        assertFalse(TextReport.contentHash(a).equals(TextReport.contentHash(c)));
    }

    @Test
    void anUnreadableAlignmentRegisterIsSaidInTheProfile() throws IOException {
        try (HttpFixture http = new HttpFixture()) {
            http.body("/voc", 200, "text/turtle", "<urn:a> <urn:b> <urn:c> .");
            Path register = Files.writeString(dir.resolve("alignments.ttl"), "@prefix x <oops");
            start("--lov-url", http.base() + "/lov", "--allow-private-hosts", "--alignments", register.toString());
            String out = harness.callTool("vocabulary_profile", McpHarness.args(Map.of("vocabulary", http.base() + "/voc"))).text();
            assertTrue(out.startsWith("Alignment register skipped: Cannot read alignment register"), out);
            assertTrue(out.contains("register: none configured"), out);
        }
    }
}

