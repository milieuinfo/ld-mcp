package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated shapes judged by what they do: per OWL construct a small ontology in
 * {@code src/test/resources/generator/} and data that is valid, lacks a value, or has a value of the
 * wrong kind, validated as validate_file does with --generate-shapes.
 */
class GeneratorBehaviourTest {

    private static final String PREFIXES = """
            @prefix ex:   <https://example.org/voc/> .
            @prefix d:    <https://example.org/data/> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix geo:  <http://www.opengis.net/ont/geosparql#> .
            """;

    @TempDir
    Path dir;

    static Model ontology(String fixture) throws IOException {
        Model m = ModelFactory.createDefaultModel();
        try (InputStream in = GeneratorBehaviourTest.class.getResourceAsStream("/generator/" + fixture)) {
            m.read(in, null, "TTL");
        }
        return m;
    }

    /** The SHACL part of the report for the data, with shapes generated from the fixture. */
    String shacl(String fixture, String data) throws IOException {
        Model onto = ontology(fixture);
        DataValidator v = new DataValidator(onto, new VocabIndex(onto), (Path) null, null, true, null);
        String report = v.validate(Files.writeString(dir.resolve("d.ttl"), PREFIXES + data).toString());
        return report.substring(report.indexOf("[SHACL]"));
    }

    static void conforms(String shacl) {
        assertTrue(shacl.startsWith("[SHACL] conforms") || shacl.startsWith("[SHACL] no shape applies"), shacl);
    }

    static void violates(String shacl, String expected) {
        assertTrue(shacl.contains(" violation(s)") && !shacl.startsWith("[SHACL] 0 violation(s)")
                && shacl.contains(expected), shacl);
    }

    // ------------------------------------------------------------ 3.8 datatypes

    @Test
    void rdfLangStringIsADatatype() throws IOException {
        conforms(shacl("datatypes.ttl", "d:p a ex:Place ; ex:name \"Gent\"@nl ."));
        violates(shacl("datatypes.ttl", "d:p a ex:Place ; ex:name d:x ."), "ex:name");
    }

    @Test
    void aDeclaredRdfsDatatypeIsADatatype() throws IOException {
        conforms(shacl("datatypes.ttl", "d:p a ex:Place ; ex:wkt \"POINT(4 51)\"^^geo:wktLiteral ."));
        violates(shacl("datatypes.ttl", "d:p a ex:Place ; ex:wkt \"POINT(4 51)\" ."), "ex:wkt");
    }

    @Test
    void rdfsLiteralMeansAnyLiteral() throws IOException {
        conforms(shacl("datatypes.ttl", "d:p a ex:Place ; ex:note \"x\" , 5 , \"y\"@en ."));
        violates(shacl("datatypes.ttl", "d:p a ex:Place ; ex:note d:x ."), "ex:note");
    }

    @Test
    void owlThingAndRdfsResourceConstrainNothing() throws IOException {
        conforms(shacl("datatypes.ttl", "d:p a ex:Place ; ex:anything d:untyped ; ex:either d:alsoUntyped ."));
    }
}
