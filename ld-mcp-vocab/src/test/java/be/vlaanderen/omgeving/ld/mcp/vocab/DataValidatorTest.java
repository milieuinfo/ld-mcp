package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
            @prefix voc:  <https://example.org/voc/> .
            @prefix qudt: <http://qudt.org/schema/qudt/> .
            @prefix unit: <http://qudt.org/vocab/unit/> .

            [] a owl:AllDisjointClasses ; owl:members ( voc:A voc:B voc:C ) .
            voc:Whole owl:disjointUnionOf ( voc:D voc:E ) .
            prov:Activity a owl:Class ; owl:disjointWith prov:Entity .
            prov:Entity a owl:Class .
            sosa:Observation a owl:Class ; rdfs:subClassOf prov:Activity .
            sosa:implements a owl:ObjectProperty .
            sosa:hasInputValue a owl:ObjectProperty ; rdfs:range prov:Entity .
            qudt:hasUnit a owl:ObjectProperty .
            unit:M a qudt:Unit ; rdfs:label "Metre"@en .
            """;

    private static final String RULES = """
            [rangeRule: (?p rdfs:range ?c), (?x ?p ?y) -> (?y rdf:type ?c)]
            [subClassRule: (?y rdfs:subClassOf ?z), (?x rdf:type ?y) -> (?x rdf:type ?z)]
            """;

    private static final String PREFIXES = """
            @prefix sosa: <http://www.w3.org/ns/sosa/> .
            @prefix ssn:  <http://www.w3.org/ns/ssn/> .
            @prefix ex:   <https://example.org/> .
            @prefix voc:  <https://example.org/voc/> .
            @prefix qudt: <http://qudt.org/schema/qudt/> .
            @prefix unit: <http://qudt.org/vocab/unit/> .
            """;

    @TempDir
    Path dir;

    private DataValidator validator(boolean withRules) throws IOException {
        return validator(withRules, null);
    }

    private DataValidator validator(boolean withRules, PrefixRegistry registry) throws IOException {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(ONTOLOGY), null, "TTL");
        Path rules = withRules ? Files.writeString(dir.resolve("r.rules"), RULES) : null;
        return new DataValidator(ontology, new VocabIndex(ontology), rules, null, false, registry);
    }

    private DataValidator validatorWithRegistry() throws IOException {
        return validator(false, PrefixRegistry.load(Files.writeString(dir.resolve("prefixes.ttl"), """
                @prefix dcat:   <http://www.w3.org/ns/dcat#> .
                @prefix schema: <http://schema.org/> .
                """)));
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
    void unknownObjectInVocabularyNamespace() throws IOException {
        String out = validator(false).validate(data("ex:r qudt:hasUnit unit:Meter ."));
        assertTrue(out.contains("Unknown resource: http://qudt.org/vocab/unit/Meter"), out);
    }

    @Test
    void knownObjectsAreAccepted() throws IOException {
        // unit:M exists; ex:other is outside the vocabulary namespaces.
        String out = validator(false).validate(data("ex:r qudt:hasUnit unit:M ; sosa:implements ex:other ."));
        assertTrue(out.contains("[VOCAB] ok"), out);
    }

    @Test
    void mintingInVocabularyNamespaceIsReported() throws IOException {
        String out = validator(false).validate(data("""
                ex:r qudt:hasUnit unit:EUR .
                unit:EUR a qudt:Unit .
                """));
        assertTrue(out.contains("Resource minted in a vocabulary namespace: http://qudt.org/vocab/unit/EUR"), out);
        assertTrue(out.contains("Unknown resource: http://qudt.org/vocab/unit/EUR"), out);
    }

    @Test
    void disjointViolationAfterInference() throws IOException {
        // hasInputValue pointing to an observation: Activity and (via the range) Entity.
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
        String out = validator(false).validate(data("ex:x a voc:A , voc:C ."));
        assertTrue(out.contains("[MODEL INVALID]"), out);
        assertTrue(out.contains("ex:x"), out);
    }

    @Test
    void disjointUnionMembersAreDisjoint() throws IOException {
        String out = validator(false).validate(data("ex:y a voc:D , voc:E ."));
        assertTrue(out.contains("[MODEL INVALID]"), out);
    }

    @Test
    void validWithoutRulesSkipsInference() throws IOException {
        String out = validator(false).validate(data("ex:a a sosa:Observation ."));
        assertTrue(out.contains("[INFERENCE] skipped"), out);
        assertTrue(out.contains("[OWL] consistent"), out);
        assertTrue(out.contains("[SHACL] skipped"), out);
    }

    @Test
    void httpsVariantOfKnownPropertyGetsHint() throws IOException {
        String out = validator(false).validate(data("""
                @prefix qudts: <https://qudt.org/schema/qudt/> .
                ex:r qudts:hasUnit unit:M .
                """));
        assertTrue(out.contains("Unknown property: https://qudt.org/schema/qudt/hasUnit"), out);
        assertTrue(out.contains("Did you mean <http://qudt.org/schema/qudt/hasUnit>? (differs only in http/https)"), out);
    }

    @Test
    void httpsVariantOfKnownUnitIsReported() throws IOException {
        // The object's namespace is not a vocabulary namespace itself, only its http variant is.
        String out = validator(false).validate(data("""
                @prefix units: <https://qudt.org/vocab/unit/> .
                ex:r qudt:hasUnit units:M .
                """));
        assertTrue(out.contains("Unknown resource: https://qudt.org/vocab/unit/M"), out);
        assertTrue(out.contains("Did you mean <http://qudt.org/vocab/unit/M>?"), out);
    }

    @Test
    void separatorVariantGetsNamespaceHint() throws IOException {
        String out = validator(false).validate(data("""
                ex:r <http://www.w3.org/ns/sosa#unknownProperty> ex:x .
                """));
        assertTrue(out.contains("Namespace <http://www.w3.org/ns/sosa#> is not loaded; "
                + "did you mean <http://www.w3.org/ns/sosa/>? (differs only in '#'/'/')"), out);
    }

    @Test
    void unrelatedNamespacesAreNotReported() throws IOException {
        // http variant of the data's own https://example.org/ namespace is not a vocabulary namespace.
        String out = validator(false).validate(data("ex:r sosa:implements <http://example.org/x> ."));
        assertTrue(out.contains("[VOCAB] ok"), out);
    }

    @Test
    void registeredNamespaceIsKnownButNotLoaded() throws IOException {
        String out = validatorWithRegistry().validate(data("ex:r <http://www.w3.org/ns/dcat#keyword> \"x\" ."));
        assertTrue(out.contains("Namespace <http://www.w3.org/ns/dcat#> is a known vocabulary "
                + "('dcat:' in the prefix registry), but it is not loaded"), out);
    }

    @Test
    void variantOfRegisteredNamespaceGetsHint() throws IOException {
        String out = validatorWithRegistry().validate(data("ex:r <https://schema.org/name> \"x\" ."));
        assertTrue(out.contains("Namespace <https://schema.org/> is neither loaded nor registered; "
                + "did you mean <http://schema.org/> ('schema:' in the prefix registry)? "
                + "(differs only in http/https)"), out);
    }

    @Test
    void unregisteredNamespaceIsFlagged() throws IOException {
        String out = validatorWithRegistry().validate(data("ex:r <http://vocab.invalid/nope#p> \"x\" ."));
        assertTrue(out.contains("Namespace <http://vocab.invalid/nope#> is neither loaded nor in the "
                + "prefix registry; check it for a typo, or load its ontology"), out);
    }

    @Test
    void exampleDomainsGetNoRegistryHint() throws IOException {
        String out = validatorWithRegistry().validate(data("ex:r <http://example.com/p> \"x\" ."));
        assertTrue(out.contains("Unknown property: http://example.com/p"), out);
        assertTrue(!out.contains("prefix registry"), out);
    }

    @Test
    void loadedVariantTakesPrecedenceOverRegistry() throws IOException {
        String out = validatorWithRegistry().validate(data("""
                @prefix qudts: <https://qudt.org/schema/qudt/> .
                ex:r qudts:hasUnit unit:M .
                """));
        assertTrue(out.contains("Did you mean <http://qudt.org/schema/qudt/hasUnit>?"), out);
        assertTrue(!out.contains("prefix registry"), out);
    }

    @Test
    void withoutRegistryNoNamespaceHint() throws IOException {
        String out = validator(false).validate(data("ex:r <http://www.w3.org/ns/dcat#keyword> \"x\" ."));
        assertTrue(out.contains("Unknown property: http://www.w3.org/ns/dcat#keyword"), out);
        assertTrue(!out.contains("prefix registry"), out);
    }

    @Test
    void shaclReportsWarningsSeparately() throws IOException {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(ONTOLOGY), null, "TTL");
        Path shapes = Files.writeString(dir.resolve("shapes.ttl"), """
                @prefix sh:   <http://www.w3.org/ns/shacl#> .
                @prefix sosa: <http://www.w3.org/ns/sosa/> .
                [] a sh:NodeShape ; sh:targetClass sosa:Observation ;
                   sh:property [ sh:path sosa:madeBySensor ; sh:minCount 1 ; sh:severity sh:Warning ;
                                 sh:message "R2: madeBySensor aanbevolen" ] .
                """);
        DataValidator validator = new DataValidator(ontology, new VocabIndex(ontology), null, shapes, false, null);
        String out = validator.validate(data("ex:a a sosa:Observation ."));
        assertTrue(out.contains("[SHACL] 0 violation(s), 1 warning(s)"), out);
        assertTrue(out.contains("- [warning] ex:a  path: sosa:madeBySensor  — R2: madeBySensor aanbevolen"), out);
    }

    @Test
    void variantsCoverSchemeAndSeparator() {
        assertEquals(List.of("https://x.org/ns#a", "http://x.org/ns/a", "https://x.org/ns/a"),
                DataValidator.variants("http://x.org/ns#a"));
    }

    @Test
    void parseErrorIsReported() throws IOException {
        assertTrue(validator(false).validate(data("ex:a a .")).startsWith("PARSE ERROR"));
    }

    @Test
    void missingFileIsError() {
        assertThrows(IllegalArgumentException.class, () -> validator(false).validate("does/not/exist.ttl"));
    }

    private ValidationResult.Finding onlyVocabFinding(DataValidator v, String ttl) throws IOException {
        ValidationResult r = v.check(Path.of(data(ttl)), "data.ttl");
        ValidationResult.Step vocab = r.steps().get(0);
        assertEquals(ValidationResult.Kind.VOCAB, vocab.kind());
        assertEquals(1, vocab.findings().size(), vocab.findings().toString());
        return vocab.findings().get(0);
    }

    @Test
    void certaintyAndCodeOfVocabularyFindings() throws IOException {
        DataValidator v = validator(false);
        ValidationResult.Finding unknownInLoaded = onlyVocabFinding(v, "ex:r qudt:noSuchProperty unit:M .");
        assertEquals(ValidationResult.Certainty.ERROR, unknownInLoaded.certainty());
        assertEquals("unknown-property", unknownInLoaded.code());
        assertEquals("http://qudt.org/schema/qudt/noSuchProperty", unknownInLoaded.focus());

        ValidationResult.Finding nearMiss = onlyVocabFinding(v, """
                @prefix qudts: <https://qudt.org/schema/qudt/> .
                ex:r qudts:hasUnit unit:M .
                """);
        assertEquals(ValidationResult.Certainty.SUSPECT, nearMiss.certainty());

        ValidationResult.Finding unloaded = onlyVocabFinding(v, "ex:r <http://vocab.invalid/nope#p> \"x\" .");
        assertEquals(ValidationResult.Certainty.SUSPECT, unloaded.certainty());

        ValidationResult.Finding unknownClass = onlyVocabFinding(v, "ex:r a voc:Nope .");
        assertEquals("unknown-class", unknownClass.code());
        assertEquals(ValidationResult.Certainty.ERROR, unknownClass.certainty());
    }

    @Test
    void aRegisteredButUnloadedNamespaceIsOnlyAHint() throws IOException {
        ValidationResult.Finding f = onlyVocabFinding(validatorWithRegistry(),
                "ex:r <http://www.w3.org/ns/dcat#keyword> \"x\" .");
        assertEquals(ValidationResult.Certainty.HINT, f.certainty());
        assertTrue(f.hints().get(0).contains("is a known vocabulary"), f.hints().toString());
        ValidationResult.Finding unregistered = onlyVocabFinding(validatorWithRegistry(),
                "ex:r <http://vocab.invalid/nope#p> \"x\" .");
        assertEquals(ValidationResult.Certainty.SUSPECT, unregistered.certainty());
    }

    @Test
    void mintingIsAnError() throws IOException {
        ValidationResult r = validator(false).check(Path.of(data("unit:EUR a qudt:Unit .")), "d");
        ValidationResult.Finding minted = r.steps().get(0).findings().stream()
                .filter(f -> f.code().equals("minted-in-vocabulary-namespace")).findFirst().orElseThrow();
        assertEquals(ValidationResult.Certainty.ERROR, minted.certainty());
    }

    @Test
    void theResultHasFourStepsAndAParseErrorNone() throws IOException {
        ValidationResult r = validator(true).check(Path.of(data("ex:r sosa:implements ex:p .")), "d");
        assertEquals(List.of(ValidationResult.Kind.VOCAB, ValidationResult.Kind.INFERENCE,
                ValidationResult.Kind.DISJOINTNESS, ValidationResult.Kind.SHACL),
                r.steps().stream().map(ValidationResult.Step::kind).toList());
        assertEquals(ValidationResult.Status.SKIPPED, r.steps().get(3).status());
        Path broken = Files.writeString(dir.resolve("broken.ttl"), "not turtle");
        ValidationResult bad = validator(false).check(broken, "broken.ttl");
        assertTrue(bad.steps().isEmpty());
        assertTrue(bad.header().parseError() != null);
        assertEquals(-1, bad.header().triples());
    }
}

