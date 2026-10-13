package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // ------------------------------------------------------------ 3.9 someValuesFrom

    @Test
    void someValuesFromNeedsOneValueOfTheClassNotAll() throws IOException {
        String f = "some-values.ttl";
        conforms(shacl(f, "d:w a ex:Whole ; ex:part d:p1 , d:p2 . d:p1 a ex:Part . d:p2 a ex:Ghost ."));
        violates(shacl(f, "d:w a ex:Whole ."), "ex:part");
        violates(shacl(f, "d:w a ex:Whole ; ex:part d:p2 . d:p2 a ex:Ghost ."), "ex:part");
    }

    @Test
    void someValuesFromAndAllValuesFromHoldSeparately() throws IOException {
        String f = "some-values.ttl";
        conforms(shacl(f, "d:s a ex:Strict ; ex:part d:p . d:p a ex:Part , ex:Named ."));
        conforms(shacl(f, "d:s a ex:Strict ; ex:part d:p , d:n . d:p a ex:Part , ex:Named . d:n a ex:Named ."));
        violates(shacl(f, "d:s a ex:Strict ; ex:part d:p . d:p a ex:Part ."), "ex:part");
        violates(shacl(f, "d:s a ex:Strict ; ex:part d:n . d:n a ex:Named ."), "ex:part");
    }

    @Test
    void minCardinalityZeroCancelsTheExistential() throws IOException {
        String f = "some-values.ttl";
        conforms(shacl(f, "d:r a ex:Relaxed ."));
        conforms(shacl(f, "d:r a ex:Relaxed ; ex:part d:g . d:g a ex:Ghost ."));
    }

    @Test
    void minCardinalityZeroCancelsExplicitMinimumsButKeepsTheRest() throws IOException {
        String f = "some-values.ttl";
        conforms(shacl(f, "d:i a ex:Imported ."));
        violates(shacl(f, "d:i a ex:Imported ; ex:vector 1 , 2 ."), "ex:vector");
        violates(shacl(f, "d:i a ex:Imported ; ex:kind d:g . d:g a ex:Ghost ."), "ex:kind");
    }

    @Test
    void someValuesFromThingNeedsAnyValue() throws IOException {
        String f = "some-values.ttl";
        conforms(shacl(f, "d:s a ex:Something ; ex:part d:untyped ."));
        violates(shacl(f, "d:s a ex:Something ."), "ex:part");
    }

    @Test
    void aRepeatedRestrictionGivesOneFinding() throws IOException {
        String out = shacl("some-values.ttl", "d:t a ex:Twice .");
        assertTrue(out.startsWith("[SHACL] 1 violation(s)"), out);
    }

    // ------------------------------------------------------------ 3.9 qualified and hasValue

    @Test
    void minQualifiedCardinality() throws IOException {
        String f = "qualified.ttl";
        String ok = "ex:code \"c\" ; ex:part d:a , d:b . d:a a ex:Part . d:b a ex:Part .";
        conforms(shacl(f, "d:m a ex:Machine ; " + ok));
        violates(shacl(f, "d:m a ex:Machine ; ex:code \"c\" ; ex:part d:a . d:a a ex:Part ."), "ex:part");
    }

    @Test
    void maxQualifiedCardinality() throws IOException {
        violates(shacl("qualified.ttl", "d:m a ex:Machine ; ex:code \"c\" ; ex:part d:a , d:b , d:x . "
                + "d:a a ex:Part . d:b a ex:Part . d:x a ex:Bad ."), "ex:part");
    }

    @Test
    void qualifiedCardinalityOnADataRange() throws IOException {
        String parts = " ex:part d:a , d:b . d:a a ex:Part . d:b a ex:Part .";
        violates(shacl("qualified.ttl", "d:m a ex:Machine ;" + parts), "ex:code");
        violates(shacl("qualified.ttl", "d:m a ex:Machine ; ex:code \"a\" , \"b\" ;" + parts), "ex:code");
        conforms(shacl("qualified.ttl", "d:m a ex:Machine ; ex:code \"a\" , 5 ;" + parts));
    }

    @Test
    void hasValue() throws IOException {
        conforms(shacl("qualified.ttl", "d:r a ex:Report ; ex:status ex:final ."));
        violates(shacl("qualified.ttl", "d:r a ex:Report ; ex:status ex:draft ."), "ex:status");
        violates(shacl("qualified.ttl", "d:r a ex:Report ."), "ex:status");
    }

    // ------------------------------------------------------------ 3.9 intersectionOf, rdfs:Class

    @Test
    void restrictionsInsideIntersectionsAndEquivalentClasses() throws IOException {
        String f = "intersection.ttl";
        violates(shacl(f, "d:x a ex:Defined ."), "ex:p");
        conforms(shacl(f, "d:x a ex:Defined ; ex:p 1 ."));
        violates(shacl(f, "d:y a ex:Capped ; ex:q 1 , 2 ."), "ex:q");
        conforms(shacl(f, "d:y a ex:Capped ; ex:q 1 ."));
    }

    @Test
    void aClassThatIsOnlyAnRdfsClassGetsAShape() throws IOException {
        violates(shacl("intersection.ttl", "d:z a ex:Plain ."), "ex:r");
    }

    // ------------------------------------------------------------ 3.9 / 3.10 not translated

    @Test
    void whatCannotBeTranslatedIsNotMadeStricter() throws IOException {
        conforms(shacl("untranslatable.ttl",
                "d:o a ex:Odd ; ex:mixed d:untyped ; ex:anon d:u2 ; ex:empty d:u3 ; ex:not d:u4 ."));
    }

    @Test
    void whatCannotBeTranslatedIsReported() throws IOException {
        ShaclGenerator.Result r = ShaclGenerator.translate(ontology("untranslatable.ttl"));
        String all = String.join("\n", r.notTranslated());
        assertEquals(6, r.notTranslated().size(), all);
        assertTrue(all.contains("ex:mixed") && all.contains("ex:anon") && all.contains("ex:empty")
                && all.contains("ex:not") && all.contains("owl:propertyChainAxiom")
                && all.contains("owl:complementOf"), all);
        assertTrue(ShaclGenerator.translate(ontology("qualified.ttl")).notTranslated().isEmpty());
    }
}

