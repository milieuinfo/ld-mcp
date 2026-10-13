package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    private static final String ONTOLOGY = """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix ex:   <https://example.org/voc/> .
            ex:Thing a owl:Class ; rdfs:subClassOf [ a owl:Restriction ; owl:onProperty ex:name ; owl:minCardinality 1 ] .
            ex:name a owl:DatatypeProperty .
            """;

    @TempDir
    Path dir;
    Path onto;
    String out;
    String err;

    @BeforeEach
    void ontology() throws IOException {
        onto = Files.createDirectories(dir.resolve("onto"));
        Files.writeString(onto.resolve("voc.ttl"), ONTOLOGY);
    }

    int run(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = Cli.run(args, "test", new PrintStream(o, true, StandardCharsets.UTF_8),
                new PrintStream(e, true, StandardCharsets.UTF_8));
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return code;
    }

    int validate(String... extra) {
        List<String> args = new ArrayList<>(List.of("validate", "--ontologies", onto.toString(), "--generate-shapes"));
        args.addAll(Arrays.asList(extra));
        return run(args.toArray(String[]::new));
    }

    Path data(String name, String ttl) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        return Files.writeString(p, "@prefix ex: <https://example.org/voc/> .\n" + ttl);
    }

    @Test
    void exitCodes() throws IOException {
        Path ok = data("ok.ttl", "<urn:a> a ex:Thing ; ex:name \"a\" .");
        Path bad = data("bad.ttl", "<urn:a> a ex:Thing .");
        Path suspect = data("suspect.ttl", "<urn:a> <http://vocab.invalid/x#p> 1 .");
        Path broken = data("broken.ttl", "not turtle");
        assertEquals(Cli.OK, validate(ok.toString()), out + err);
        assertTrue(out.contains("Summary: 1 file(s), 0 error(s), 0 suspect, 0 hint(s), 0 parse error(s)"), out);
        assertEquals(Cli.FINDINGS, validate(bad.toString()), out);
        assertEquals(Cli.OK, validate(suspect.toString()), out);
        assertEquals(Cli.FINDINGS, validate("--fail-on", "suspect", suspect.toString()), out);
        assertEquals(Cli.ERROR, validate(broken.toString(), ok.toString()), out);
        assertEquals(Cli.ERROR, validate("--format", "xml", ok.toString()));
        assertTrue(err.contains("--format must be text, json or ttl"), err);
        assertEquals(Cli.ERROR, validate());
        assertEquals(Cli.ERROR, validate("--shape", "x", ok.toString()));
        assertEquals(Cli.ERROR, run("validat", "x"));
        assertTrue(err.contains("Unknown command 'validat'"), err);
    }

    @Test
    void aConfigurationProblemIsExitCodeTwo() throws IOException {
        Files.writeString(onto.resolve("broken.ttl"), "not turtle");
        Path ok = data("ok.ttl", "<urn:a> a ex:Thing ; ex:name \"a\" .");
        assertEquals(Cli.ERROR, validate(ok.toString()));
        assertTrue(out.startsWith("Configuration problems:"), out);
    }

    @Test
    void directoriesAreWalkedWithoutTrigUnlessAsked() throws IOException {
        data("in/a.ttl", "<urn:a> a ex:Thing ; ex:name \"a\" .");
        data("in/sub/b.ttl", "<urn:b> a ex:Thing ; ex:name \"b\" .");
        Files.writeString(dir.resolve("in/c.trig"), "<urn:g> { <urn:c> a <https://example.org/voc/Thing> . }");
        Files.writeString(dir.resolve("in/readme.md"), "# no RDF");
        assertEquals(Cli.OK, validate(dir.resolve("in").toString()));
        assertTrue(out.contains("Summary: 2 file(s)"), out);
        assertEquals(Cli.FINDINGS, validate("--include-trig", dir.resolve("in").toString()));
        assertTrue(out.contains("Summary: 3 file(s), 1 error(s)"), out);
        Files.writeString(dir.resolve("in/big.jsonld"), "{\"@id\": \"urn:x\", \"@type\": \"https://example.org/voc/Thing\"}");
        assertEquals(Cli.FINDINGS, validate(dir.resolve("in").toString()));
        assertTrue(out.contains("Summary: 3 file(s)"), out);
        assertEquals(Cli.OK, validate("--extensions", "ttl", dir.resolve("in").toString()));
        assertTrue(out.contains("Summary: 2 file(s)"), out);
        assertEquals(Cli.ERROR, validate("--extensions", " , ", dir.resolve("in").toString()));
    }

    @Test
    void jsonOutput() throws IOException {
        Path bad = data("bad.ttl", "<urn:a> a ex:Thing .");
        assertEquals(Cli.FINDINGS, validate("--format", "json", bad.toString()));
        JsonObject json = JSON.parse(out);
        assertEquals("1.0", json.get("schemaVersion").getAsString().value());
        assertEquals(1, json.get("summary").getAsObject().get("files").getAsNumber().value().intValue());
        assertEquals(1, json.get("summary").getAsObject().get("error").getAsNumber().value().intValue());
        assertEquals("SHACL", json.get("results").getAsArray().get(0).getAsObject().get("steps").getAsArray()
                .get(3).getAsObject().get("kind").getAsString().value());
    }

    @Test
    void turtleOutputIsAShaclReport() throws IOException {
        Path bad = data("bad.ttl", "<urn:a> a ex:Thing ; <https://example.org/voc/nope> 1 .");
        assertEquals(Cli.FINDINGS, validate("--format", "ttl", bad.toString()));
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(out), null, "TTL");
        String q = "PREFIX sh: <http://www.w3.org/ns/shacl#> ASK { ?r a sh:ValidationReport ; sh:conforms false ; "
                + "sh:result [ sh:sourceConstraintComponent <urn:x-ld-mcp:component:unknown-property> ; "
                + "sh:focusNode <https://example.org/voc/nope> ] , [ sh:sourceConstraintComponent <urn:x-ld-mcp:component:shacl> ; "
                + "sh:focusNode <urn:a> ; sh:resultSeverity sh:Violation ] }";
        assertTrue(org.apache.jena.query.QueryExecutionFactory.create(q, m).execAsk(), out);
    }

    @Test
    void generateShapesIsDeterministicAndExcludesItsOwnOutput() throws IOException {
        Path target = onto.resolve("generated-shapes.ttl");
        assertEquals(Cli.OK, run("generate-shapes", "--ontologies", onto.toString(), "--out", target.toString()));
        String first = Files.readString(target);
        assertEquals(Cli.OK, run("generate-shapes", "--ontologies", onto.toString(), "--out", target.toString()));
        assertEquals(first, Files.readString(target), "a second run, with the output under --ontologies");
        assertFalse(first.contains("generated-shapes.ttl"), first);
        assertTrue(first.startsWith("# SHACL shapes generated by ld-mcp-vocab test"), first);
        assertTrue(first.contains("# Not translated (0):"), first);
        Model written = ModelFactory.createDefaultModel();
        written.read(new StringReader(first), null, "TTL");
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(ONTOLOGY), null, "TTL");
        assertTrue(written.isIsomorphicWith(ShaclGenerator.generate(ontology)), first);
    }

    @Test
    void canonicalTurtleRoundTripsTheGeneratedShapesOfAllFixtures() throws IOException {
        for (String fixture : List.of("datatypes.ttl", "some-values.ttl", "qualified.ttl", "intersection.ttl",
                "untranslatable.ttl")) {
            Model ontology = ModelFactory.createDefaultModel();
            try (InputStream in = getClass().getResourceAsStream("/generator/" + fixture)) {
                ontology.read(in, null, "TTL");
            }
            Model shapes = ShaclGenerator.generate(ontology);
            String text = CanonicalTurtle.write(shapes, ontology);
            Model back = ModelFactory.createDefaultModel();
            back.read(new StringReader(text), null, "TTL");
            assertTrue(back.isIsomorphicWith(shapes), fixture + "\n" + text);
            assertEquals(text, CanonicalTurtle.write(ShaclGenerator.generate(ontology), ontology), fixture);
        }
    }
}
