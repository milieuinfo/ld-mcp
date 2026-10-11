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
 * Opzoeken van klassen en properties in een geladen set ontologieën. Geeft tekst terug die
 * bedoeld is voor een taalmodel: compact, met prefixvormen waar mogelijk.
 */
public final class VocabIndex {

    private static final Property DCT_DESCRIPTION =
            ResourceFactory.createProperty("http://purl.org/dc/terms/description");

    /** Typen die een resource tot "term" maken (klasse of property). */
    private static final Set<Resource> TERM_TYPES = Set.of(
            RDFS.Class, OWL.Class, RDF.Property,
            OWL.ObjectProperty, OWL.DatatypeProperty, OWL.AnnotationProperty);

    private final Model model;
    /** Alle termen, gesorteerd op IRI, éénmaal berekend. */
    private final List<Resource> terms;

    public VocabIndex(Model model) {
        this.model = model;
        Set<Resource> found = new TreeSet<>(Comparator.comparing(Resource::getURI));
        for (Resource type : TERM_TYPES) {
            model.listSubjectsWithProperty(RDF.type, type)
                    .filterKeep(Resource::isURIResource)
                    .forEachRemaining(found::add);
        }
        // Ook termen die enkel via subClassOf/subPropertyOf/domain/range gedeclareerd zijn.
        for (Property p : List.of(RDFS.subClassOf, RDFS.subPropertyOf, RDFS.domain, RDFS.range)) {
            model.listSubjectsWithProperty(p)
                    .filterKeep(Resource::isURIResource)
                    .forEachRemaining(found::add);
        }
        this.terms = List.copyOf(found);
    }

    public int size() {
        return terms.size();
    }

    /** Beschrijving van één term, of een "niet gevonden"-melding met suggesties. */
    public String lookup(String term) {
        String iri = expand(term);
        Resource r = model.createResource(iri);
        if (!model.containsResource(r) || !model.listStatements(r, null, (RDFNode) null).hasNext()) {
            return notFound(term, iri);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(shortForm(iri)).append("  <").append(iri).append(">\n");
        section(sb, "type", objects(r, RDF.type));
        section(sb, "label", literals(r, RDFS.label, SKOS.prefLabel));
        section(sb, "definitie", literals(r, SKOS.definition, RDFS.comment, DCT_DESCRIPTION));
        section(sb, "subClassOf", objects(r, RDFS.subClassOf));
        section(sb, "subPropertyOf", objects(r, RDFS.subPropertyOf));
        section(sb, "domain", objects(r, RDFS.domain));
        section(sb, "range", objects(r, RDFS.range));
        section(sb, "inverseOf", objects(r, OWL.inverseOf));
        section(sb, "equivalent", objects(r, OWL.equivalentClass, OWL.equivalentProperty));
        section(sb, "disjointWith", objects(r, OWL.disjointWith));
        section(sb, "isDefinedBy", objects(r, RDFS.isDefinedBy));
        section(sb, "subklassen", subjects(RDFS.subClassOf, r));
        section(sb, "subproperties", subjects(RDFS.subPropertyOf, r));
        return sb.toString();
    }

    /** Termen waarvan lokale naam, label of IRI de zoekterm bevat (hoofdletterongevoelig). */
    public String search(String query, int limit) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Resource> hits = terms.stream()
                .filter(t -> matches(t, q))
                .sorted(Comparator.comparingInt((Resource t) -> rank(t, q))
                        .thenComparing(Resource::getURI))
                .toList();
        if (hits.isEmpty()) {
            return "Geen termen gevonden voor '" + query + "'.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(hits.size()).append(" treffer(s)");
        if (hits.size() > limit) {
            sb.append(", eerste ").append(limit).append(" getoond");
        }
        sb.append(":\n");
        hits.stream().limit(limit).forEach(t -> sb.append("- ")
                .append(shortForm(t.getURI()))
                .append("  [").append(String.join(", ", objects(t, RDF.type))).append("]")
                .append(labelSuffix(t))
                .append('\n'));
        return sb.toString();
    }

    // ---------------------------------------------------------------- hulpfuncties

    private String notFound(String term, String iri) {
        String local = localName(iri).toLowerCase(Locale.ROOT);
        List<String> sameLocal = terms.stream()
                .filter(t -> localName(t.getURI()).equalsIgnoreCase(local))
                .map(t -> shortForm(t.getURI()))
                .toList();
        StringBuilder sb = new StringBuilder("Niet gevonden: ").append(term);
        if (!iri.equals(term)) {
            sb.append("  <").append(iri).append(">");
        }
        sb.append('\n');
        if (!sameLocal.isEmpty()) {
            sb.append("Zelfde lokale naam in een andere namespace: ")
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
                sb.append("Gelijkaardige termen: ").append(String.join(", ", similar)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Zet een prefixvorm (sosa:Observation) om naar een volledige IRI; IRI's blijven ongewijzigd. */
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
                throw new IllegalArgumentException("Onbekend prefix '" + t.substring(0, colon)
                        + "'. Gekende prefixen: " + String.join(", ", new TreeSet<>(model.getNsPrefixMap().keySet())));
            }
            return ns + t.substring(colon + 1);
        }
        throw new IllegalArgumentException("Geef een prefixvorm (sosa:Observation) of een volledige IRI.");
    }

    private boolean matches(Resource t, String q) {
        return localName(t.getURI()).toLowerCase(Locale.ROOT).contains(q)
                || literals(t, RDFS.label, SKOS.prefLabel).stream()
                        .anyMatch(l -> l.toLowerCase(Locale.ROOT).contains(q));
    }

    /** Exacte lokale naam eerst, dan begint-met, dan de rest. */
    private int rank(Resource t, String q) {
        String local = localName(t.getURI()).toLowerCase(Locale.ROOT);
        if (local.equals(q)) {
            return 0;
        }
        return local.startsWith(q) ? 1 : 2;
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

    /** Literals, Nederlands en Engels eerst. */
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

    /** OWL-restricties en -lijsten kort weergeven in plaats van een blank-node-id. */
    private String describeBlank(Resource b) {
        if (b.hasProperty(OWL.onProperty)) {
            String p = render(b.getProperty(OWL.onProperty).getObject());
            for (Property kind : List.of(OWL.someValuesFrom, OWL.allValuesFrom, OWL.hasValue,
                    OWL.cardinality, OWL.minCardinality, OWL.maxCardinality,
                    OWL2_QUALIFIED_CARDINALITY, OWL2_MIN_QUALIFIED, OWL2_MAX_QUALIFIED)) {
                if (b.hasProperty(kind)) {
                    return "restrictie " + p + " " + kind.getLocalName() + " "
                            + render(b.getProperty(kind).getObject());
                }
            }
            return "restrictie op " + p;
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
