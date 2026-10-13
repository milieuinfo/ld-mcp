package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        // ssn: is not loaded here, so the term cannot be ruled out: a suspect, not an error.
        String out = validator(false).validate(data("ex:s ssn:implements ex:p ."));
        assertTrue(out.contains("[VOCAB] 0 error(s), 1 suspect, 0 hint(s)"), out);
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
        // Reported once: not again as an unknown resource where the data uses it.
        assertFalse(out.contains("Unknown resource: http://qudt.org/vocab/unit/EUR"), out);
    }

    @Test
    void disjointViolationAfterInference() throws IOException {
        // hasInputValue pointing to an observation: Activity and (via the range) Entity.
        String out = validator(true).validate(data("""
                ex:a a sosa:Observation ; sosa:hasInputValue ex:b .
                ex:b a sosa:Observation .
                """));
        assertTrue(out.contains("[VOCAB] ok"), out);
        assertTrue(out.contains("[DISJOINTNESS] 1 violation(s)"), out);
        assertTrue(out.contains("ex:b"), out);
    }

    @Test
    void allDisjointClassesBecomePairs() throws IOException {
        String out = validator(false).validate(data("ex:x a voc:A , voc:C ."));
        assertTrue(out.contains("[DISJOINTNESS] 1 violation(s)"), out);
        assertTrue(out.contains("ex:x"), out);
    }

    @Test
    void disjointUnionMembersAreDisjoint() throws IOException {
        String out = validator(false).validate(data("ex:y a voc:D , voc:E ."));
        assertTrue(out.contains("[DISJOINTNESS] 1 violation(s)"), out);
    }

    @Test
    void validWithoutRulesSkipsInference() throws IOException {
        String out = validator(false).validate(data("ex:a a sosa:Observation ."));
        assertTrue(out.contains("[INFERENCE] skipped"), out);
        assertTrue(out.contains("[DISJOINTNESS] ok"), out);
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
        assertTrue(out.contains("[SHACL] 0 violation(s), 1 warning(s) (1 of 1 shapes applied to 1 focus node(s); "
                + "graph: asserted data + class hierarchy + types of referenced terms)"), out);
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

    private DataValidator withShapes(String shapesTtl) throws IOException {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(ONTOLOGY), null, "TTL");
        Path shapes = Files.writeString(dir.resolve("shapes.ttl"), shapesTtl);
        return new DataValidator(ontology, new VocabIndex(ontology), null, shapes, false, null);
    }

    @Test
    void shaclSaysWhenNoShapeApplies() throws IOException {
        DataValidator v = withShapes("""
                @prefix sh:   <http://www.w3.org/ns/shacl#> .
                @prefix sosa: <http://www.w3.org/ns/sosa/> .
                [] a sh:NodeShape ; sh:targetClass sosa:Observation ; sh:property [ sh:path sosa:madeBySensor ; sh:minCount 1 ] .
                [] a sh:NodeShape ; sh:targetClass sosa:Sample ; sh:property [ sh:path sosa:isSampleOf ; sh:minCount 1 ] .
                """);
        String none = v.validate(data("ex:a a voc:A ."));
        assertTrue(none.contains("[SHACL] no shape applies to this data (2 shapes loaded; graph: asserted data + class hierarchy + types of referenced terms)"), none);
        String some = v.validate(data("ex:a a sosa:Observation ; sosa:madeBySensor ex:s ."));
        assertTrue(some.contains("[SHACL] conforms (1 of 2 shapes applied to 1 focus node(s); graph: asserted data + class hierarchy + types of referenced terms)"), some);
    }

    @Test
    void vocabularyFindingsAreTaggedAndErrorsComeFirst() throws IOException {
        String out = validatorWithRegistry().validate(data("""
                ex:a <http://www.w3.org/ns/dcat#keyword> "x" ; qudt:noSuch unit:M .
                """));
        assertTrue(out.contains("[VOCAB] 1 error(s), 0 suspect, 1 hint(s)"), out);
        assertTrue(out.indexOf("- [error] Unknown property: http://qudt.org/schema/qudt/noSuch")
                < out.indexOf("- [hint] Unknown property: http://www.w3.org/ns/dcat#keyword"), out);
    }

    @Test
    void disjointnessWarningsAreCountedApart() {
        ValidationResult.Step step = new ValidationResult.Step(ValidationResult.Kind.DISJOINTNESS,
                ValidationResult.Status.FINDINGS, List.of(new ValidationResult.Finding(ValidationResult.Certainty.SUSPECT,
                        "disjointness", "x", null, "odd", List.of(), null)), Map.of("valid", true));
        String out = TextReport.render(new ValidationResult(new ValidationResult.Header("f", 1, null, null), List.of(step)));
        assertTrue(out.contains("[DISJOINTNESS] 0 violation(s), 1 warning(s)\n- [warning] odd"), out);
    }

    @Test
    void disjointnessFollowsTheClassHierarchyWithoutRules() throws IOException {
        // sosa:Observation is a subclass of prov:Activity, which is disjoint with prov:Entity.
        String out = validator(false).validate(data("""
                @prefix prov: <http://www.w3.org/ns/prov#> .
                ex:o a sosa:Observation , prov:Entity .
                """));
        assertTrue(out.contains("[INFERENCE] skipped"), out);
        assertTrue(out.contains("[DISJOINTNESS] 1 violation(s)"), out);
    }

    private static final String HIERARCHY = """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix voc:  <https://example.org/voc/> .
            voc:Part a owl:Class . voc:SubPart a owl:Class ; rdfs:subClassOf voc:Part .
            voc:Thing a owl:Class . voc:Special a owl:Class ; rdfs:subClassOf voc:Thing .
            voc:part a owl:ObjectProperty .
            """;

    private static final String PART_SHAPE = """
            @prefix sh:  <http://www.w3.org/ns/shacl#> .
            @prefix voc: <https://example.org/voc/> .
            [] a sh:NodeShape ; sh:targetClass voc:Thing ;
               sh:property [ sh:path voc:part ; sh:minCount 1 ; sh:class voc:Part ] .
            """;

    private DataValidator hierarchyValidator() throws IOException {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(HIERARCHY), null, "TTL");
        Path shapes = Files.writeString(dir.resolve("shapes.ttl"), PART_SHAPE);
        return new DataValidator(ontology, new VocabIndex(ontology), null, shapes, false, null);
    }

    @Test
    void shaclClassAcceptsAnInstanceOfASubclass() throws IOException {
        String out = hierarchyValidator().validate(data("ex:t a voc:Thing ; voc:part ex:p . ex:p a voc:SubPart ."));
        assertTrue(out.contains("[SHACL] conforms"), out);
        assertTrue(out.contains("graph: asserted data + class hierarchy + types of referenced terms"), out);
    }

    @Test
    void aShapeOnASuperclassTargetsInstancesOfASubclass() throws IOException {
        String out = hierarchyValidator().validate(data("ex:s a voc:Special ."));
        assertTrue(out.contains("[SHACL] 1 violation(s)"), out);
        assertTrue(out.contains("- ex:s  path: voc:part  — minCount[1]"), out);
    }

    @Test
    void theHierarchyItselfIsNotValidated() throws IOException {
        // The ontology's classes are no focus nodes: only rdfs:subClassOf is added, no rdf:type.
        String out = hierarchyValidator().validate(data("ex:t a voc:Thing ; voc:part ex:p . ex:p a voc:Part ."));
        assertTrue(out.contains("[SHACL] conforms (1 of 1 shapes applied to 1 focus node(s)"), out);
    }

    @Test
    void aReferencedTermCountsWithTheTypeTheOntologyGivesIt() throws IOException {
        // unit:M a qudt:Unit is in the ontology, not in the data.
        DataValidator v = withShapes("""
                @prefix sh:   <http://www.w3.org/ns/shacl#> .
                @prefix qudt: <http://qudt.org/schema/qudt/> .
                @prefix sosa: <http://www.w3.org/ns/sosa/> .
                [] a sh:NodeShape ; sh:targetClass sosa:Observation ;
                   sh:property [ sh:path qudt:hasUnit ; sh:class qudt:Unit ] .
                [] a sh:NodeShape ; sh:targetClass qudt:Unit ;
                   sh:property [ sh:path qudt:hasQuantityKind ; sh:minCount 1 ] .
                """);
        String out = v.validate(data("ex:o a sosa:Observation ; qudt:hasUnit unit:M ."));
        assertTrue(out.contains("[SHACL] conforms (1 of 2 shapes applied to 1 focus node(s)"), out);
        String wrong = v.validate(data("ex:o a sosa:Observation ; qudt:hasUnit ex:notAUnit ."));
        assertTrue(wrong.contains("[SHACL] 1 violation(s)"), wrong);
    }

    @Test
    void aReferencedTermIsStillCheckedWhenAShapeTargetsItAsObject() throws IOException {
        DataValidator v = withShapes("""
                @prefix sh:   <http://www.w3.org/ns/shacl#> .
                @prefix qudt: <http://qudt.org/schema/qudt/> .
                [] a sh:NodeShape ; sh:targetObjectsOf qudt:hasUnit ;
                   sh:property [ sh:path qudt:hasQuantityKind ; sh:minCount 1 ] .
                """);
        String out = v.validate(data("ex:o qudt:hasUnit unit:M ."));
        assertTrue(out.contains("[SHACL] 1 violation(s)") && out.contains("unit:M  path: qudt:hasQuantityKind"), out);
    }

    private DataValidator validatorFor(String ontologyTtl) {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(ontologyTtl), null, "TTL");
        return new DataValidator(ontology, new VocabIndex(ontology), (Path) null, null, false, null);
    }

    private static final String ROLES = """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix voc:  <https://example.org/voc/> .
            voc:Thing a owl:Class .
            voc:part a owl:ObjectProperty ; rdfs:range voc:Ghost .
            """;

    @Test
    void aTermThatIsOnlyReferencedIsNotDefined() throws IOException {
        ValidationResult r = validatorFor(ROLES).check(Path.of(data("ex:a a voc:Ghost .")), "d");
        ValidationResult.Finding f = r.steps().get(0).findings().get(0);
        assertEquals("undefined-term", f.code());
        assertEquals(ValidationResult.Certainty.SUSPECT, f.certainty());
        assertEquals("Term only referenced, not defined: https://example.org/voc/Ghost", f.message());
    }

    @Test
    void rolesAreChecked() throws IOException {
        String out = validatorFor(ROLES).validate(data("ex:a a voc:part ; voc:Thing ex:b ."));
        assertTrue(out.contains("[VOCAB] 2 error(s)"), out);
        assertTrue(out.contains("- [error] Property used as class: https://example.org/voc/part"), out);
        assertTrue(out.contains("- [error] Class used as property: https://example.org/voc/Thing"), out);
    }

    @Test
    void builtInTermsAreKnownWithoutAnOntologyMentioningThem() throws IOException {
        String out = validatorFor(ROLES).validate(data("""
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix owl:  <http://www.w3.org/2002/07/owl#> .
                @prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                ex:a a voc:Thing , owl:NamedIndividual ; rdfs:label "a" ; rdfs:seeAlso ex:b ; owl:sameAs ex:c ;
                     owl:differentFrom ex:d ; rdf:value 1 ; rdf:_3 ex:e .
                """));
        assertTrue(out.contains("[VOCAB] ok"), out);
        String typo = validatorFor(ROLES).validate(data("""
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                ex:a rdfs:lable "a" .
                """));
        assertTrue(typo.contains("Unknown property: http://www.w3.org/2000/01/rdf-schema#lable"), typo);
    }

    @Test
    void lookupAndValidationAgree() throws IOException {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(ROLES), null, "TTL");
        VocabIndex index = new VocabIndex(ontology);
        assertTrue(index.lookup("https://example.org/voc/Ghost").startsWith("Not found"), index.lookup("https://example.org/voc/Ghost"));
        assertTrue(index.lookup("https://example.org/voc/Ghost").contains("mention it but do not declare it"));
        assertTrue(index.lookup("http://www.w3.org/2000/01/rdf-schema#label").contains("built-in term"));
        assertTrue(index.lookup("https://example.org/voc/Thing").startsWith("<https://example.org/voc/Thing>")
                || index.lookup("https://example.org/voc/Thing").contains("owl:Class"));
    }

    @Test
    void datatypesAndLiteralsAreChecked() throws IOException {
        DataValidator v = validator(false);
        String out = v.validate(data("""
                @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
                ex:a sosa:implements ex:b ;
                     <http://www.w3.org/2000/01/rdf-schema#label> "x"^^xsd:strin , "not a date"^^xsd:dateTime ,
                         "5"^^xsd:integer , "naam"@nl , "1.5"^^unit:M , "y"^^<https://dt.invalid/own> .
                """));
        assertTrue(out.contains("- [error] Unknown datatype: http://www.w3.org/2001/XMLSchema#strin\n"
                + "    Did you mean <http://www.w3.org/2001/XMLSchema#string>?"), out);
        assertTrue(out.contains("- [error] Invalid literal: \"not a date\"^^http://www.w3.org/2001/XMLSchema#dateTime"), out);
        assertTrue(out.contains("- [error] Not a datatype: http://qudt.org/vocab/unit/M"), out);
        assertTrue(out.contains("- [suspect] Unknown datatype: https://dt.invalid/own"), out);
        assertFalse(out.contains("integer"), out);
    }

    @Test
    void aDatatypeDeclaredInTheOntologyIsKnown() throws IOException {
        String out = validatorFor(ROLES + "voc:code a rdfs:Datatype .").validate(data(
                "ex:a <http://www.w3.org/2000/01/rdf-schema#label> \"A-1\"^^voc:code ."));
        assertTrue(out.contains("[VOCAB] ok"), out);
    }

    @Test
    void levenshtein() {
        assertEquals(1, DataValidator.distance("dateTim", "dateTime"));
        assertEquals(0, DataValidator.distance("", ""));
        assertEquals(3, DataValidator.distance("abc", ""));
    }

    private static final String DEPRECATED = ROLES + """
            @prefix dct: <http://purl.org/dc/terms/> .
            voc:old a owl:ObjectProperty ; owl:deprecated true ; dct:isReplacedBy voc:part .
            voc:OldThing a owl:DeprecatedClass ; owl:equivalentClass voc:Thing .
            voc:fine a owl:ObjectProperty ; owl:deprecated false .
            """;

    @Test
    void deprecatedTermsAreSuspectWithTheirReplacement() throws IOException {
        String out = validatorFor(DEPRECATED).validate(data("ex:a a voc:OldThing ; voc:old ex:b ; voc:fine ex:c ."));
        assertTrue(out.contains("[VOCAB] 0 error(s), 2 suspect, 0 hint(s)"), out);
        assertTrue(out.contains("- [suspect] Deprecated term: https://example.org/voc/old\n    Replaced by "), out);
        assertTrue(out.contains("Deprecated term: https://example.org/voc/OldThing"), out);
        assertFalse(out.contains("voc/fine"), out);
    }

    @Test
    void lookupShowsDeprecation() {
        Model ontology = ModelFactory.createDefaultModel();
        ontology.read(new StringReader(DEPRECATED), null, "TTL");
        VocabIndex index = new VocabIndex(ontology);
        assertTrue(index.lookup("https://example.org/voc/old").contains("  deprecated: true (replaced by "),
                index.lookup("https://example.org/voc/old"));
        assertFalse(index.lookup("https://example.org/voc/fine").contains("deprecated"));
    }

    @Test
    void anOntologyHeaderOrAShapeDoesNotMakeAVocabularyNamespace() throws IOException {
        String out = validatorFor(ROLES + """
                @prefix sh: <http://www.w3.org/ns/shacl#> .
                <https://example.org/onto> a owl:Ontology .
                <https://example.org/shapes/ThingShape> a sh:NodeShape ; sh:targetClass voc:Thing .
                """).validate(data("ex:t1 a voc:Thing . <https://example.org/shapes/s1> a voc:Thing ."));
        assertTrue(out.contains("[VOCAB] ok"), out);
    }

    @Test
    void iriSchemesWithoutSlashesDoNotBreakTheSuggestions() throws IOException {
        String out = validator(false).validate(data("<urn:a> <urn:x:p> <mailto:a@b.example> ; <tag:x,2026:q> 1 ."));
        assertTrue(out.contains("Unknown property: urn:x:p"), out);
        assertTrue(out.contains("Unknown property: tag:x,2026:q"), out);
    }

    @Test
    void disjointnessFollowsEquivalentClassesAndNamesTheWay() throws IOException {
        String out = validatorFor(ROLES + """
                voc:Animal a owl:Class ; owl:disjointWith voc:Plant .
                voc:Beast a owl:Class ; owl:equivalentClass voc:Animal .
                voc:Plant a owl:Class .
                """).validate(data("ex:x a voc:Beast , voc:Plant ."));
        assertTrue(out.contains("[DISJOINTNESS] 1 violation(s)\n- ex:x is a voc:Animal (as a voc:Beast) and a voc:Plant, "
                + "which are disjoint classes"), out);
    }
}

