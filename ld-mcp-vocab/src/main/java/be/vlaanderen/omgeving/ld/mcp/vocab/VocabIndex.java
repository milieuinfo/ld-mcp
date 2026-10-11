package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.SKOS;

/**
 * Looks up classes, properties and named individuals (e.g. QUDT units) in a loaded set of
 * ontologies. Returns text intended for a language model: compact, with prefixed names where
 * possible.
 */
public final class VocabIndex {

    private static final Property DCT_DESCRIPTION =
            ResourceFactory.createProperty("http://purl.org/dc/terms/description");

    /** Types that make a resource a "term" (class or property). */
    private static final Set<Resource> TERM_TYPES = Set.of(
            RDFS.Class, OWL.Class, RDF.Property,
            OWL.ObjectProperty, OWL.DatatypeProperty, OWL.AnnotationProperty);

    private final Model model;
    /** All terms, sorted by IRI, computed once. */
    private final List<Resource> terms;

    /**
     * Builds the index of all classes, properties and named individuals in the model.
     *
     * @param model the loaded ontologies; its namespace prefixes are used to expand and shorten IRIs
     */
    public VocabIndex(Model model) {
        this.model = model;
        Set<Resource> found = new TreeSet<>(Comparator.comparing(Resource::getURI));
        for (Resource type : TERM_TYPES) {
            model.listSubjectsWithProperty(RDF.type, type)
                    .filterKeep(Resource::isURIResource)
                    .forEachRemaining(found::add);
        }
        // Named individuals, such as units (qudt:Unit) or concepts, but not ontology headers.
        model.listStatements(null, RDF.type, (RDFNode) null).forEachRemaining(st -> {
            if (st.getSubject().isURIResource() && !st.getObject().equals(OWL.Ontology)) {
                found.add(st.getSubject());
            }
        });
        // Also terms that are only declared through subClassOf/subPropertyOf/domain/range.
        for (Property p : List.of(RDFS.subClassOf, RDFS.subPropertyOf, RDFS.domain, RDFS.range)) {
            model.listSubjectsWithProperty(p)
                    .filterKeep(Resource::isURIResource)
                    .forEachRemaining(found::add);
        }
        this.terms = List.copyOf(found);
    }

    /**
     * Returns the size of the index.
     *
     * @return the number of indexed terms
     */
    public int size() {
        return terms.size();
    }

    /**
     * Describes one term: type, label, definition, superclasses and superproperties, domain,
     * range, inverse, equivalents, disjoint classes, SKOS mappings (for concepts such as units in a
     * code list) and subterms.
     *
     * @param term a prefixed name ({@code sosa:Observation}) or a full IRI
     * @return the description, or a "Not found" message followed by suggestions (the same local
     *         name in another namespace, or similar local names)
     * @throws IllegalArgumentException if the prefix is unknown or the term is neither a prefixed
     *                                  name nor an IRI
     */
    public String lookup(String term) {
        String iri = expand(term);
        Resource r = model.createResource(iri);
        if (!model.containsResource(r) || !model.listStatements(r, null, (RDFNode) null).hasNext()) {
            return notFound(term, iri);
        }

        StringBuilder sb = new StringBuilder();
        String shortForm = shortForm(iri);
        sb.append(shortForm.equals("<" + iri + ">") ? shortForm : shortForm + "  <" + iri + ">").append('\n');
        section(sb, "type", objects(r, RDF.type));
        section(sb, "label", literals(r, RDFS.label, SKOS.prefLabel));
        section(sb, "definition", literals(r, SKOS.definition, RDFS.comment, DCT_DESCRIPTION));
        section(sb, "subClassOf", objects(r, RDFS.subClassOf));
        section(sb, "subPropertyOf", objects(r, RDFS.subPropertyOf));
        section(sb, "domain", objects(r, RDFS.domain));
        section(sb, "range", objects(r, RDFS.range));
        section(sb, "inverseOf", objects(r, OWL.inverseOf));
        section(sb, "equivalent", objects(r, OWL.equivalentClass, OWL.equivalentProperty));
        section(sb, "disjointWith", objects(r, OWL.disjointWith));
        section(sb, "isDefinedBy", objects(r, RDFS.isDefinedBy));
        section(sb, "exactMatch", objects(r, SKOS.exactMatch));
        section(sb, "closeMatch", objects(r, SKOS.closeMatch));
        section(sb, "broadMatch", objects(r, SKOS.broadMatch));
        section(sb, "narrowMatch", objects(r, SKOS.narrowMatch));
        section(sb, "subclasses", subjects(RDFS.subClassOf, r));
        section(sb, "subproperties", subjects(RDFS.subPropertyOf, r));
        return sb.toString();
    }

    /**
     * Finds classes, properties and named individuals whose local name or label contains the query
     * (case-insensitive). Exact matches on the local name or a label come first, then names or
     * labels that start with the query, then the rest.
     *
     * @param query the text to search for
     * @param limit the maximum number of results to list
     * @return the number of hits and the first {@code limit} terms with their type and label
     */
    public String search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Resource> hits = terms.stream()
                .filter(t -> matches(t, q))
                .sorted(Comparator.comparingInt((Resource t) -> rank(t, q))
                        .thenComparing(Resource::getURI))
                .toList();
        if (hits.isEmpty()) {
            return "No terms found for '" + query + "'.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(" hit(s)");
        if (hits.size() > limit) {
            sb.append(", first ").append(limit).append(" shown");
        }
        sb.append(":\n");
        hits.stream().limit(limit).forEach(t -> sb.append("- ")
                .append(shortForm(t.getURI()))
                .append("  [").append(String.join(", ", objects(t, RDF.type))).append("]")
                .append(labelSuffix(t))
                .append('\n'));
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private String notFound(String term, String iri) {
        String local = localName(iri).toLowerCase(Locale.ROOT);
        List<String> sameLocal = terms.stream()
                .filter(t -> localName(t.getURI()).equalsIgnoreCase(local))
                .map(t -> shortForm(t.getURI()))
                .toList();
        StringBuilder sb = new StringBuilder("Not found: ").append(term);
        if (!iri.equals(term)) {
            sb.append("  <").append(iri).append(">");
        }
        sb.append('\n');
        if (!sameLocal.isEmpty()) {
            sb.append("Same local name in another namespace: ")
                    .append(String.join(", ", sameLocal)).append('\n');
        } else if (!local.isEmpty()) {
            List<String> similar = terms.stream()
                    .filter(t -> localName(t.getURI()).toLowerCase(Locale.ROOT).contains(local)
                            || local.contains(localName(t.getURI()).toLowerCase(Locale.ROOT))
                               && localName(t.getURI()).length() > 3)
                    .limit(10)
                    .map(t -> shortForm(t.getURI()))
                    .toList();
            if (!similar.isEmpty()) {
                sb.append("Similar terms: ").append(String.join(", ", similar)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Expands a prefixed name ({@code sosa:Observation}) to a full IRI; IRIs, with or without
     * angle brackets, are returned unchanged.
     */
    String expand(String term) {
        String t = term.strip();
        if (t.startsWith("<") && t.endsWith(">")) {
            return t.substring(1, t.length() - 1);
        }
        if (t.contains("://")) {
            return t;
        }
        int colon = t.indexOf(':');
        if (colon >= 0) {
            String ns = model.getNsPrefixURI(t.substring(0, colon));
            if (ns == null) {
                throw new IllegalArgumentException("Unknown prefix '" + t.substring(0, colon)
                        + "'. Known prefixes: " + String.join(", ", new TreeSet<>(model.getNsPrefixMap().keySet())));
            }
            return ns + t.substring(colon + 1);
        }
        throw new IllegalArgumentException("Give a prefixed name (sosa:Observation) or a full IRI.");
    }

    private boolean matches(Resource t, String q) {
        return localName(t.getURI()).toLowerCase(Locale.ROOT).contains(q)
                || literals(t, RDFS.label, SKOS.prefLabel).stream()
                        .anyMatch(l -> l.toLowerCase(Locale.ROOT).contains(q));
    }

    /** Exact local name or label first, then names or labels that start with the query, then the rest. */
    private int rank(Resource t, String q) {
        List<String> names = new ArrayList<>();
        names.add(localName(t.getURI()).toLowerCase(Locale.ROOT));
        for (Property p : List.of(RDFS.label, SKOS.prefLabel)) {
            model.listObjectsOfProperty(t, p).filterKeep(RDFNode::isLiteral).forEachRemaining(l ->
                    names.add(l.asLiteral().getLexicalForm().strip().toLowerCase(Locale.ROOT)));
        }
        if (names.stream().anyMatch(q::equals)) {
            return 0;
        }
        return names.stream().anyMatch(n -> n.startsWith(q)) ? 1 : 2;
    }

    private String labelSuffix(Resource t) {
        List<String> labels = literals(t, RDFS.label, SKOS.prefLabel);
        return labels.isEmpty() ? "" : "  — " + labels.get(0);
    }

    private List<String> objects(Resource r, Property... properties) {
        List<String> out = new ArrayList<>();
        for (Property p : properties) {
            model.listObjectsOfProperty(r, p).forEachRemaining(o -> out.add(render(o)));
        }
        return out.stream().distinct().sorted().collect(Collectors.toList());
    }

    private List<String> subjects(Property p, Resource o) {
        return model.listSubjectsWithProperty(p, o).toList().stream()
                .map(this::render).distinct().sorted().toList();
    }

    /** Literals, Dutch and English first. */
    private List<String> literals(Resource r, Property... properties) {
        List<Literal> out = new ArrayList<>();
        for (Property p : properties) {
            model.listObjectsOfProperty(r, p).filterKeep(RDFNode::isLiteral)
                    .forEachRemaining(o -> out.add(o.asLiteral()));
        }
        return out.stream()
                .sorted(Comparator.comparingInt(VocabIndex::languageRank))
                .map(l -> {
                    String text = l.getLexicalForm().strip().replaceAll("\\s+", " ");
                    return l.getLanguage().isEmpty() ? text : text + " @" + l.getLanguage();
                })
                .distinct()
                .toList();
    }

    private static int languageRank(Literal l) {
        return switch (l.getLanguage()) {
            case "nl" -> 0;
            case "en" -> 1;
            case "" -> 2;
            default -> 3;
        };
    }

    private String render(RDFNode node) {
        if (node.isURIResource()) {
            return shortForm(node.asResource().getURI());
        }
        if (node.isLiteral()) {
            return node.asLiteral().getLexicalForm();
        }
        return describeBlank(node.asResource());
    }

    /** Summarises OWL restrictions and lists instead of showing a blank node id. */
    private String describeBlank(Resource b) {
        if (b.hasProperty(OWL.onProperty)) {
            String p = render(b.getProperty(OWL.onProperty).getObject());
            for (Property kind : List.of(OWL.someValuesFrom, OWL.allValuesFrom, OWL.hasValue,
                    OWL.cardinality, OWL.minCardinality, OWL.maxCardinality,
                    OWL2_QUALIFIED_CARDINALITY, OWL2_MIN_QUALIFIED, OWL2_MAX_QUALIFIED)) {
                if (b.hasProperty(kind)) {
                    return "restriction " + p + " " + kind.getLocalName() + " "
                            + render(b.getProperty(kind).getObject());
                }
            }
            return "restriction on " + p;
        }
        for (Property set : List.of(OWL.unionOf, OWL.intersectionOf)) {
            if (b.hasProperty(set)) {
                List<String> members = b.getPropertyResourceValue(set).as(org.apache.jena.rdf.model.RDFList.class)
                        .asJavaList().stream().map(this::render).toList();
                return set.getLocalName() + "(" + String.join(", ", members) + ")";
            }
        }
        return "[blank node]";
    }

    private static final Property OWL2_QUALIFIED_CARDINALITY =
            ResourceFactory.createProperty(OWL.NS + "qualifiedCardinality");
    private static final Property OWL2_MIN_QUALIFIED =
            ResourceFactory.createProperty(OWL.NS + "minQualifiedCardinality");
    private static final Property OWL2_MAX_QUALIFIED =
            ResourceFactory.createProperty(OWL.NS + "maxQualifiedCardinality");

    private String shortForm(String iri) {
        String s = model.shortForm(iri);
        return s.equals(iri) ? "<" + iri + ">" : s;
    }

    private static String localName(String iri) {
        int i = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return i >= 0 ? iri.substring(i + 1) : iri;
    }

    private static void section(StringBuilder sb, String label, List<String> values) {
        if (!values.isEmpty()) {
            sb.append("  ").append(label).append(": ").append(String.join("; ", values)).append('\n');
        }
    }
}
