package be.vlaanderen.omgeving.ld.mcp.vocab;

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
            """;

    private static Model parse(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(PREFIXES + ttl), null, "TTL");
        return m;
    }

    /** Compares the generated shapes with the expected ones, regardless of blank node labels. */
    private static void assertShapes(String ontology, String expected) {
        Model actual = ShaclGenerator.generate(parse(ontology));
        StringWriter out = new StringWriter();
        actual.write(out, "TTL");
        assertTrue(actual.isIsomorphicWith(parse(expected)), out.toString());
    }

    @Test
    void someValuesFromGivesClassAndMinCount() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:B ] ,
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:maxCardinality 1 ] .
                """, """
                ex:AShape a sh:NodeShape ; sh:targetClass ex:A ;
                    sh:property [ sh:path ex:p ; sh:class ex:B ;
                                  sh:minCount "1"^^xsd:long ; sh:maxCount "1"^^xsd:long ] .
                """);
    }

    @Test
    void unionGivesOrAndDatatype() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty ex:p ;
                      owl:allValuesFrom [ owl:unionOf ( xsd:string ex:B ) ] ] .
                """, """
                ex:AShape a sh:NodeShape ; sh:targetClass ex:A ;
                    sh:property [ sh:path ex:p ; sh:or ( [ sh:datatype xsd:string ] [ sh:class ex:B ] ) ] .
                """);
    }

    @Test
    void minCardinalityZeroRelaxesSomeValuesFrom() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:someValuesFrom ex:B ] ,
                    [ a owl:Restriction ; owl:onProperty ex:p ; owl:minCardinality 0 ] .
                """, """
                ex:AShape a sh:NodeShape ; sh:targetClass ex:A ;
                    sh:property [ sh:path ex:p ; sh:class ex:B ] .
                """);
    }

    @Test
    void anonymousInverseGivesInversePath() {
        assertShapes("""
                ex:A a owl:Class ; rdfs:subClassOf
                    [ a owl:Restriction ; owl:onProperty [ owl:inverseOf ex:q ] ;
                      owl:cardinality 1 ] .
                """, """
                ex:AShape a sh:NodeShape ; sh:targetClass ex:A ;
                    sh:property [ sh:path [ sh:inversePath ex:q ] ;
                                  sh:minCount "1"^^xsd:long ; sh:maxCount "1"^^xsd:long ] .
                """);
    }
}
