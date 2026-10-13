package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.io.StringWriter;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

class ShaclGeneratorTest {

    private static final String PREFIXES = """
            @prefix ex:   <https://example.org/> .
            @prefix sh:   <http://www.w3.org/ns/shacl#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .
            @prefix shape: <urn:x-ld-mcp:shape:> .
            """;

    private static Model parse(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(PREFIXES + ttl), null, "TTL");
        return m;
    }

    /**
     * Compares the generated shapes with the expected ones, regardless of blank node labels and
     * without the messages (tested apart).
     */
    private static void assertShapes(String ontology, String expected) {
        Model actual = ShaclGenerator.generate(parse(ontology));
        actual.removeAll(null, actual.createProperty(ShaclGenerator.SH + "message"), null);
        StringWriter out = new StringWriter();
        actual.write(out, "TTL");
        assertTrue(actual.isIsomorphicWith(parse(expected)), out.toString());
    }

    @Test
    void someValuesFromGivesAQualifiedShapeOfItsOwn() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:B ] ,
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:maxCardinality 1 ] .
                """, """
                <urn:x-ld-mcp:shape:https%3A%2F%2Fexample.org%2FA> a sh:NodeShape ; sh:targetClass ex:A ;
                    sh:property [ sh:path ex:p ; sh:maxCount "1"^^xsd:long ] ,
                                [ sh:path ex:p ; sh:qualifiedValueShape [ sh:class ex:B ] ;
                                  sh:qualifiedMinCount "1"^^xsd:long ] .
                """);
    }

    @Test
    void unionGivesOrAndDatatype() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty ex:p ;
                      owl:allValuesFrom [ owl:unionOf ( xsd:string ex:B ) ] ] .
                """, """
                <urn:x-ld-mcp:shape:https%3A%2F%2Fexample.org%2FA> a sh:NodeShape ; sh:targetClass ex:A ;
                    sh:property [ sh:path ex:p ; sh:or ( [ sh:datatype xsd:string ] [ sh:class ex:B ] ) ] .
                """);
    }

    @Test
    void minCardinalityZeroCancelsSomeValuesFrom() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:B ] ,
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:minCardinality 0 ] .
                """, "");
    }

    @Test
    void anonymousInverseGivesInversePath() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty [ owl:inverseOf ex:q ] ;
                      owl:cardinality 1 ] .
                """, """
                <urn:x-ld-mcp:shape:https%3A%2F%2Fexample.org%2FA> a sh:NodeShape ; sh:targetClass ex:A ;
                    sh:property [ sh:path [ sh:inversePath ex:q ] ;
                                  sh:minCount "1"^^xsd:long ; sh:maxCount "1"^^xsd:long ] .
                """);
    }

    @Test
    void everyPropertyShapeHasAReadableMessage() {
        Model shapes = ShaclGenerator.generate(parse("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:B ] ,
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:maxCardinality 1 ] ,
                    [ a owl:Restriction ; owl:onProperty [ owl:inverseOf ex:q ] ;
                      owl:allValuesFrom [ owl:unionOf ( ex:B xsd:string ) ] ] .
                """));
        java.util.List<String> messages = shapes.listObjectsOfProperty(shapes.createProperty(ShaclGenerator.SH + "message"))
                .mapWith(n -> n.asLiteral().getLexicalForm()).toList().stream().sorted().toList();
        assertEquals(java.util.List.of(
                "ex:A: ^ex:q needs every value is one of (literal of xsd:string, ex:B) (generated from the OWL restrictions)",
                "ex:A: ex:p needs at least 1 value(s) that is a ex:B (generated from the OWL restrictions)",
                "ex:A: ex:p needs at most 1 value(s) (generated from the OWL restrictions)"), messages);
    }
}

