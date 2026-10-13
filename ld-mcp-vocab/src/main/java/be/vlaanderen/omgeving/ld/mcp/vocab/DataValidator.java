package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.jena.rdf.model.InfModel;
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
import org.apache.jena.shacl.validation.Severity;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import be.vlaanderen.omgeving.ld.mcp.core.JsonLd;

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
 *   <li>SHACL (optional, {@code --shapes} and/or {@code --generate-shapes}) on the original
 *       model.</li>
 * </ol>
 * The report marks each step with {@code [VOCAB]}/{@code [VOCAB ERROR]}, {@code [INFERENCE]},
 * {@code [OWL]}/{@code [MODEL INVALID]} and {@code [SHACL]}.
 */
public final class DataValidator {

    /** Maximum number of messages per step; the remainder is only counted. */
    private static final int MAX_MESSAGES = 50;

    private final Model ontology;
    private final VocabIndex index;
    private final Set<String> vocabularyNamespaces;
    private final Model structural;
    private final Reasoner owlReasoner;
    private final List<Rule> rules;
    private final Shapes shapes;
    private final PrefixRegistry registry;

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
        this.vocabularyNamespaces = new HashSet<>();
        ontology.listSubjects().filterKeep(Resource::isURIResource)
                .forEachRemaining(s -> vocabularyNamespaces.add(namespace(s.getURI())));
        this.structural = subset(ontology,
                RDFS.subClassOf, RDFS.subPropertyOf, OWL.inverseOf, RDFS.domain, RDFS.range);
        this.owlReasoner = ReasonerRegistry.getOWLMiniReasoner()
                .bindSchema(disjointness(ontology));
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
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("File not found: " + path.toAbsolutePath());
        }
        Model data;
        try {
            data = JsonLd.load(path);
        } catch (RiotException e) {
            return "PARSE ERROR in " + file + ": " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Validation: ").append(file).append(" (").append(data.size()).append(" triples)\n");
        vocabulary(data, sb);
        Model inferred = inference(data, sb);
        owl(inferred, data, sb);
        shacl(data, sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- steps

    private void vocabulary(Model data, StringBuilder sb) {
        // Sorted by IRI, then by kind: the order of the statements in a model is not stable.
        Set<String> unknown = new TreeSet<>(Comparator.comparing((String u) -> u.substring(u.indexOf(": ") + 2))
                .thenComparing(Comparator.naturalOrder()));
        data.listStatements().forEachRemaining(st -> {
            String p = st.getPredicate().getURI();
            if (!ontology.containsResource(ontology.createResource(p))) {
                unknown.add("Unknown property: " + p);
            }
            if (st.getPredicate().equals(RDF.type) && st.getObject().isURIResource()) {
                String c = st.getObject().asResource().getURI();
                if (!ontology.containsResource(ontology.createResource(c))) {
                    unknown.add("Unknown class: " + c);
                }
            } else if (st.getObject().isURIResource()
                    && (isUnknownInVocabulary(st.getResource()) || isNearVocabulary(st.getResource()))) {
                unknown.add("Unknown resource: " + st.getResource().getURI());
            }
            if (st.getSubject().isURIResource()) {
                if (isUnknownInVocabulary(st.getSubject())) {
                    unknown.add("Resource minted in a vocabulary namespace: " + st.getSubject().getURI());
                } else if (isNearVocabulary(st.getSubject())) {
                    unknown.add("Unknown resource: " + st.getSubject().getURI());
                }
            }
        });
        if (unknown.isEmpty()) {
            sb.append("\n[VOCAB] ok\n");
            return;
        }
        sb.append("\n[VOCAB ERROR] ").append(unknown.size()).append(" unknown term(s)\n");
        List<String> lines = new ArrayList<>();
        for (String u : unknown) {
            StringBuilder line = new StringBuilder("- ").append(u);
            String iri = u.substring(u.indexOf(": ") + 2);
            Hint hint = nearMiss(iri);
            if (hint != null) {
                line.append("\n    ").append(hint.text());
            }
            if (hint == null || !hint.termExists()) {
                // Suggestions from lookup_term: everything after the "Not found" line.
                index.lookup(iri).lines().skip(1)
                        .forEach(s -> line.append("\n    ").append(s));
            }
            lines.add(line.toString());
        }
        appendCapped(sb, lines);
    }

    private Model inference(Model data, StringBuilder sb) {
        if (rules == null) {
            sb.append("\n[INFERENCE] skipped (no --rules)\n");
            return data;
        }
        InfModel inf = ModelFactory.createInfModel(new GenericRuleReasoner(rules).bindSchema(structural), data);
        Model result = ModelFactory.createDefaultModel().add(data).add(inf.getDeductionsModel());
        sb.append("\n[INFERENCE] ").append(result.size() - data.size()).append(" inferred triples\n");
        return result;
    }

    private void owl(Model inferred, Model data, StringBuilder sb) {
        ValidityReport report = ModelFactory.createInfModel(owlReasoner, inferred).validate();
        List<String> lines = new ArrayList<>();
        report.getReports().forEachRemaining(r -> lines.add("- " + shorten(data, r.getDescription())));
        Collections.sort(lines);
        if (report.isValid() && lines.isEmpty()) {
            sb.append("\n[OWL] consistent\n");
            return;
        }
        sb.append("\n[MODEL INVALID] ").append(lines.size()).append(" message(s)")
                .append(report.isValid() ? " (warnings only)" : "").append('\n');
        appendCapped(sb, lines);
    }

    private void shacl(Model data, StringBuilder sb) {
        if (shapes == null) {
            sb.append("\n[SHACL] skipped (no --shapes or --generate-shapes)\n");
            return;
        }
        ValidationReport report = ShaclValidator.get().validate(shapes, data.getGraph());
        if (report.conforms()) {
            sb.append("\n[SHACL] conforms\n");
            return;
        }
        // Violations first, then warnings, then info; only the latter two are marked.
        Map<Severity, List<String>> bySeverity = new LinkedHashMap<>();
        for (Severity s : List.of(Severity.Violation, Severity.Warning, Severity.Info)) {
            bySeverity.put(s, new ArrayList<>());
        }
        report.getEntries().forEach(e -> bySeverity.computeIfAbsent(e.severity(), s -> new ArrayList<>())
                .add("- " + (e.severity() == Severity.Violation ? "" : "[" + severityName(e.severity()) + "] ")
                        + data.shortForm(e.focusNode().toString())
                        + "  path: " + (e.resultPath() == null ? "-" : shorten(data, e.resultPath().toString()))
                        + "  — " + e.message()));
        List<String> counts = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        bySeverity.forEach((severity, entries) -> {
            Collections.sort(entries);
            if (!entries.isEmpty() || severity == Severity.Violation) {
                counts.add(entries.size() + " " + severityName(severity) + "(s)");
            }
            lines.addAll(entries);
        });
        sb.append("\n[SHACL] ").append(String.join(", ", counts)).append('\n');
        appendCapped(sb, lines);
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
     * Collects the class disjointness axioms that OWLMiniReasoner can use: {@code owl:disjointWith}
     * plus the pairs from {@code owl:AllDisjointClasses} and {@code owl:disjointUnionOf}. The latter
     * two use an RDF list (blank nodes) and would otherwise drop out of the subset; OWLMiniReasoner
     * only understands {@code owl:disjointWith}.
     *
     * @param source the ontologies
     * @return a model with only {@code owl:disjointWith} triples between named classes
     */
    static Model disjointness(Model source) {
        Model subset = subset(source, OWL.disjointWith);
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

    private static void appendCapped(StringBuilder sb, List<String> lines) {
        lines.stream().limit(MAX_MESSAGES).forEach(l -> sb.append(l).append('\n'));
        if (lines.size() > MAX_MESSAGES) {
            sb.append("… and ").append(lines.size() - MAX_MESSAGES).append(" more messages\n");
        }
    }

    /** An IRI in a namespace of the ontologies that the ontologies do not mention. */
    private boolean isUnknownInVocabulary(Resource r) {
        return vocabularyNamespaces.contains(namespace(r.getURI())) && !ontology.containsResource(r);
    }

    /**
     * An IRI outside the vocabulary namespaces, whose namespace is a near miss of one of them (see
     * {@link #variants(String)}), and that the ontologies do not mention.
     */
    private boolean isNearVocabulary(Resource r) {
        String iri = r.getURI();
        if (vocabularyNamespaces.contains(namespace(iri)) || ontology.containsResource(r)) {
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
    private record Hint(String text, boolean termExists) {
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
            if (ontology.containsResource(ontology.createResource(v))) {
                return new Hint("Did you mean <" + v + ">? (differs only in " + difference(iri, v) + ")", true);
            }
        }
        String ns = namespace(iri);
        if (vocabularyNamespaces.contains(ns)) {
            return null;
        }
        for (String v : variants) {
            if (vocabularyNamespaces.contains(namespace(v))) {
                return new Hint("Namespace <" + ns + "> is not loaded; did you mean <"
                        + namespace(v) + ">? (differs only in " + difference(iri, v) + ")", false);
            }
        }
        if (registry == null || isExampleDomain(iri)) {
            return null;
        }
        List<String> known = registry.prefixes(ns);
        if (!known.isEmpty()) {
            return new Hint("Namespace <" + ns + "> is a known vocabulary (" + prefixNames(known)
                    + " in the prefix registry), but it is not loaded", false);
        }
        for (String v : variants) {
            List<String> prefixes = registry.prefixes(namespace(v));
            if (!prefixes.isEmpty()) {
                return new Hint("Namespace <" + ns + "> is neither loaded nor registered; did you mean <"
                        + namespace(v) + "> (" + prefixNames(prefixes) + " in the prefix registry)? "
                        + "(differs only in " + difference(iri, v) + ")", false);
            }
        }
        return new Hint("Namespace <" + ns + "> is neither loaded nor in the prefix registry; "
                + "check it for a typo, or load its ontology", false);
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
