package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

class VocabIndexTest {

    private static final String TTL = """
            @prefix sosa: <http://www.w3.org/ns/sosa/> .
            @prefix ssn:  <http://www.w3.org/ns/ssn/> .
            @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .

            sosa:Observation a owl:Class ; rdfs:label "Observation"@en .
            sosa:Sample a owl:Class ; rdfs:subClassOf sosa:FeatureOfInterest .
            sosa:SpatialSample a owl:Class ; rdfs:subClassOf sosa:Sample .
            sosa:implements a owl:ObjectProperty ;
                rdfs:comment "A relation between an entity and a Procedure."@en .
            ssn:System a owl:Class .
            """;

    private final VocabIndex index = new VocabIndex(model());

    private static Model model() {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(TTL), null, "TTL");
        return m;
    }

    @Test
    void lookupKnownTerm() {
        String out = index.lookup("sosa:Sample");
        assertTrue(out.contains("subClassOf: sosa:FeatureOfInterest"), out);
        assertTrue(out.contains("subclasses: sosa:SpatialSample"), out);
    }

    @Test
    void lookupSuggestsOtherNamespace() {
        String out = index.lookup("ssn:implements");
        assertTrue(out.startsWith("Not found"), out);
        assertTrue(out.contains("sosa:implements"), out);
    }

    @Test
    void unknownPrefixIsError() {
        assertThrows(IllegalArgumentException.class, () -> index.lookup("foo:Bar"));
    }

    @Test
    void searchRanksExactMatchFirst() {
        String out = index.search("sample", 10);
        assertTrue(out.indexOf("sosa:Sample ") < out.indexOf("sosa:SpatialSample"), out);
    }
}
