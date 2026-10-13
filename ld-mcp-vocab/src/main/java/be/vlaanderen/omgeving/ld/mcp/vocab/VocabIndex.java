package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.ArrayList;
import java.util.Collections;
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

    private static final Property DCT_IS_REPLACED_BY =
            ResourceFactory.createProperty("http://purl.org/dc/terms/isReplacedBy");
    private static final Property DCT_DESCRIPTION =
            ResourceFactory.createProperty("http://purl.org/dc/terms/description");

    /** Types that make a resource a "term" (class or property). */
    private static final Set<Resource> TERM_TYPES = Set.of(
            RDFS.Class, OWL.Class, RDF.Property,
            OWL.ObjectProperty, OWL.DatatypeProperty, OWL.AnnotationProperty);

    /** Properties whose subject is thereby declared as a class or property. */
    private static final List<Property> DECLARING = List.of(RDFS.subClassOf, RDFS.subPropertyOf, RDFS.domain,
            RDFS.range, OWL.equivalentClass, OWL.equivalentProperty, OWL.disjointWith,
            org.apache.jena.vocabulary.OWL2.disjointUnionOf, OWL.inverseOf);

    private static final String SH = "http://www.w3.org/ns/shacl#";

    /** Types that make a subject something other than a term. */
    private static final Set<RDFNode> NOT_TERMS = Set.of(OWL.Ontology,
            ResourceFactory.createResource(SH + "NodeShape"), ResourceFactory.createResource(SH + "PropertyShape"),
            ResourceFactory.createResource(SH + "Shape"));

    private static final Set<Resource> CLASS_TYPES = Set.of(RDFS.Class, OWL.Class, RDFS.Datatype);
    private static final Set<Resource> PROPERTY_TYPES = Set.of(RDF.Property, OWL.ObjectProperty,
            OWL.DatatypeProperty, OWL.AnnotationProperty, OWL.FunctionalProperty, OWL.InverseFunctionalProperty,
            OWL.TransitiveProperty, OWL.SymmetricProperty);

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
        // Named individuals, such as units (qudt:Unit) or concepts, but not ontology headers or
        // SHACL shapes (a shapes file next to the ontologies, or generated shapes).
        model.listStatements(null, RDF.type, (RDFNode) null).forEachRemaining(st -> {
            if (st.getSubject().isURIResource() && !NOT_TERMS.contains(st.getObject())) {
                found.add(st.getSubject());
            }
        });
        // Also terms that are only declared through an axiom about them.
        for (Property p : DECLARING) {
            model.listSubjectsWithProperty(p)
                    .filterKeep(Resource::isURIResource)
                    .forEachRemaining(found::add);
        }
        // A subject typed only as a header or a shape is no term, also if it was found above.
        found.removeIf(r -> {
            List<RDFNode> types = model.listObjectsOfProperty(r, RDF.type).toList();
            return !types.isEmpty() && NOT_TERMS.containsAll(types);
        });
        this.terms = List.copyOf(found);
    }

    /**
     * Returns the namespaces of the indexed terms: everything up to and including the last
     * {@code #} or {@code /}.
     *
     * @return the namespaces in which the ontologies declare terms
     */
    public Set<String> namespaces() {
        Set<String> out = new java.util.HashSet<>();
        terms.forEach(t -> out.add(namespaceOf(t.getURI())));
        return out;
    }

    private static String namespaceOf(String iri) {
        return iri.substring(0, Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/')) + 1);
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
     * Returns the loaded ontologies this index was built from. Callers must not modify it.
     *
     * @return the model of the loaded ontologies
     */
    public Model model() {
        return model;
    }

    /**
     * Tells whether a term is in the index.
     *
     * @param iri the full IRI of the term
     * @return whether the loaded ontologies declare the term (give it a type, or make it the
     *         subject of {@code subClassOf}, {@code subPropertyOf}, {@code domain}, {@code range}
     *         or an OWL class or property axiom)
     */
    public boolean contains(String iri) {
        int i = Collections.binarySearch(terms, ResourceFactory.createResource(iri),
                Comparator.comparing(Resource::getURI));
        return i >= 0;
    }

    /**
     * Tells whether a term is known: declared in the loaded ontologies, or a built-in term of RDF,
     * RDFS, OWL or XSD. The one definition that {@code lookup_term} and {@code validate_file} share.
     *
     * @param iri the full IRI of the term
     * @return whether the term is known
     */
    public boolean isKnown(String iri) {
        return contains(iri) || BuiltIns.isBuiltIn(iri);
    }

    /**
     * Tells whether the loaded ontologies mention an IRI at all, also when they do not declare it
     * (e.g. only as the object of {@code rdfs:range}).
     *
     * @param iri the full IRI
     * @return whether it occurs in a triple of the ontologies
     */
    public boolean isMentioned(String iri) {
        return model.containsResource(model.createResource(iri));
    }

    /**
     * Tells whether a term is deprecated: {@code owl:deprecated true}, or typed
     * {@code owl:DeprecatedClass} or {@code owl:DeprecatedProperty}.
     *
     * @param iri the full IRI
     * @return whether the ontologies deprecate it
     */
    public boolean isDeprecated(String iri) {
        Resource r = model.createResource(iri);
        return model.contains(r, RDF.type, OWL.DeprecatedClass) || model.contains(r, RDF.type, OWL.DeprecatedProperty)
                || model.listObjectsOfProperty(r, OWL.deprecated).toList().stream()
                        .anyMatch(VocabIndex::isTrue);
    }

    /**
     * Returns what replaces a deprecated term: {@code dct:isReplacedBy}, or else an equivalent class
     * or property.
     *
     * @param iri the full IRI
     * @return the replacements, as prefixed names where possible
     */
    public List<String> replacements(String iri) {
        Resource r = model.createResource(iri);
        List<String> out = objects(r, DCT_IS_REPLACED_BY);
        return out.isEmpty() ? objects(r, OWL.equivalentClass, OWL.equivalentProperty) : out;
    }

    /** Whether a value of owl:deprecated means true ("true"^^xsd:boolean, or "true", "1"). */
    static boolean isTrue(RDFNode n) {
        if (!n.isLiteral()) {
            return false;
        }
        String v = n.asLiteral().getLexicalForm().strip();
        return v.equalsIgnoreCase("true") || v.equals("1");
    }

    /**
     * Tells whether a term is a class: typed as one, or the subject of {@code rdfs:subClassOf}, or a
     * built-in class.
     *
     * @param iri the full IRI
     * @return whether it is declared as a class
     */
    public boolean isClass(String iri) {
        Resource r = model.createResource(iri);
        return CLASS_TYPES.stream().anyMatch(t -> model.contains(r, RDF.type, t))
                || model.contains(r, RDFS.subClassOf) || BuiltIns.isBuiltInClass(iri);
    }

    /**
     * Tells whether a term is a property: typed as one, or the subject of {@code subPropertyOf},
     * {@code domain} or {@code range}, or a built-in property.
     *
     * @param iri the full IRI
     * @return whether it is declared as a property
     */
    public boolean isProperty(String iri) {
        Resource r = model.createResource(iri);
        return PROPERTY_TYPES.stream().anyMatch(t -> model.contains(r, RDF.type, t))
                || model.contains(r, RDFS.subPropertyOf) || model.contains(r, RDFS.domain)
                || model.contains(r, RDFS.range) || BuiltIns.isBuiltInProperty(iri);
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
        return describe(term, expand(term));
    }

    /**
     * Describes one term given by its full IRI, without reading it as a prefixed name (an IRI such
     * as {@code urn:x:y} or {@code mailto:a@b} would otherwise be taken for one).
     *
     * @param iri the full IRI
     * @return the description, or a "Not found" message with suggestions
     */
    public String lookupIri(String iri) {
        return describe(iri, iri);
    }

    private String describe(String term, String iri) {
        Resource r = model.createResource(iri);
        if (!contains(iri)) {
            if (BuiltIns.isBuiltIn(iri) && !model.listStatements(r, null, (RDFNode) null).hasNext()) {
                return shortForm(iri) + "  <" + iri + ">\n  built-in term of RDF, RDFS, OWL or XSD; "
                        + "the loaded ontologies do not describe it\n";
            }
            if (!BuiltIns.isBuiltIn(iri)) {
                return notFound(term, iri);
            }
        }

        StringBuilder sb = new StringBuilder();
        String shortForm = shortForm(iri);
        sb.append(shortForm.equals("<" + iri + ">") ? shortForm : shortForm + "  <" + iri + ">").append('\n');
        section(sb, "type", objects(r, RDF.type));
        if (isDeprecated(iri)) {
            List<String> by = replacements(iri);
            sb.append("  deprecated: true").append(by.isEmpty() ? "" : " (replaced by " + String.join(", ", by) + ")")
                    .append('\n');
        }
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
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, not " + limit);
        }
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
        if (!iri.equals(term) && !term.strip().equals("<" + iri + ">")) {
            sb.append("  <").append(iri).append(">");
        }
        sb.append('\n');
        Resource r = model.createResource(iri);
        if (model.containsResource(r)) {
            List<String> where = new ArrayList<>();
            model.listStatements(null, null, r).forEachRemaining(st -> where.add(
                    render(st.getSubject()) + " " + render(st.getPredicate()) + " " + shortForm(iri)));
            model.listStatements(r, null, (RDFNode) null).forEachRemaining(st -> where.add(
                    shortForm(iri) + " " + render(st.getPredicate()) + " " + render(st.getObject())));
            sb.append("The loaded ontologies mention it but do not declare it (no type, and no "
                    + "subClassOf, subPropertyOf, domain, range or OWL axiom about it): ")
                    .append(String.join("; ", where.stream().sorted().limit(3).toList()))
                    .append(where.size() > 3 ? "; …" : "").append('\n');
        }
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

    /** Dutch first (also nl-BE, nl-NL), then English (also en-GB, …), then no language, then others. */
    static int languageRank(Literal l) {
        String lang = l.getLanguage().toLowerCase(Locale.ROOT);
        if (lang.equals("nl") || lang.startsWith("nl-")) {
            return 0;
        }
        if (lang.equals("en") || lang.startsWith("en-")) {
            return 1;
        }
        return lang.isEmpty() ? 2 : 3;
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
