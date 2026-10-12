package be.vlaanderen.omgeving.ld.mcp.vocab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

import org.junit.jupiter.api.Test;

class VocabularyProfilerTest {

    private static final String NS = "https://example.org/voc#";

    /** A small vocabulary with one of each thing the profiler looks for. */
    private static final String VOC = """
            @prefix voc:  <https://example.org/voc#> .
            @prefix sosa: <http://www.w3.org/ns/sosa/> .
            @prefix prov: <http://www.w3.org/ns/prov#> .
            @prefix owl:  <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix skos: <http://www.w3.org/2004/02/skos/core#> .
            @prefix dct:  <http://purl.org/dc/terms/> .

            <https://example.org/voc> a owl:Ontology ;
                owl:versionIRI <https://example.org/voc/1.2/> ;
                owl:versionInfo "1.2" ;
                dct:license <https://creativecommons.org/licenses/by/4.0/> .

            voc:Meter a owl:Class ; rdfs:label "meter"@en ; rdfs:comment "A device."@en ;
                rdfs:subClassOf sosa:Sensor .
            voc:Gauge a owl:Class ; skos:broader voc:Meter .
            voc:Old a owl:Class ; owl:deprecated true ; rdfs:label "old"@en .
            voc:maker a owl:DatatypeProperty ; rdfs:label "maker"@en ; rdfs:range voc:Meter .

            sosa:Sensor rdfs:subClassOf prov:Activity .
            """;

    private static Fetcher fetcher(Map<String, Fetcher.Response> byAccept) {
        return (uri, accept) -> byAccept.getOrDefault(accept, new Fetcher.Response(406, "text/html", "<html/>"));
    }

    private static final Map<String, Fetcher.Response> TURTLE_ONLY = Map.of(
            "text/turtle", new Fetcher.Response(200, "text/turtle; charset=utf-8", VOC));

    @Test
    void reportsDereferencingPerAcceptHeader() {
        String out = new VocabularyProfiler(null, fetcher(TURTLE_ONLY), VocabularyProfiler.DEFAULT_CORE)
                .profile(NS, null);
        assertTrue(out.contains("not in LOV"), out);
        assertTrue(out.contains("- Accept text/turtle: 200, text/turtle; charset=utf-8, "), out);
        assertTrue(out.contains("- Accept application/rdf+xml: 406, text/html"), out);
        assertTrue(out.contains("## Dereferencing a term <https://example.org/voc#Gauge>"), out);
    }

    @Test
    void describesVersionLicenceAndQuality() {
        String out = new VocabularyProfiler(null, fetcher(TURTLE_ONLY), VocabularyProfiler.DEFAULT_CORE)
                .profile(NS, NS + "Meter");
        assertTrue(out.contains("versionIRI: <https://example.org/voc/1.2/>"), out);
        assertTrue(out.contains("versionInfo: \"1.2\""), out);
        assertTrue(out.contains("licence: <https://creativecommons.org/licenses/by/4.0/>"), out);
        assertTrue(out.contains("classes: 3, 1 without label, 2 without definition"), out);
        assertTrue(out.contains("properties: 1, 0 without label, 1 without definition, 1 without domain, 0 without range"), out);
        assertTrue(out.contains("deprecated terms (owl:deprecated): 1"), out);
        assertTrue(out.contains("links to the core (subClassOf, subPropertyOf, domain, range, equivalent, disjoint, inverse): "
                + "{http://www.w3.org/ns/sosa/=1}"), out);
        assertTrue(out.contains("axioms about terms outside its own namespace: 1, e.g.\n  - sosa:Sensor rdfs:subClassOf prov:Activity"), out);
        assertTrue(out.contains("SKOS relations between classes: 1, e.g.\n  - voc:Gauge skos:broader voc:Meter"), out);
    }

    @Test
    void htmlOnlyMeansNoRdf() {
        String out = new VocabularyProfiler(null, fetcher(Map.of()), VocabularyProfiler.DEFAULT_CORE)
                .profile(NS, null);
        assertTrue(out.contains("No RDF returned for any Accept header."), out);
        assertFalse(out.contains("## Content"), out);
    }

    @Test
    void jsonLdContextWithoutTriplesIsNotRdf() {
        Map<String, Fetcher.Response> contextOnly = Map.of("application/ld+json",
                new Fetcher.Response(200, "application/ld+json", "{\"@context\": {\"name\": \"https://example.org/voc#name\"}}"));
        String out = new VocabularyProfiler(null, fetcher(contextOnly), VocabularyProfiler.DEFAULT_CORE)
                .profile(NS, null);
        assertTrue(out.contains("parses but contains no triples"), out);
        assertTrue(out.contains("No RDF returned for any Accept header."), out);
    }

    /** A register with one module that connects SOSA and the example vocabulary, one that does not. */
    private static final String REGISTER = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            <https://example.org/sosa-voc> a owl:Ontology ;
                dcterms:title "SOSA alignment with voc"@en ;
                owl:versionIRI <https://example.org/sosa-voc/1> ;
                dcterms:references <http://www.w3.org/ns/sosa/> , <https://example.org/voc#> ;
                dcterms:source <https://example.org/sosa-voc.ttl> ;
                rdfs:comment "Working draft."@en .
            <https://example.org/sosa-other> a owl:Ontology ;
                dcterms:references <http://www.w3.org/ns/sosa/> , <https://example.org/other#> .
            """;

    private static Model ttl(String text) {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(text), null, "TTL");
        return m;
    }

    @Test
    void alignmentsFromRegisterAndLoadedOntologies() {
        AlignmentRegister register = AlignmentRegister.fromModel(ttl(REGISTER));
        Model loaded = ttl("""
                @prefix voc: <https://example.org/voc#> .
                @prefix sosa: <http://www.w3.org/ns/sosa/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                voc:Thing rdfs:subClassOf sosa:FeatureOfInterest .
                sosa:Asset rdfs:subClassOf voc:Thing .
                voc:Thing rdfs:subClassOf voc:Other .
                """);
        String out = new VocabularyProfiler(null, fetcher(Map.of()), VocabularyProfiler.DEFAULT_CORE)
                .profile(NS, null, register, loaded);
        assertTrue(out.contains("register: 1 of 2 known module(s) connect this namespace"), out);
        assertTrue(out.contains("- <https://example.org/sosa-voc> SOSA alignment with voc\n"
                + "  connects with: http://www.w3.org/ns/sosa/\n  loaded: no\n  status: Working draft."), out);
        assertTrue(out.contains("loaded ontologies: 2 axiom(s) between this vocabulary and the core"), out);
        assertTrue(out.contains("No RDF returned"), out);
    }

    @Test
    void registeredModuleCountsAsLoadedByVersionIri() {
        AlignmentRegister register = AlignmentRegister.fromModel(ttl(REGISTER));
        Model loaded = ttl("""
                <https://example.org/some-header> <http://www.w3.org/2002/07/owl#versionIRI> <https://example.org/sosa-voc/1> .
                """);
        String out = new VocabularyProfiler(null, fetcher(Map.of()), VocabularyProfiler.DEFAULT_CORE)
                .profile(NS, null, register, loaded);
        assertTrue(out.contains("  loaded: yes"), out);
    }

    @Test
    void withoutRegisterTheSectionSaysSo() {
        String out = new VocabularyProfiler(null, fetcher(Map.of()), VocabularyProfiler.DEFAULT_CORE)
                .profile(NS, null);
        assertTrue(out.contains("register: none configured (--alignments)"), out);
    }

    @Test
    void prefixUnknownToLovIsAnError() {
        assertThrows(IllegalArgumentException.class,
                () -> new VocabularyProfiler(null, fetcher(TURTLE_ONLY), VocabularyProfiler.DEFAULT_CORE)
                        .profile("voc", null));
    }
}
