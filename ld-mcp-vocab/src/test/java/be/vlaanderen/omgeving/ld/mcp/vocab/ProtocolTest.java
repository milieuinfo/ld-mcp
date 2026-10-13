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
        List<String> args = new ArrayList<>(List.of("--ontologies", onto.toString()));
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
}
