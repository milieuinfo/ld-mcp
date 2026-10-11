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
 * Valideert een RDF-databestand tegen de opgeladen ontologieën, in vier stappen:
 * <ol>
 *   <li>vocabulaire: elk predicaat en elk {@code rdf:type}-doel moet in de ontologieën voorkomen;</li>
 *   <li>regelinferentie (optioneel, {@code --rules}) met de structurele deelset
 *       (subClassOf, subPropertyOf, inverseOf, domain, range);</li>
 *   <li>OWL-consistentie met OWLMiniReasoner en de deelset {@code owl:disjointWith}
 *       (inclusief {@code owl:AllDisjointClasses} en {@code owl:disjointUnionOf}, omgezet naar
 *       paren), op het model na inferentie;</li>
 *   <li>SHACL (optioneel, {@code --shapes}) op het oorspronkelijke model.</li>
 * </ol>
 */
public final class DataValidator {

    /** Maximum aantal meldingen per stap; de rest wordt enkel geteld. */
    private static final int MAX_MESSAGES = 50;

    private final Model ontology;
    private final VocabIndex index;
    private final Model structural;
    private final Reasoner owlReasoner;
    private final List<Rule> rules;
    private final Shapes shapes;

    /**
     * @param rules  Jena-regelbestand, of {@code null} om inferentie over te slaan
     * @param shapes SHACL-shapesbestand, of {@code null} om SHACL over te slaan
     */
    public DataValidator(Model ontology, VocabIndex index, Path rules, Path shapes) {
        this.ontology = ontology;
        this.index = index;
        this.structural = subset(ontology,
                RDFS.subClassOf, RDFS.subPropertyOf, OWL.inverseOf, RDFS.domain, RDFS.range);
        this.owlReasoner = ReasonerRegistry.getOWLMiniReasoner()
                .bindSchema(disjointness(ontology));
        this.rules = rules == null ? null : Rule.parseRules(read(rules));
        this.shapes = shapes == null ? null : Shapes.parse(RDFDataMgr.loadGraph(shapes.toString()));
    }

    public String validate(String file) {
        Path path = Path.of(file);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Bestand niet gevonden: " + path.toAbsolutePath());
        }
        Model data;
        try {
            data = RDFDataMgr.loadModel(path.toString());
        } catch (RiotException e) {
            return "PARSEFOUT in " + file + ": " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Validatie: ").append(file).append(" (").append(data.size()).append(" triples)\n");
        vocabulary(data, sb);
        Model inferred = inference(data, sb);
        owl(inferred, data, sb);
        shacl(data, sb);
        return sb.toString();
    }

    // ---------------------------------------------------------------- stappen

    private void vocabulary(Model data, StringBuilder sb) {
        Set<String> unknown = new LinkedHashSet<>();
        data.listStatements().forEachRemaining(st -> {
            String p = st.getPredicate().getURI();
            if (!ontology.containsResource(ontology.createResource(p))) {
                unknown.add("Onbekende property: " + p);
            }
            if (st.getPredicate().equals(RDF.type) && st.getObject().isURIResource()) {
                String c = st.getObject().asResource().getURI();
                if (!ontology.containsResource(ontology.createResource(c))) {
                    unknown.add("Onbekende class: " + c);
                }
            }
        });
        if (unknown.isEmpty()) {
            sb.append("\n[VOCAB] ok\n");
            return;
        }
        sb.append("\n[VOCAB ERROR] ").append(unknown.size()).append(" onbekende term(en)\n");
        List<String> lines = new ArrayList<>();
        for (String u : unknown) {
            StringBuilder line = new StringBuilder("- ").append(u);
            String iri = u.substring(u.indexOf(": ") + 2);
            // Suggesties uit lookup_term: alles na de "Niet gevonden"-regel.
            index.lookup(iri).lines().skip(1)
                    .forEach(s -> line.append("\n    ").append(s));
            lines.add(line.toString());
        }
        appendCapped(sb, lines);
    }

    private Model inference(Model data, StringBuilder sb) {
        if (rules == null) {
            sb.append("\n[INFERENTIE] overgeslagen (geen --rules)\n");
            return data;
        }
        InfModel inf = ModelFactory.createInfModel(new GenericRuleReasoner(rules).bindSchema(structural), data);
        Model result = ModelFactory.createDefaultModel().add(data).add(inf.getDeductionsModel());
        sb.append("\n[INFERENTIE] ").append(result.size() - data.size()).append(" afgeleide triples\n");
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
        sb.append("\n[MODEL INVALID] ").append(lines.size()).append(" melding(en)")
                .append(report.isValid() ? " (enkel waarschuwingen)" : "").append('\n');
        appendCapped(sb, lines);
    }

    private void shacl(Model data, StringBuilder sb) {
        if (shapes == null) {
            sb.append("\n[SHACL] overgeslagen (geen --shapes)\n");
            return;
        }
        ValidationReport report = ShaclValidator.get().validate(shapes, data.getGraph());
        if (report.conforms()) {
            sb.append("\n[SHACL] conform\n");
            return;
        }
        List<String> lines = new ArrayList<>();
        report.getEntries().forEach(e -> lines.add("- " + data.shortForm(e.focusNode().toString())
                + "  pad: " + (e.resultPath() == null ? "-" : shorten(data, e.resultPath().toString()))
                + "  — " + e.message()));
        sb.append("\n[SHACL] ").append(lines.size()).append(" schending(en)\n");
        appendCapped(sb, lines);
    }

    // ---------------------------------------------------------------- hulpfuncties

    /** Deelset van de ontologie met enkel de gegeven predicaten, zonder blank nodes. */
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
     * {@code owl:disjointWith} plus de paren uit {@code owl:AllDisjointClasses} en
     * {@code owl:disjointUnionOf}. Die laatste twee gebruiken een RDF-lijst (blank nodes) en zouden
     * anders uit de deelset vallen; OWLMiniReasoner kent enkel {@code owl:disjointWith}.
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
            sb.append("… en nog ").append(lines.size() - MAX_MESSAGES).append(" meldingen\n");
        }
    }

    /** IRI's in een vrije tekst (OWL-meldingen) inkorten met de prefixen van het databestand. */
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
