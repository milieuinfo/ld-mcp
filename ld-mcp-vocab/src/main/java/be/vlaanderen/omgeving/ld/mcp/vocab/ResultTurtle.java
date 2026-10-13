package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.List;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Certainty;
import be.vlaanderen.omgeving.ld.mcp.vocab.ValidationResult.Finding;

/**
 * Validation results as RDF: one {@code sh:ValidationReport} per file, with every finding of every
 * step as an {@code sh:ValidationResult}. Findings of the VOCAB and DISJOINTNESS steps get a source
 * constraint component of their own, {@code urn:x-ld-mcp:component:<code>}; SHACL findings
 * {@code urn:x-ld-mcp:component:shacl} (the message says which constraint). Severity: error →
 * {@code sh:Violation}, suspect → {@code sh:Warning}, hint → {@code sh:Info}.
 */
final class ResultTurtle {

    /** Namespace of the constraint components of the non-SHACL findings. */
    static final String COMPONENT = "urn:x-ld-mcp:component:";

    private static final String SH = ShaclGenerator.SH;

    private ResultTurtle() {
    }

    /**
     * Builds the reports.
     *
     * @param results the results, one per file
     * @return the reports as a model
     */
    static Model toModel(List<ValidationResult> results) {
        Model m = ModelFactory.createDefaultModel();
        Property conforms = m.createProperty(SH + "conforms");
        Property result = m.createProperty(SH + "result");
        for (ValidationResult r : results) {
            Resource report = m.createResource().addProperty(RDF.type, m.createResource(SH + "ValidationReport"))
                    .addProperty(RDFS.label, r.header().file());
            boolean ok = r.header().parseError() == null && r.steps().stream()
                    .flatMap(s -> s.findings().stream()).noneMatch(f -> f.certainty() == Certainty.ERROR);
            report.addLiteral(conforms, ok);
            if (r.header().parseError() != null) {
                report.addProperty(RDFS.comment, "PARSE ERROR: " + r.header().parseError());
            }
            r.steps().forEach(step -> step.findings().forEach(f -> report.addProperty(result, finding(m, f))));
        }
        return m;
    }

    /**
     * Writes the reports as Turtle, the same text for the same results.
     *
     * @param results the results
     * @return the Turtle
     */
    static String write(List<ValidationResult> results) {
        PrefixMapping p = PrefixMapping.Factory.create().setNsPrefix("sh", SH)
                .setNsPrefix("rdfs", RDFS.getURI()).setNsPrefix("xsd", org.apache.jena.vocabulary.XSD.NS);
        return CanonicalTurtle.write(toModel(results), p);
    }

    private static Resource finding(Model m, Finding f) {
        Resource r = m.createResource().addProperty(RDF.type, m.createResource(SH + "ValidationResult"));
        if (f.focusIri() != null) {
            r.addProperty(m.createProperty(SH + "focusNode"), m.createResource(f.focusIri()));
        } else {
            r.addProperty(m.createProperty(SH + "focusNode"), f.focus());
        }
        if (f.pathIri() != null) {
            r.addProperty(m.createProperty(SH + "resultPath"), m.createResource(f.pathIri()));
        }
        String message = f.hints().isEmpty() ? f.message() : f.message() + " — " + String.join(" | ", f.hints());
        r.addProperty(m.createProperty(SH + "resultMessage"), message);
        r.addProperty(m.createProperty(SH + "resultSeverity"), m.createResource(SH + switch (f.certainty()) {
            case ERROR -> "Violation";
            case SUSPECT -> "Warning";
            case HINT -> "Info";
        }));
        r.addProperty(m.createProperty(SH + "sourceConstraintComponent"), m.createResource(COMPONENT + f.code()));
        return r;
    }
}
