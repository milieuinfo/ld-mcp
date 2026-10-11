package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * Validates an RDF data file against the loaded ontologies, in four steps:
 * <ol>
 *   <li>vocabulary: every predicate and every {@code rdf:type} object must occur in the
 *       ontologies;</li>
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
    private final Model structural;
    private final Reasoner owlReasoner;
    private final List<Rule> rules;
    private final Shapes shapes;

    /**
     * Prepares the subsets, reasoners and shapes; these are reused for every validation.
     *
     * @param ontology       the loaded ontologies
     * @param index          index over the same ontologies, used for suggestions on unknown terms
     * @param rules          Jena rules file, or {@code null} to skip inference
     * @param shapes         SHACL shapes file, or {@code null}
     * @param generateShapes generate shapes from the OWL restrictions of the ontology (combined
     *                       with {@code shapes}, if given); without either, SHACL is skipped
     */
    public DataValidator(Model ontology, VocabIndex index, Path rules, Path shapes,
                         boolean generateShapes) {
        this.ontology = ontology;
        this.index = index;
        this.structural = subset(ontology,
                RDFS.subClassOf, RDFS.subPropertyOf, OWL.inverseOf, RDFS.domain, RDFS.range);
        this.owlReasoner = ReasonerRegistry.getOWLMiniReasoner()
                .bindSchema(disjointness(ontology));
        this.rules = rules == null ? null : Rule.parseRules(read(rules));
        this.shapes = shapes(ontology, shapes, generateShapes);
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
            data = RDFDataMgr.loadModel(path.toString());
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
        Set<String> unknown = new LinkedHashSet<>();
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
            // Suggestions from lookup_term: everything after the "Not found" line.
            index.lookup(iri).lines().skip(1)
                    .forEach(s -> line.append("\n    ").append(s));
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
        List<String> lines = new ArrayList<>();
        report.getEntries().forEach(e -> lines.add("- " + data.shortForm(e.focusNode().toString())
                + "  path: " + (e.resultPath() == null ? "-" : shorten(data, e.resultPath().toString()))
                + "  — " + e.message()));
        sb.append("\n[SHACL] ").append(lines.size()).append(" violation(s)\n");
        appendCapped(sb, lines);
    }

    // ---------------------------------------------------------------- helpers

    private static Shapes shapes(Model ontology, Path file, boolean generate) {
        if (file == null && !generate) {
            return null;
        }
        Model all = ModelFactory.createDefaultModel();
        if (generate) {
            all.add(ShaclGenerator.generate(ontology));
        }
        if (file != null) {
            all.add(RDFDataMgr.loadModel(file.toString()));
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
