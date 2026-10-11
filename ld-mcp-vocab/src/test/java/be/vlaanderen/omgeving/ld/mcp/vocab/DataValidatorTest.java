package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataValidatorTest {

    private static final String ONTOLOGY = """
            @prefix sosa: <http://www.w3.org/ns/sosa/> .
            @prefix prov: <http://www.w3.org/ns/prov#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix ex:   <https://example.org/> .

            [] a owl:AllDisjointClasses ; owl:members ( ex:A ex:B ex:C ) .
            ex:Totaal owl:disjointUnionOf ( ex:D ex:E ) .
            prov:Activity a owl:Class ; owl:disjointWith prov:Entity .
            prov:Entity a owl:Class .
            sosa:Observation a owl:Class ; rdfs:subClassOf prov:Activity .
            sosa:implements a owl:ObjectProperty .
            sosa:hasInputValue a owl:ObjectProperty ; rdfs:range prov:Entity .
            """;

    private static final String RULES = """
            [rangeRule: (?p rdfs:range ?c), (?x ?p ?y) -> (?y rdf:type ?c)]
            [subClassRule: (?y rdfs:subClassOf ?z), (?x rdf:type ?y) -> (?x rdf:type ?z)]
            """;

    private static final String PREFIXES = """
            @prefix sosa: <http://www.w3.org/ns/sosa/> .
            @prefix ssn:  <http://www.w3.org/ns/ssn/> .
            @prefix ex:   <https://example.org/> .
            """;

    @TempDir
    Path dir;

    private DataValidator validator(boolean withRules) throws IOException {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(ONTOLOGY), null, "TTL");
        Path rules = withRules ? Files.writeString(dir.resolve("r.rules"), RULES) : null;
        return new DataValidator(ontology, new VocabIndex(ontology), rules, null);
    }

    private String data(String ttl) throws IOException {
        return Files.writeString(dir.resolve("data.ttl"), PREFIXES + ttl).toString();
    }

    @Test
    void unknownTermWithSuggestion() throws IOException {
        String out = validator(false).validate(data("ex:s ssn:implements ex:p ."));
        assertTrue(out.contains("[VOCAB ERROR] 1"), out);
        assertTrue(out.contains("sosa:implements"), out);
    }

    @Test
    void disjointViolationAfterInference() throws IOException {
        // hasInputValue naar een observatie: Activity én (via range) Entity.
        String out = validator(true).validate(data("""
                ex:a a sosa:Observation ; sosa:hasInputValue ex:b .
                ex:b a sosa:Observation .
                """));
        assertTrue(out.contains("[VOCAB] ok"), out);
        assertTrue(out.contains("[MODEL INVALID]"), out);
        assertTrue(out.contains("ex:b"), out);
    }

    @Test
    void allDisjointClassesBecomePairs() throws IOException {
        String out = validator(false).validate(data("ex:x a ex:A , ex:C ."));
        assertTrue(out.contains("[MODEL INVALID]"), out);
        assertTrue(out.contains("ex:x"), out);
    }

    @Test
    void disjointUnionMembersAreDisjoint() throws IOException {
        String out = validator(false).validate(data("ex:y a ex:D , ex:E ."));
        assertTrue(out.contains("[MODEL INVALID]"), out);
    }

    @Test
    void validWithoutRulesSkipsInference() throws IOException {
        String out = validator(false).validate(data("ex:a a sosa:Observation ."));
        assertTrue(out.contains("[INFERENTIE] overgeslagen"), out);
        assertTrue(out.contains("[OWL] consistent"), out);
        assertTrue(out.contains("[SHACL] overgeslagen"), out);
    }

    @Test
    void parseErrorIsReported() throws IOException {
        assertTrue(validator(false).validate(data("ex:a a .")).startsWith("PARSEFOUT"));
    }

    @Test
    void missingFileIsError() {
        assertThrows(IllegalArgumentException.class, () -> validator(false).validate("bestaat/niet.ttl"));
    }
}
