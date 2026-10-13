package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.shared.PrefixMapping;
import org.junit.jupiter.api.Test;

/** Small display and counting details (plan 3.12). */
class DisplayTest {

    private static final PrefixMapping PREFIXES = PrefixMapping.Factory.create()
            .setNsPrefix("ex", "https://example.org/")
            .setNsPrefix("voc", "https://example.org/voc/")
            .setNsPrefix("onto", "https://example.org/onto#");

    @Test
    void shortensOnlyAtANamespaceBoundaryAndWithTheLongestNamespace() {
        assertEquals("voc:part", DataValidator.shorten(PREFIXES, "<https://example.org/voc/part>"));
        assertEquals("onto:part", DataValidator.shorten(PREFIXES, "<https://example.org/onto#part>"));
        assertEquals("<https://example.org/a/b/c>", DataValidator.shorten(PREFIXES, "<https://example.org/a/b/c>"));
        assertEquals("ex:x is not voc:y", DataValidator.shorten(PREFIXES,
                "https://example.org/x is not https://example.org/voc/y"));
        assertEquals("see https://example.org/a/b here", DataValidator.shorten(PREFIXES, "see https://example.org/a/b here"));
    }

    @Test
    void notFoundShowsTheIriOnce() {
        VocabIndex index = new VocabIndex(ModelFactory.createDefaultModel());
        String out = index.lookup("<https://example.org/nope>");
        assertEquals("Not found: <https://example.org/nope>", out.lines().findFirst().orElseThrow());
    }

    @Test
    void regionalVariantsOfDutchAndEnglishComeFirst() {
        assertEquals(0, VocabIndex.languageRank(ResourceFactory.createLangLiteral("x", "nl-BE")));
        assertEquals(1, VocabIndex.languageRank(ResourceFactory.createLangLiteral("x", "en-GB")));
        assertEquals(2, VocabIndex.languageRank(ResourceFactory.createPlainLiteral("x")));
        assertEquals(3, VocabIndex.languageRank(ResourceFactory.createLangLiteral("x", "fr")));
        assertEquals(3, VocabIndex.languageRank(ResourceFactory.createLangLiteral("x", "nld")));
    }

    @Test
    void alignmentsMatchOnTheNamespaceNotOnAPrefix() {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader("""
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix dcterms: <http://purl.org/dc/terms/> .
                <urn:wide> a owl:Ontology ; dcterms:references <http://www.w3.org/ns/> , <urn:x#> .
                <urn:exact> a owl:Ontology ; dcterms:references <http://www.w3.org/ns/sosa/> , <urn:y#> .
                """), null, "TTL");
        AlignmentRegister register = AlignmentRegister.fromModel(m);
        assertEquals(List.of("urn:exact"), register.matching("http://www.w3.org/ns/sosa/").stream()
                .map(AlignmentRegister.Entry::iri).toList());
        assertEquals(1, register.matching("http://www.w3.org/ns/sosa").size());
        assertTrue(AlignmentRegister.sameNamespace("https://saref.etsi.org/core/", "https://saref.etsi.org/core"));
        assertFalse(AlignmentRegister.sameNamespace("https://saref.etsi.org/", "https://saref.etsi.org/core/"));
    }

    @Test
    void profileCountsAnnotationPropertiesApartAndNoInternalLinksToTheCore() {
        String voc = """
                @prefix owl:  <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix sosa: <http://www.w3.org/ns/sosa/> .
                sosa:A a owl:Class ; rdfs:subClassOf sosa:B .
                sosa:B a owl:Class .
                sosa:note a owl:AnnotationProperty .
                sosa:p a owl:ObjectProperty ; rdfs:domain sosa:A ; rdfs:range sosa:B .
                """;
        Fetcher f = (uri, accept) -> accept.equals("text/turtle")
                ? new Fetcher.Response(200, "text/turtle", voc) : new Fetcher.Response(406, "text/html", "");
        String out = new VocabularyProfiler(null, f, VocabularyProfiler.DEFAULT_CORE)
                .profile("http://www.w3.org/ns/sosa/", null);
        assertTrue(out.contains("properties: 2, 2 without label, 2 without definition, 0 without domain, "
                + "0 without range (of 1 that are no owl:AnnotationProperty)"), out);
        assertTrue(out.contains("links to the core (subClassOf, subPropertyOf, domain, range, equivalent, disjoint, inverse): none"), out);
    }
}
