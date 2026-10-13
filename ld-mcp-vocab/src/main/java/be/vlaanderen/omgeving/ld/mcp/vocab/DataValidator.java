package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.jena.datatypes.BaseDatatype;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.compose.Union;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.parser.Shape;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.shacl.validation.VLib;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.shacl.validation.Severity;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;

import be.vlaanderen.omgeving.ld.mcp.core.JsonLd;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Certainty;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Finding;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Header;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Kind;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Status;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Step;

/**
 * Validates an RDF data file against the loaded ontologies, in four steps:
 * <ol>
 *   <li>vocabulary: every predicate and every {@code rdf:type} object must occur in the
 *       ontologies, and so must every other IRI in a namespace that the ontologies define
 *       resources in (e.g. a QUDT unit). A data file may not mint new resources in such a
 *       namespace either;</li>
 *   <li>rule inference (optional, {@code --rules}) with the structural subset
 *       (subClassOf, subPropertyOf, inverseOf, domain, range);</li>
 *   <li>OWL consistency with OWLMiniReasoner and the {@code owl:disjointWith} subset
 *       (including {@code owl:AllDisjointClasses} and {@code owl:disjointUnionOf}, expanded into
 *       pairs), on the model after inference;</li>
 *   <li>SHACL (optional, {@code --shapes} and/or {@code --generate-shapes}) on the data plus the
 *       class hierarchy and the types of referenced terms, without inferred triples.</li>
 * </ol>
 * The report marks each step with {@code [VOCAB]}/{@code [VOCAB ERROR]}, {@code [INFERENCE]},
 * {@code [OWL]}/{@code [MODEL INVALID]} and {@code [SHACL]}.
 */
public final class DataValidator {

    private final Model ontology;
    private final VocabIndex index;
    private final Set<String> vocabularyNamespaces;
    private final Model structural;
    private final Reasoner owlReasoner;
    private final Graph hierarchy;
    private final List<Rule> rules;
    private final Shapes shapes;
    private final PrefixRegistry registry;
    private ValidationResult.Configuration configuration;

    /** What the SHACL step validates, as the report says it. */
    static final String SHACL_GRAPH = "asserted data + class hierarchy + types of referenced terms";

    /**
     * Prepares the subsets, reasoners and shapes from files; these are reused for every validation.
     *
     * @param ontology       the loaded ontologies
     * @param index          index over the same ontologies, used for suggestions on unknown terms
     * @param rules          Jena rules file, or {@code null} to skip inference
     * @param shapes         SHACL shapes file, or {@code null}
     * @param generateShapes generate shapes from the OWL restrictions of the ontology (combined
     *                       with {@code shapes}, if given); without either, SHACL is skipped
     * @param registry       registry of well-known namespaces, used in hints for namespaces that
     *                       are not loaded; or {@code null}
     * @throws RuntimeException if the rules or shapes cannot be parsed
     */
    public DataValidator(Model ontology, VocabIndex index, Path rules, Path shapes,
                         boolean generateShapes, PrefixRegistry registry) {
        this(ontology, index, rules == null ? null : parseRules(rules),
                shapes(generateShapes ? ShaclGenerator.generate(ontology) : null,
                        shapes == null ? null : JsonLd.load(shapes)),
                registry);
    }

    /**
     * Prepares the subsets and the reasoner; the rules and shapes are given parsed, so that the
     * caller decides what happens when one of them cannot be read ({@link Workspace}).
     *
     * @param ontology the loaded ontologies
     * @param index    index over the same ontologies, used for suggestions on unknown terms
     * @param rules    the rules for the inference step, or {@code null} to skip it
     * @param shapes   the shapes, or {@code null} to skip SHACL
     * @param registry registry of well-known namespaces, used in hints for namespaces that are not
     *                 loaded; or {@code null}
     */
    public DataValidator(Model ontology, VocabIndex index, List<Rule> rules, Shapes shapes,
                         PrefixRegistry registry) {
        this.ontology = ontology;
        this.index = index;
        // Only namespaces of declared terms: not of ontology headers, shapes or other subjects.
        this.vocabularyNamespaces = index.namespaces();
        this.structural = subset(ontology,
                RDFS.subClassOf, RDFS.subPropertyOf, OWL.inverseOf, RDFS.domain, RDFS.range);
        this.owlReasoner = ReasonerRegistry.getOWLMiniReasoner()
                .bindSchema(disjointness(ontology));
        // Direct subClassOf triples suffice: SHACL follows rdfs:subClassOf* in the data graph itself.
        this.hierarchy = subset(ontology, RDFS.subClassOf).getGraph();
        this.rules = rules;
        this.shapes = shapes;
        this.registry = registry;
    }

    /**
     * Parses a Jena rules file.
     *
     * @param file the rules file
     * @return the rules
     * @throws RuntimeException if the file cannot be read or parsed
     */
    static List<Rule> parseRules(Path file) {
        return Rule.parseRules(read(file));
    }

    /**
     * Validates one data file.
     *
     * @param file path to the file, relative to the working directory or absolute; the format is
     *             derived from the extension
     * @return the report, or a message starting with {@code PARSE ERROR} if the file cannot be parsed
     * @throws IllegalArgumentException if the file does not exist
     */
    public String validate(String file) {
        return validate(Path.of(file), file);
    }

    /**
     * Validates one data file.
     *
     * @param path  the file; the format is derived from the extension
     * @param label how the report names the file
     * @return the report, or a message starting with {@code PARSE ERROR} if the file cannot be parsed
     * @throws IllegalArgumentException if the file does not exist
     */
    public String validate(Path path, String label) {
        return TextReport.render(check(path, label));
    }

    /**
     * Validates one data file and returns the result as data.
     *
     * @param path  the file; the format is derived from the extension
     * @param label how the result names the file
     * @return the result; if the file cannot be parsed, a header with the parse error and no steps
     * @throws IllegalArgumentException if the file does not exist
     */
    public ValidationResult check(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + path.toAbsolutePath());
        }
        Model data;
        try {
            data = JsonLd.load(path);
        } catch (RiotException e) {
            return new ValidationResult(new Header(label, -1, e.getMessage(), configuration), List.of());
        }
        List<Step> steps = new ArrayList<>();
        steps.add(vocabulary(data));
        Model inferred = data;
        if (rules == null) {
            steps.add(new Step(Kind.INFERENCE, Status.SKIPPED, List.of(), Map.of()));
        } else {
            inferred = inference(data);
            steps.add(new Step(Kind.INFERENCE, Status.OK, List.of(), Map.of("inferred", inferred.size() - data.size())));
        }
        steps.add(disjointness(inferred, data));
        steps.add(shacl(data));
        return new ValidationResult(new Header(label, data.size(), null, configuration), steps);
    }

    /**
     * Sets the configuration that every result reports in its header.
     *
     * @param configuration the configuration, or {@code null}
     * @return this validator
     */
    DataValidator withConfiguration(ValidationResult.Configuration configuration) {
        this.configuration = configuration;
        return this;
    }

    // ---------------------------------------------------------------- steps

    private Step vocabulary(Model data) {
        // Sorted by IRI, then by kind: the order of the statements in a model is not stable.
        Map<String, String> unknown = new TreeMap<>(Comparator.comparing((String u) -> u.substring(u.indexOf(": ") + 2))
                .thenComparing(Comparator.naturalOrder()));
        data.listStatements().forEachRemaining(st -> {
            String p = st.getPredicate().getURI();
            deprecated(p, unknown);
            if (!index.isKnown(p)) {
                unknownOrUndefined(unknown, "property", p);
            } else if (index.isClass(p) && !index.isProperty(p)) {
                unknown.put("Class used as property: " + p, "class-used-as-property");
            }
            if (st.getPredicate().equals(RDF.type) && st.getObject().isURIResource()) {
                String c = st.getObject().asResource().getURI();
                deprecated(c, unknown);
                if (!index.isKnown(c)) {
                    unknownOrUndefined(unknown, "class", c);
                } else if (index.isProperty(c) && !index.isClass(c)) {
                    unknown.put("Property used as class: " + c, "property-used-as-class");
                }
            } else if (st.getObject().isURIResource()
                    && (isUnknownInVocabulary(st.getResource()) || isNearVocabulary(st.getResource()))) {
                unknownOrUndefined(unknown, "resource", st.getResource().getURI());
            } else if (st.getObject().isURIResource()) {
                deprecated(st.getResource().getURI(), unknown);
            }
            if (st.getObject().isLiteral()) {
                checkLiteral(st.getObject().asLiteral(), unknown);
            }
            if (st.getSubject().isURIResource()) {
                if (isUnknownInVocabulary(st.getSubject())) {
                    unknown.put("Resource minted in a vocabulary namespace: " + st.getSubject().getURI(),
                            "minted-in-vocabulary-namespace");
                } else if (isNearVocabulary(st.getSubject())) {
                    unknown.put("Unknown resource: " + st.getSubject().getURI(), "unknown-resource");
                }
            }
        });
        // A minted resource is not reported once more as unknown where the data uses it.
        Set<String> minted = new HashSet<>();
        unknown.forEach((m, code) -> {
            if (code.equals("minted-in-vocabulary-namespace")) {
                minted.add(m.substring(m.indexOf(": ") + 2));
            }
        });
        unknown.entrySet().removeIf(e -> !e.getValue().equals("minted-in-vocabulary-namespace")
                && minted.contains(e.getKey().substring(e.getKey().indexOf(": ") + 2)));
        List<Finding> findings = new ArrayList<>();
        unknown.forEach((message, code) -> {
            String iri = message.substring(message.indexOf(": ") + 2);
            boolean role = code.endsWith("-used-as-class") || code.endsWith("-used-as-property")
                    || code.equals("invalid-literal") || code.equals("not-a-datatype")
                    || code.equals("deprecated-term");
            Hint hint = role ? null : nearMiss(iri);
            List<String> hints = new ArrayList<>();
            if (hint != null) {
                hints.add(hint.text());
            }
            if (code.equals("deprecated-term")) {
                List<String> by = index.replacements(iri);
                if (!by.isEmpty()) {
                    hints.add("Replaced by " + String.join(", ", by));
                }
            } else if (code.equals("unknown-datatype") && hint == null) {
                similarDatatypes(iri).ifPresent(hints::add);
            } else if (!role && (hint == null || !hint.termExists())) {
                // Suggestions from lookup_term: everything after the "Not found" line.
                index.lookup(iri).lines().skip(1).forEach(hints::add);
            }
            // Established only where the namespace is loaded: there the term certainly does not exist.
            Certainty certainty = code.startsWith("minted") || code.endsWith("-used-as-class")
                    || code.endsWith("-used-as-property") ? Certainty.ERROR
                    : code.equals("undefined-term") || code.equals("deprecated-term") ? Certainty.SUSPECT
                    : code.equals("not-a-datatype") || code.equals("invalid-literal") ? Certainty.ERROR
                    : code.equals("unknown-datatype") ? datatypeCertainty(iri)
                    : hint != null ? hint.certainty()
                    : vocabularyNamespaces.contains(namespace(iri)) ? Certainty.ERROR : Certainty.SUSPECT;
            findings.add(new Finding(certainty, code, iri, null, message, hints, null));
        });
        // Errors first, then suspects, then hints; within each, by IRI (the order of the map).
        findings.sort(Comparator.comparing(Finding::certainty));
        return new Step(Kind.VOCAB, findings.isEmpty() ? Status.OK : Status.FINDINGS, findings, Map.of());
    }

    private Model inference(Model data) {
        InfModel inf = ModelFactory.createInfModel(new GenericRuleReasoner(rules).bindSchema(structural), data);
        return ModelFactory.createDefaultModel().add(data).add(inf.getDeductionsModel());
    }

    private Step disjointness(Model inferred, Model data) {
        ValidityReport report = ModelFactory.createInfModel(owlReasoner, inferred).validate();
        List<Finding> findings = new ArrayList<>();
        report.getReports().forEachRemaining(r -> findings.add(new Finding(
                r.isError() ? Certainty.ERROR : Certainty.SUSPECT, "disjointness",
                r.getExtension() == null ? "" : String.valueOf(r.getExtension()), null,
                shorten(data, r.getDescription()), List.of(), null)));
        findings.sort(Comparator.comparing(Finding::message));
        boolean ok = report.isValid() && findings.isEmpty();
        return new Step(Kind.DISJOINTNESS, ok ? Status.OK : Status.FINDINGS, findings, Map.of("valid", report.isValid()));
    }

    private Step shacl(Model data) {
        if (shapes == null) {
            return new Step(Kind.SHACL, Status.SKIPPED, List.of(), Map.of());
        }
        // The data plus the class hierarchy of the ontologies, read-only and without copying: so that
        // sh:class and sh:targetClass follow subclasses. No inferred triples (D2): an inferred type
        // must not fill in a property that sh:minCount requires.
        Graph base = new Union(data.getGraph(), hierarchy);
        // Plus the types the ontologies assert for terms the data only refers to (a unit, a concept):
        // so that sh:class accepts unit:M as a qudt:Unit without the data repeating its type.
        Graph referenced = referencedTypes(data);
        Graph graph = referenced.isEmpty() ? base : new Union(base, referenced);
        ValidationReport report = ShaclValidator.get().validate(shapes, graph);
        Map<String, Object> stats = shaclStats(base);
        // Those terms must not become subjects of validation themselves: drop results on them, unless
        // they are focus nodes without the added types too (e.g. through sh:targetObjectsOf).
        Set<Node> introduced = new HashSet<>();
        referenced.find().forEachRemaining(t -> introduced.add(t.getSubject()));
        Set<Node> keep = focusNodes(base, introduced);
        List<ReportEntry> entries = report.getEntries().stream()
                .filter(e -> !introduced.contains(e.focusNode()) || keep.contains(e.focusNode()))
                .toList();
        if (entries.isEmpty()) {
            return new Step(Kind.SHACL, Status.OK, List.of(), stats);
        }
        List<Finding> findings = new ArrayList<>();
        entries.forEach(e -> {
            String severity = severityName(e.severity());
            Certainty certainty = e.severity() == Severity.Violation ? Certainty.ERROR
                    : e.severity() == Severity.Warning ? Certainty.SUSPECT : Certainty.HINT;
            findings.add(new Finding(certainty, "shacl", data.shortForm(e.focusNode().toString()),
                    e.resultPath() == null ? null : shorten(data, e.resultPath().toString()),
                    e.message(), List.of(), severity));
        });
        findings.sort(Comparator.comparing(TextReport::shaclLine));
        return new Step(Kind.SHACL, Status.FINDINGS, findings, stats);
    }

    /**
     * The {@code rdf:type} triples that the ontologies assert for IRIs that the data uses as an object
     * (not of {@code rdf:type}) without describing them itself.
     */
    private Graph referencedTypes(Model data) {
        Graph out = GraphFactory.createDefaultGraph();
        Graph dataGraph = data.getGraph();
        Graph onto = ontology.getGraph();
        Set<Node> seen = new HashSet<>();
        dataGraph.find().forEachRemaining(t -> {
            Node o = t.getObject();
            if (o.isURI() && !t.getPredicate().equals(RDF.type.asNode()) && seen.add(o)
                    && !dataGraph.contains(o, Node.ANY, Node.ANY)) {
                onto.find(o, RDF.type.asNode(), Node.ANY).forEachRemaining(out::add);
            }
        });
        return out;
    }

    /** Which of the given nodes are focus nodes of some shape in the graph. */
    private Set<Node> focusNodes(Graph graph, Set<Node> candidates) {
        Set<Node> out = new HashSet<>();
        if (candidates.isEmpty()) {
            return out;
        }
        for (Shape shape : shapes.getTargetShapes()) {
            for (Node n : candidates) {
                if (!out.contains(n) && VLib.isFocusNode(shape, n, graph)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /**
     * How much of the shapes the data met: the number of shapes with a target, how many of them
     * had at least one focus node in the data, and the number of distinct focus nodes. A
     * "conforms" with no shape applied says nothing about the data.
     */
    private Map<String, Object> shaclStats(Graph graph) {
        int targetShapes = 0;
        int applied = 0;
        Set<Node> focus = new HashSet<>();
        for (Shape shape : shapes.getTargetShapes()) {
            targetShapes++;
            Collection<Node> nodes = VLib.focusNodes(graph, shape);
            if (!nodes.isEmpty()) {
                applied++;
                focus.addAll(nodes);
            }
        }
        return Map.of("shapes", targetShapes, "applied", applied, "focusNodes", focus.size(),
                "graph", SHACL_GRAPH);
    }

    /** "violation", "warning" or "info"; for a custom severity, its local name in lower case. */
    private static String severityName(Severity severity) {
        String iri = severity.level().getURI();
        return iri.substring(Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/')) + 1).toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Combines generated and hand-written shapes.
     *
     * @param generated shapes generated from the ontology, or {@code null}
     * @param file      shapes read from files, or {@code null}
     * @return the shapes, or {@code null} if neither is given
     */
    static Shapes shapes(Model generated, Model file) {
        if (generated == null && file == null) {
            return null;
        }
        Model all = ModelFactory.createDefaultModel();
        if (generated != null) {
            all.add(generated);
        }
        if (file != null) {
            all.add(file);
        }
        return Shapes.parse(all);
    }

    /** Subset of the ontology with only the given predicates, without blank nodes. */
    private static Model subset(Model source, Property... predicates) {
        Model subset = ModelFactory.createDefaultModel();
        for (Property p : predicates) {
            source.listStatements(null, p, (RDFNode) null).forEachRemaining(st -> {
                if (st.getSubject().isURIResource() && st.getObject().isURIResource()) {
                    subset.add(st);
                }
            });
        }
        return subset;
    }

    /**
     * Collects the schema for the disjointness check: {@code owl:disjointWith} plus the pairs from
     * {@code owl:AllDisjointClasses} and {@code owl:disjointUnionOf} (which use an RDF list and would
     * otherwise drop out of the subset; OWLMiniReasoner only understands {@code owl:disjointWith}),
     * and the class hierarchy ({@code rdfs:subClassOf} between named classes), so that an instance
     * of a subclass meets the disjointness of its superclass also without {@code --rules}.
     *
     * @param source the ontologies
     * @return a model with {@code owl:disjointWith} and {@code rdfs:subClassOf} triples between named
     *         classes
     */
    static Model disjointness(Model source) {
        Model subset = subset(source, OWL.disjointWith, RDFS.subClassOf);
        source.listSubjectsWithProperty(RDF.type, OWL2.AllDisjointClasses).forEachRemaining(axiom ->
                addPairs(subset, axiom.getPropertyResourceValue(OWL2.members)));
        source.listStatements(null, OWL2.disjointUnionOf, (RDFNode) null).forEachRemaining(st ->
                addPairs(subset, st.getResource()));
        return subset;
    }

    private static void addPairs(Model subset, Resource list) {
        if (list == null || !list.canAs(RDFList.class)) {
            return;
        }
        List<Resource> members = list.as(RDFList.class).asJavaList().stream()
                .filter(RDFNode::isURIResource)
                .map(RDFNode::asResource)
                .toList();
        for (int i = 0; i < members.size(); i++) {
            for (int j = i + 1; j < members.size(); j++) {
                subset.add(members.get(i), OWL.disjointWith, members.get(j));
            }
        }
    }

    /**
     * The datatype if Jena implements it; {@code null} for none, and for the plain
     * {@link BaseDatatype} that the parser registers for every datatype IRI it meets (also for
     * {@code xsd:dateTim}), which knows nothing about the datatype.
     */
    private static RDFDatatype implemented(RDFDatatype t) {
        return t == null || t.getClass() == BaseDatatype.class ? null : t;
    }

    /** An unknown datatype in XSD, RDF or a loaded namespace certainly does not exist. */
    private Certainty datatypeCertainty(String iri) {
        String ns = namespace(iri);
        return ns.equals(XSD.NS) || ns.equals(RDF.uri) || vocabularyNamespaces.contains(ns)
                ? Certainty.ERROR : Certainty.SUSPECT;
    }

    /** Datatypes whose IRI differs from the given one in at most two characters, e.g. xsd:dateTime. */
    private Optional<String> similarDatatypes(String iri) {
        List<String> candidates = new ArrayList<>();
        TypeMapper.getInstance().listTypes().forEachRemaining(t -> {
            if (t.getURI() != null && implemented(t) != null && !t.getURI().equals(iri)) {
                candidates.add(t.getURI());
            }
        });
        ontology.listSubjectsWithProperty(RDF.type, RDFS.Datatype).filterKeep(Resource::isURIResource)
                .forEachRemaining(r -> candidates.add(r.getURI()));
        List<String> close = candidates.stream().distinct()
                .filter(c -> namespace(c).equals(namespace(iri)) && distance(c, iri) <= 2)
                .sorted().limit(3).toList();
        return close.isEmpty() ? Optional.empty()
                : Optional.of("Did you mean " + String.join(", ", close.stream().map(c -> "<" + c + ">").toList()) + "?");
    }

    /** Levenshtein distance. */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /**
     * Checks the datatype of a literal and its lexical form: an unknown datatype, a known term that
     * is no datatype (e.g. a unit), or a lexical form that is not valid for the datatype.
     */
    private void checkLiteral(Literal literal, Map<String, String> unknown) {
        String dt = literal.getDatatypeURI();
        if (dt == null || dt.equals(RDF.langString.getURI())) {
            return;
        }
        RDFDatatype known = implemented(TypeMapper.getInstance().getTypeByName(dt));
        boolean declared = ontology.contains(ontology.createResource(dt), RDF.type, RDFS.Datatype);
        if (known == null && !declared) {
            if (index.isKnown(dt) || index.isMentioned(dt)) {
                unknown.put("Not a datatype: " + dt, "not-a-datatype");
            } else {
                unknown.put("Unknown datatype: " + dt, "unknown-datatype");
            }
            return;
        }
        if (known != null && !known.isValid(literal.getLexicalForm())) {
            unknown.put("Invalid literal: \"" + Untrusted.literal(literal.getLexicalForm()) + "\"^^" + dt,
                    "invalid-literal");
        }
    }

    private void deprecated(String iri, Map<String, String> unknown) {
        if (index.isDeprecated(iri)) {
            unknown.put("Deprecated term: " + iri, "deprecated-term");
        }
    }

    /**
     * Reports an IRI that is not known: "Unknown …" if the ontologies do not mention it at all, "Term
     * only referenced, not defined" if they mention it without declaring it.
     */
    private void unknownOrUndefined(Map<String, String> unknown, String kind, String iri) {
        if (index.isMentioned(iri)) {
            unknown.put("Term only referenced, not defined: " + iri, "undefined-term");
        } else {
            unknown.put("Unknown " + kind + ": " + iri, "unknown-" + kind);
        }
    }

    /** An IRI in a namespace of the ontologies that the ontologies do not declare. */
    private boolean isUnknownInVocabulary(Resource r) {
        return vocabularyNamespaces.contains(namespace(r.getURI())) && !index.isKnown(r.getURI());
    }

    /**
     * An IRI outside the vocabulary namespaces, whose namespace is a near miss of one of them (see
     * {@link #variants(String)}), and that the ontologies do not mention.
     */
    private boolean isNearVocabulary(Resource r) {
        String iri = r.getURI();
        if (vocabularyNamespaces.contains(namespace(iri)) || index.isKnown(iri)) {
            return false;
        }
        return variants(iri).stream().anyMatch(v -> vocabularyNamespaces.contains(namespace(v)));
    }

    /**
     * A hint for an unknown IRI.
     *
     * @param text       the hint, ready for the report
     * @param termExists whether the hint names an existing term (which makes further suggestions
     *                   superfluous) rather than only a namespace
     */
    private record Hint(String text, boolean termExists, Certainty certainty) {
    }

    /**
     * Looks for a hint for an unknown IRI, in this order: a variant that the ontologies mention, a
     * variant in a vocabulary namespace and, with a prefix registry, the namespace itself or a
     * variant of it in the registry.
     *
     * @param iri the unknown IRI
     * @return the hint, or {@code null} if there is nothing to add
     */
    private Hint nearMiss(String iri) {
        List<String> variants = variants(iri);
        for (String v : variants) {
            if (index.isKnown(v)) {
                return new Hint("Did you mean <" + v + ">? (differs only in " + difference(iri, v) + ")", true,
                        Certainty.SUSPECT);
            }
        }
        String ns = namespace(iri);
        if (vocabularyNamespaces.contains(ns)) {
            return null;
        }
        for (String v : variants) {
            if (vocabularyNamespaces.contains(namespace(v))) {
                return new Hint("Namespace <" + ns + "> is not loaded; did you mean <"
                        + namespace(v) + ">? (differs only in " + difference(iri, v) + ")", false, Certainty.SUSPECT);
            }
        }
        if (registry == null || isExampleDomain(iri)) {
            return null;
        }
        List<String> known = registry.prefixes(ns);
        if (!known.isEmpty()) {
            return new Hint("Namespace <" + ns + "> is a known vocabulary (" + prefixNames(known)
                    + " in the prefix registry), but it is not loaded", false, Certainty.HINT);
        }
        for (String v : variants) {
            List<String> prefixes = registry.prefixes(namespace(v));
            if (!prefixes.isEmpty()) {
                return new Hint("Namespace <" + ns + "> is neither loaded nor registered; did you mean <"
                        + namespace(v) + "> (" + prefixNames(prefixes) + " in the prefix registry)? "
                        + "(differs only in " + difference(iri, v) + ")", false, Certainty.SUSPECT);
            }
        }
        return new Hint("Namespace <" + ns + "> is neither loaded nor in the prefix registry; "
                + "check it for a typo, or load its ontology", false, Certainty.SUSPECT);
    }

    /**
     * Whether an IRI is in one of the domains reserved for examples (RFC 2606). Such domains carry
     * no vocabulary, even if somebody has registered a prefix for them.
     */
    private static boolean isExampleDomain(String iri) {
        int start = iri.indexOf("://") + 3;
        int end = iri.indexOf('/', start);
        String host = iri.substring(start, end < 0 ? iri.length() : end).toLowerCase(Locale.ROOT);
        return host.matches("(.+\\.)?example\\.(com|org|net)(:\\d+)?");
    }

    /** At most three prefixes as {@code 'a:', 'b:', 'c:'}, followed by an ellipsis if there are more. */
    private static String prefixNames(List<String> prefixes) {
        String names = String.join(", ", prefixes.stream().limit(3).map(p -> "'" + p + ":'").toList());
        return prefixes.size() > 3 ? names + ", …" : names;
    }

    /**
     * Variants of an IRI that differ only in the scheme ({@code http} versus {@code https}) and/or
     * the namespace separator ({@code #} versus {@code /}). Both are common mistakes; many sites,
     * for instance, redirect {@code http} to {@code https}, while the {@code http} IRI is the
     * identifier.
     *
     * @param iri an IRI
     * @return the variants: scheme first, then separator, then both; never the IRI itself
     */
    static List<String> variants(String iri) {
        List<String> schemes = new ArrayList<>(List.of(iri));
        if (iri.startsWith("http://")) {
            schemes.add("https://" + iri.substring("http://".length()));
        } else if (iri.startsWith("https://")) {
            schemes.add("http://" + iri.substring("https://".length()));
        }
        List<String> result = new ArrayList<>(schemes.subList(1, schemes.size()));
        for (String s : schemes) {
            int sep = Math.max(s.lastIndexOf('#'), s.lastIndexOf('/'));
            if (sep > s.indexOf("://") + 2) {
                char other = s.charAt(sep) == '#' ? '/' : '#';
                result.add(s.substring(0, sep) + other + s.substring(sep + 1));
            }
        }
        return result;
    }

    /** Describes how a variant from {@link #variants(String)} differs from the original IRI. */
    private static String difference(String iri, String variant) {
        boolean scheme = iri.startsWith("https://") != variant.startsWith("https://");
        boolean separator = !iri.substring(iri.indexOf("://")).equals(variant.substring(variant.indexOf("://")));
        if (scheme && separator) {
            return "http/https and '#'/'/'";
        }
        return scheme ? "http/https" : "'#'/'/'";
    }

    /** The namespace of an IRI: everything up to and including the last '#' or '/'. */
    private static String namespace(String iri) {
        return iri.substring(0, Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/')) + 1);
    }

    /** Shortens IRIs in free text (OWL messages) using the prefixes of the data file. */
    private static String shorten(PrefixMapping prefixes, String text) {
        String out = text;
        for (var e : prefixes.getNsPrefixMap().entrySet()) {
            if (!e.getKey().isEmpty()) {
                String ns = Pattern.quote(e.getValue());
                out = out.replaceAll("<" + ns + "([^>\\s]*)>", e.getKey() + ":$1")
                        .replace(e.getValue(), e.getKey() + ":");
            }
        }
        return out;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
