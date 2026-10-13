package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.apicatalog.jsonld.JsonLdError;
import com.apicatalog.jsonld.JsonLdErrorCode;
import com.apicatalog.jsonld.document.JsonDocument;
import com.apicatalog.jsonld.loader.DocumentLoader;

import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

import be.vlaanderen.omgeving.ld.mcp.core.JsonLd;

/**
 * Builds a fact sheet about a vocabulary, to help decide whether to reuse it: what LOV records
 * about it, whether its namespace and a sample term dereference to RDF, and what the RDF itself
 * shows (version, licence, labels and definitions, links to core vocabularies, axioms about other
 * vocabularies' terms, SKOS relations between classes, deprecated terms). It reports facts, not a
 * verdict: the assessment belongs to the modeller (e.g. rule R17 of oslo-ld-kit).
 */
public final class VocabularyProfiler {

    /** Namespaces treated as the core when no others are configured. */
    public static final List<String> DEFAULT_CORE = List.of(
            "http://www.w3.org/ns/sosa/",
            "http://www.w3.org/ns/ssn/",
            "http://www.w3.org/ns/prov#",
            "http://qudt.org/schema/qudt/",
            "http://www.w3.org/2006/time#",
            "http://www.opengis.net/ont/geosparql#",
            "http://www.w3.org/2004/02/skos/core#",
            "http://purl.org/dc/terms/");

    /** Accept headers tried in turn, with the RDF syntax each one asks for. */
    private static final Map<String, Lang> ACCEPTS = new LinkedHashMap<>();

    static {
        ACCEPTS.put("text/turtle", Lang.TURTLE);
        ACCEPTS.put("application/rdf+xml", Lang.RDFXML);
        ACCEPTS.put("application/ld+json", Lang.JSONLD);
    }

    private static final Property DCT_LICENSE = ResourceFactory.createProperty("http://purl.org/dc/terms/license");
    private static final Property DC11_RIGHTS = ResourceFactory.createProperty("http://purl.org/dc/elements/1.1/rights");
    private static final Property CC_LICENSE = ResourceFactory.createProperty("http://creativecommons.org/ns#license");
    private static final Property SCHEMA_LICENSE = ResourceFactory.createProperty("http://schema.org/license");
    private static final Property DCT_DESCRIPTION = ResourceFactory.createProperty("http://purl.org/dc/terms/description");
    private static final Set<Property> AXIOMS = Set.of(RDFS.subClassOf, RDFS.subPropertyOf, RDFS.domain,
            RDFS.range, OWL.equivalentClass, OWL.equivalentProperty, OWL.disjointWith, OWL.inverseOf);
    private static final Set<Property> SKOS_RELATIONS = Set.of(SKOS.broader, SKOS.narrower, SKOS.related,
            SKOS.exactMatch, SKOS.closeMatch, SKOS.broadMatch, SKOS.narrowMatch, SKOS.relatedMatch);
    private static final int EXAMPLES = 5;

    /** Default time budget of one profile. */
    public static final Duration DEFAULT_BUDGET = Duration.ofSeconds(30);

    private final LovClient lov;
    private final Fetcher fetcher;
    private final List<String> core;
    private final Duration budget;

    /**
     * Creates a profiler.
     *
     * @param lov     client for LOV, or {@code null} to skip the LOV record
     * @param fetcher how to dereference the vocabulary
     * @param core    namespaces counted as the core, e.g. {@link #DEFAULT_CORE}
     */
    public VocabularyProfiler(LovClient lov, Fetcher fetcher, List<String> core) {
        this(lov, fetcher, core, DEFAULT_BUDGET);
    }

    /**
     * Creates a profiler with a time budget per profile.
     *
     * @param lov     client for LOV, or {@code null} to skip the LOV record
     * @param fetcher how to dereference the vocabulary
     * @param core    namespaces counted as the core, e.g. {@link #DEFAULT_CORE}
     * @param budget  the time one profile may take; checks that do not fit are reported as not
     *                checked
     */
    public VocabularyProfiler(LovClient lov, Fetcher fetcher, List<String> core, Duration budget) {
        this.lov = lov;
        this.fetcher = fetcher;
        this.core = List.copyOf(core);
        this.budget = budget;
    }

    /**
     * Builds the fact sheet of a vocabulary.
     *
     * @param vocab      a LOV prefix ({@code saref}) or a namespace IRI
     * @param sampleTerm a term IRI to dereference as well; {@code null} to take a class or property
     *                   from the vocabulary itself
     * @return the fact sheet as text
     * @throws IllegalArgumentException if the vocabulary is neither an IRI nor known to LOV (a prefix
     *                                  cannot be resolved while LOV is unavailable)
     */
    public String profile(String vocab, String sampleTerm) {
        return profile(vocab, sampleTerm, null, null);
    }

    /**
     * Builds the fact sheet of a vocabulary, with a section on alignments: the modules in the
     * register that connect its namespace (and whether they are loaded), and the axioms between
     * the vocabulary and the core that the loaded ontologies contain.
     *
     * @param vocab      a LOV prefix ({@code saref}) or a namespace IRI
     * @param sampleTerm a term IRI to dereference as well; {@code null} to take a class or property
     *                   from the vocabulary itself
     * @param register   known alignment modules, or {@code null} if none is configured
     * @param loaded     the loaded ontologies, or {@code null} to skip that part
     * @return the fact sheet as text
     * @throws IllegalArgumentException if the vocabulary is neither an IRI nor known to LOV (a prefix
     *                                  cannot be resolved while LOV is unavailable)
     */
    public String profile(String vocab, String sampleTerm, AlignmentRegister register, Model loaded) {
        Deadline deadline = Deadline.after(budget);
        StringBuilder sb = new StringBuilder();
        sb.append("Fact sheet for '").append(vocab).append("'. Facts only; assess them yourself "
                + "(e.g. against R17 of oslo-ld-kit). Live checks outweigh LOV, whose records can lag behind.\n\n");

        // LOV is only one source: when it cannot be reached, the live checks still run for an IRI.
        Optional<JsonObject> info = Optional.empty();
        String lovError = null;
        if (lov != null) {
            try {
                // At most half the budget: the live checks below are authoritative, LOV is not.
                info = lov.vocabularyInfo(vocab, Deadline.after(deadline.cap(budget.dividedBy(2))));
            } catch (IllegalArgumentException e) {
                lovError = e.getMessage();
            }
        }
        sb.append("## LOV record\n");
        if (lovError != null) {
            sb.append("unavailable: ").append(lovError).append('\n');
        } else {
            sb.append(info.map(i -> Untrusted.block(lovHost(), LovClient.summarise(i))).orElse("not in LOV\n"));
        }

        String namespace = info.map(i -> i.hasKey("nsp") ? i.get("nsp").getAsString().value() : null)
                .orElse(isIri(vocab) ? vocab : null);
        if (namespace == null) {
            throw new IllegalArgumentException(lovError != null
                    ? "'" + vocab + "' is not an IRI, and LOV, which could resolve it as a prefix, is unavailable: "
                            + lovError + ". Give the namespace IRI instead."
                    : "'" + vocab + "' is not an IRI and LOV does not know it as a prefix");
        }

        sb.append("\n## Alignments\n");
        alignments(sb, namespace, register, loaded);

        sb.append("\n## Dereferencing the namespace <").append(namespace).append(">\n");
        Model model = null;
        for (Map.Entry<String, Lang> e : ACCEPTS.entrySet()) {
            Attempt a = attempt(namespace, e.getKey(), deadline);
            sb.append("- Accept ").append(e.getKey()).append(": ").append(a.report()).append('\n');
            if (model == null && a.model() != null) {
                model = a.model();
            }
        }
        if (model == null) {
            sb.append("No RDF returned for any Accept header.\n");
            return sb.toString();
        }

        String sample = sampleTerm != null ? sampleTerm : pickSample(model, namespace);
        if (sample != null) {
            sb.append("\n## Dereferencing a term <").append(sample).append(">\n");
            for (String accept : ACCEPTS.keySet()) {
                sb.append("- Accept ").append(accept).append(": ").append(attempt(sample, accept, deadline).report()).append('\n');
            }
        }

        sb.append("\n## Content of the vocabulary (").append(model.size()).append(" triples)\n");
        StringBuilder content = new StringBuilder();
        describe(content, model, namespace);
        sb.append(Untrusted.block(Untrusted.host(namespace), content.toString()));
        return sb.toString();
    }

    private String lovHost() {
        return lov == null ? "LOV" : "LOV (" + Untrusted.host(lov.baseUrl()) + ")";
    }

    // ---------------------------------------------------------------- alignments

    private void alignments(StringBuilder sb, String namespace, AlignmentRegister register, Model loaded) {
        if (register == null) {
            sb.append("register: none configured (--alignments)\n");
        } else {
            List<AlignmentRegister.Entry> found = register.matching(namespace);
            sb.append("register: ").append(found.size()).append(" of ").append(register.size())
                    .append(" known module(s) connect this namespace\n");
            for (AlignmentRegister.Entry e : found) {
                List<String> others = e.namespaces().stream()
                        .filter(n -> !AlignmentRegister.sameNamespace(n, namespace)).toList();
                sb.append("- <").append(Untrusted.literal(e.iri())).append(">")
                        .append(e.title().isEmpty() ? "" : " " + Untrusted.literal(e.title()))
                        .append("\n  connects with: ").append(String.join(", ", others))
                        .append("\n  loaded: ").append(loaded == null ? "unknown" : isLoaded(loaded, e) ? "yes" : "no");
                if (!e.comment().isEmpty()) {
                    sb.append("\n  status: ").append(Untrusted.literal(e.comment()));
                }
                if (!e.source().isEmpty()) {
                    sb.append("\n  source: ").append(Untrusted.literal(e.source()));
                }
                if (!e.seeAlso().isEmpty()) {
                    sb.append("\n  documentation: ").append(Untrusted.literal(e.seeAlso()));
                }
                sb.append('\n');
            }
        }
        if (loaded == null) {
            return;
        }
        int count = 0;
        List<String> examples = new ArrayList<>();
        for (Statement st : loaded.listStatements().toList()) {
            if (!AXIOMS.contains(st.getPredicate()) || !st.getSubject().isURIResource() || !st.getObject().isURIResource()) {
                continue;
            }
            String s = st.getSubject().getURI();
            String o = st.getObject().asResource().getURI();
            boolean outward = s.startsWith(namespace) && coreOf(st.getObject()).isPresent() && !o.startsWith(namespace);
            boolean inward = o.startsWith(namespace) && coreOf(st.getSubject()).isPresent() && !s.startsWith(namespace);
            if (outward || inward) {
                count++;
                if (examples.size() < EXAMPLES) {
                    examples.add(show(loaded, st.getSubject()) + " " + show(loaded, st.getPredicate()) + " " + show(loaded, st.getObject()));
                }
            }
        }
        sb.append("loaded ontologies: ").append(count)
                .append(" axiom(s) between this vocabulary and the core (in either direction, from any loaded file)");
        if (!examples.isEmpty()) {
            sb.append(", e.g.\n");
            examples.forEach(x -> sb.append("  - ").append(x).append('\n'));
        } else {
            sb.append('\n');
        }
    }

    private static boolean isLoaded(Model loaded, AlignmentRegister.Entry e) {
        Resource module = loaded.createResource(e.iri());
        if (loaded.contains(module, RDF.type, OWL.Ontology)) {
            return true;
        }
        return !e.versionIri().isEmpty()
                && loaded.contains(null, OWL.versionIRI, loaded.createResource(e.versionIri()));
    }

    // ---------------------------------------------------------------- dereferencing

    /** The outcome of one request: a line for the report and the parsed model, if any. */
    private record Attempt(String report, Model model) {
    }

    private Attempt attempt(String iri, String accept, Deadline deadline) {
        if (deadline.expired()) {
            return new Attempt("not checked: time budget exhausted", null);
        }
        Fetcher.Response r;
        try {
            r = fetcher.get(URI.create(iri), accept, deadline);
        } catch (IOException | IllegalArgumentException e) {
            return new Attempt("request failed (" + Untrusted.literal(e.getMessage()) + ")", null);
        }
        String head = r.status() + ", " + (r.contentType().isEmpty() ? "no content type" : Untrusted.literal(r.contentType()));
        if (!r.ok()) {
            return new Attempt(head, null);
        }
        Lang lang = RDFLanguages.contentTypeToLang(mediaType(r.contentType()));
        if (lang == null) {
            return new Attempt(head + ", not RDF", null);
        }
        Dataset ds = DatasetFactory.create();
        try {
            RDFParser.fromString(r.body(), lang).base(iri).context(JsonLd.context(contexts(deadline))).parse(ds);
        } catch (RiotException e) {
            return new Attempt(head + ", does not parse as " + lang.getLabel() + " (" + Untrusted.literal(e.getMessage()) + ")", null);
        }
        // Some publishers wrap the vocabulary in a named graph (JSON-LD @graph with an @id).
        Model m = ModelFactory.createDefaultModel().add(ds.getDefaultModel()).add(ds.getUnionModel());
        m.setNsPrefixes(ds.getDefaultModel().getNsPrefixMap());
        if (m.isEmpty()) {
            return new Attempt(head + ", parses but contains no triples (e.g. only a JSON-LD @context)", null);
        }
        return new Attempt(head + ", " + m.size() + " triples", m);
    }

    /**
     * Loads a remote JSON-LD context through the same fetcher (and so the same network policy and
     * deadline) as the vocabulary itself; with {@code --offline} the fetcher refuses.
     */
    private DocumentLoader contexts(Deadline deadline) {
        return (url, options) -> {
            try {
                Fetcher.Response r = fetcher.get(url, "application/ld+json, application/json", deadline);
                if (!r.ok()) {
                    throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                            "remote @context " + url + " answered " + r.status());
                }
                return JsonDocument.of(new StringReader(r.body()));
            } catch (IOException e) {
                throw new JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED,
                        "remote @context " + url + ": " + e.getMessage());
            }
        };
    }

    private static String mediaType(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase(Locale.ROOT);
    }

    private static String pickSample(Model m, String namespace) {
        for (Resource type : List.of(OWL.Class, RDFS.Class, OWL.ObjectProperty, OWL.DatatypeProperty, RDF.Property)) {
            Optional<Resource> r = m.listSubjectsWithProperty(RDF.type, type).toList().stream()
                    .filter(s -> s.isURIResource() && s.getURI().startsWith(namespace)
                            && s.getURI().length() > namespace.length())
                    .min((a, b) -> a.getURI().compareTo(b.getURI()));
            if (r.isPresent()) {
                return r.get().getURI();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- content

    private void describe(StringBuilder sb, Model m, String namespace) {
        String docIri = namespace.endsWith("#") || namespace.endsWith("/")
                ? namespace.substring(0, namespace.length() - 1) : namespace;
        List<Resource> headers = m.listSubjectsWithProperty(RDF.type, OWL.Ontology).toList();
        Resource header = headers.stream().filter(h -> h.isURIResource()
                        && (h.getURI().equals(namespace) || h.getURI().equals(docIri)))
                .findFirst().orElse(headers.isEmpty() ? null : headers.get(0));
        sb.append("ontology header: ").append(header == null ? "none" : "<" + header.getURI() + ">").append('\n');
        if (header != null) {
            sb.append("versionIRI: ").append(values(m, header, OWL.versionIRI)).append('\n');
            sb.append("versionInfo: ").append(values(m, header, OWL.versionInfo)).append('\n');
            sb.append("licence: ").append(values(m, header, DCT_LICENSE, CC_LICENSE, SCHEMA_LICENSE, DC11_RIGHTS)).append('\n');
        }

        List<Resource> classes = own(m, namespace, OWL.Class, RDFS.Class);
        List<Resource> properties = own(m, namespace, RDF.Property, OWL.ObjectProperty, OWL.DatatypeProperty,
                OWL.AnnotationProperty);
        sb.append("classes: ").append(classes.size()).append(", ")
                .append(missing(m, classes, RDFS.label, SKOS.prefLabel)).append(" without label, ")
                .append(missing(m, classes, RDFS.comment, SKOS.definition, DCT_DESCRIPTION)).append(" without definition\n");
        // Annotation properties have no domain or range by design: not counted there.
        List<Resource> withAxioms = properties.stream()
                .filter(p -> !m.contains(p, RDF.type, OWL.AnnotationProperty)).toList();
        sb.append("properties: ").append(properties.size()).append(", ")
                .append(missing(m, properties, RDFS.label, SKOS.prefLabel)).append(" without label, ")
                .append(missing(m, properties, RDFS.comment, SKOS.definition, DCT_DESCRIPTION)).append(" without definition, ")
                .append(missing(m, withAxioms, RDFS.domain)).append(" without domain, ")
                .append(missing(m, withAxioms, RDFS.range)).append(" without range")
                .append(withAxioms.size() < properties.size()
                        ? " (of " + withAxioms.size() + " that are no owl:AnnotationProperty)" : "")
                .append('\n');

        // owl:deprecated false is not a deprecation; owl:DeprecatedClass/Property is one.
        Set<Resource> deprecatedTerms = new java.util.HashSet<>();
        m.listStatements(null, OWL.deprecated, (RDFNode) null).forEachRemaining(st -> {
            if (VocabIndex.isTrue(st.getObject())) {
                deprecatedTerms.add(st.getSubject());
            }
        });
        deprecatedTerms.addAll(m.listSubjectsWithProperty(RDF.type, OWL.DeprecatedClass).toList());
        deprecatedTerms.addAll(m.listSubjectsWithProperty(RDF.type, OWL.DeprecatedProperty).toList());
        long deprecated = deprecatedTerms.stream()
                .filter(s -> s.isURIResource() && s.getURI().startsWith(namespace)).count();
        sb.append("deprecated terms (owl:deprecated true, owl:DeprecatedClass/Property): ").append(deprecated).append('\n');

        // Links from own terms to the core.
        Map<String, Integer> links = new TreeMap<>();
        List<String> foreign = new ArrayList<>();
        int foreignCount = 0;
        for (Statement st : m.listStatements().toList()) {
            if (!AXIOMS.contains(st.getPredicate()) || !st.getSubject().isURIResource()) {
                continue;
            }
            String s = st.getSubject().getURI();
            if (s.startsWith(namespace)) {
                // Links inside the vocabulary are no links to the core, also when it is a core vocabulary.
                if (!(st.getObject().isURIResource() && st.getObject().asResource().getURI().startsWith(namespace))) {
                    coreOf(st.getObject()).ifPresent(ns -> links.merge(ns, 1, Integer::sum));
                }
            } else {
                foreignCount++;
                if (foreign.size() < EXAMPLES) {
                    foreign.add(show(m, st.getSubject()) + " " + show(m, st.getPredicate()) + " " + show(m, st.getObject()));
                }
            }
        }
        sb.append("links to the core (subClassOf, subPropertyOf, domain, range, equivalent, disjoint, inverse): ")
                .append(links.isEmpty() ? "none" : links.toString()).append('\n')
                .append("  (only links inside this vocabulary; for alignments published elsewhere, see ## Alignments)\n");
        sb.append("axioms about terms outside its own namespace: ").append(foreignCount);
        if (!foreign.isEmpty()) {
            sb.append(", e.g.\n");
            foreign.forEach(f -> sb.append("  - ").append(f).append('\n'));
        } else {
            sb.append('\n');
        }

        List<String> skos = new ArrayList<>();
        int skosCount = 0;
        for (Statement st : m.listStatements().toList()) {
            if (SKOS_RELATIONS.contains(st.getPredicate()) && isClass(m, st.getSubject())
                    && st.getObject().isResource() && isClass(m, st.getObject().asResource())) {
                skosCount++;
                if (skos.size() < EXAMPLES) {
                    skos.add(show(m, st.getSubject()) + " " + show(m, st.getPredicate()) + " " + show(m, st.getObject()));
                }
            }
        }
        sb.append("SKOS relations between classes: ").append(skosCount);
        if (!skos.isEmpty()) {
            sb.append(", e.g.\n");
            skos.forEach(s -> sb.append("  - ").append(s).append('\n'));
        } else {
            sb.append('\n');
        }
    }

    private Optional<String> coreOf(RDFNode node) {
        if (!node.isURIResource()) {
            return Optional.empty();
        }
        String iri = node.asResource().getURI();
        return core.stream().filter(iri::startsWith).findFirst();
    }

    private static boolean isClass(Model m, Resource r) {
        return m.contains(r, RDF.type, OWL.Class) || m.contains(r, RDF.type, RDFS.Class);
    }

    private static List<Resource> own(Model m, String namespace, Resource... types) {
        Set<Resource> out = new java.util.TreeSet<>((a, b) -> a.getURI().compareTo(b.getURI()));
        for (Resource t : types) {
            m.listSubjectsWithProperty(RDF.type, t).toList().stream()
                    .filter(s -> s.isURIResource() && s.getURI().startsWith(namespace))
                    .forEach(out::add);
        }
        return List.copyOf(out);
    }

    private static long missing(Model m, List<Resource> terms, Property... props) {
        return terms.stream().filter(t -> {
            for (Property p : props) {
                if (m.contains(t, p)) {
                    return false;
                }
            }
            return true;
        }).count();
    }

    private static String values(Model m, Resource r, Property... props) {
        List<String> out = new ArrayList<>();
        for (Property p : props) {
            m.listObjectsOfProperty(r, p).forEachRemaining(o -> out.add(show(m, o)));
        }
        return out.isEmpty() ? "-" : String.join(", ", out);
    }

    private static String show(Model m, RDFNode n) {
        if (n.isURIResource()) {
            String iri = n.asResource().getURI();
            String s = m.shortForm(iri);
            return Untrusted.literal(s.equals(iri) || s.startsWith(":") ? "<" + iri + ">" : s);
        }
        if (n.isLiteral()) {
            return "\"" + Untrusted.literal(n.asLiteral().getLexicalForm()) + "\"";
        }
        return "[]";
    }

    private static boolean isIri(String s) {
        return s.startsWith("http://") || s.startsWith("https://");
    }
}
