package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.shared.PrefixMapping;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.XSD;

/**
 * Writes a model whose blank nodes form trees (as generated shapes and validation reports do) as
 * Turtle that is the same for the same triples: subjects sorted by IRI, {@code a} first, then
 * predicates and objects sorted by their text, blank nodes nested and lists inline. A diff of two
 * runs therefore shows only real changes. A blank node that is used twice, or not at all, is
 * written at the end with a label of its own.
 */
final class CanonicalTurtle {

    private final Model model;
    private final PrefixMapping prefixes;
    private final Map<Resource, Integer> uses = new java.util.HashMap<>();

    private CanonicalTurtle(Model model, PrefixMapping prefixes) {
        this.model = model;
        this.prefixes = prefixes;
        model.listStatements().forEachRemaining(st -> {
            if (st.getObject().isAnon()) {
                uses.merge(st.getObject().asResource(), 1, Integer::sum);
            }
        });
    }

    /**
     * Writes a model.
     *
     * @param model    the triples
     * @param prefixes candidate prefixes; only those that are used are declared
     * @return the Turtle text
     */
    static String write(Model model, PrefixMapping prefixes) {
        return new CanonicalTurtle(model, prefixes).write();
    }

    private String write() {
        StringBuilder body = new StringBuilder();
        Set<String> subjects = new TreeSet<>();
        model.listSubjects().forEachRemaining(s -> {
            if (s.isURIResource()) {
                subjects.add(s.getURI());
            }
        });
        for (String s : subjects) {
            Resource r = model.createResource(s);
            body.append(term(r)).append(predicates(r, 1)).append(" .\n\n");
        }
        // Blank nodes that cannot be nested: roots (subject only) and shared ones, with stable labels.
        List<String> rest = new ArrayList<>();
        model.listSubjects().forEachRemaining(s -> {
            if (s.isAnon() && uses.getOrDefault(s, 0) != 1) {
                rest.add("[]" + predicates(s, 1) + " .\n\n");
            }
        });
        rest.stream().sorted().forEach(body::append);
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(prefixes.getNsPrefixMap()).forEach((p, ns) -> {
            if (!p.isEmpty() && body.indexOf(p + ":") >= 0) {
                sb.append("@prefix ").append(p).append(": <").append(ns).append("> .\n");
            }
        });
        return sb.append('\n').append(body).toString().stripTrailing() + "\n";
    }

    private String predicates(Resource r, int depth) {
        List<String> types = new ArrayList<>();
        Map<String, List<String>> byPredicate = new TreeMap<>();
        for (Statement st : r.listProperties().toList()) {
            String o = object(st.getObject(), depth);
            if (st.getPredicate().equals(RDF.type)) {
                types.add(o);
            } else {
                byPredicate.computeIfAbsent(term(st.getPredicate()), k -> new ArrayList<>()).add(o);
            }
        }
        String indent = "\n" + "    ".repeat(depth);
        List<String> parts = new ArrayList<>();
        if (!types.isEmpty()) {
            parts.add("a " + String.join(" , ", types.stream().sorted().toList()));
        }
        byPredicate.forEach((p, os) -> parts.add(p + " " + String.join(" , ", os.stream().sorted().toList())));
        return parts.isEmpty() ? "" : " " + String.join(" ;" + indent, parts);
    }

    private String object(RDFNode n, int depth) {
        if (n.isLiteral()) {
            return literal(n.asLiteral());
        }
        if (n.isURIResource()) {
            return term(n.asResource());
        }
        Resource b = n.asResource();
        if (uses.getOrDefault(b, 0) != 1) {
            return "_:b" + Integer.toHexString(b.getId().getLabelString().hashCode());
        }
        if (b.equals(RDF.nil) || b.hasProperty(RDF.first) && b.canAs(RDFList.class)) {
            List<String> items = new ArrayList<>();
            for (RDFNode item : b.as(RDFList.class).asJavaList()) {
                items.add(object(item, depth + 1));
            }
            return "( " + String.join(" ", items) + " )";
        }
        String inner = predicates(b, depth + 1);
        return inner.isEmpty() ? "[]" : "[" + inner + "\n" + "    ".repeat(depth) + "]";
    }

    private String term(Resource r) {
        String iri = r.getURI();
        String s = prefixes.shortForm(iri);
        if (!s.equals(iri)) {
            String local = s.substring(s.indexOf(':') + 1);
            if (local.matches("[A-Za-z_][\\w\\-]*") || local.isEmpty()) {
                return s;
            }
        }
        return "<" + iri + ">";
    }

    private String literal(Literal l) {
        String lex = l.getLexicalForm().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
        if (!l.getLanguage().isEmpty()) {
            return "\"" + lex + "\"@" + l.getLanguage();
        }
        String dt = l.getDatatypeURI();
        if (dt == null || dt.equals(XSD.xstring.getURI())) {
            return "\"" + lex + "\"";
        }
        return "\"" + lex + "\"^^" + term(model.createResource(dt));
    }
}
