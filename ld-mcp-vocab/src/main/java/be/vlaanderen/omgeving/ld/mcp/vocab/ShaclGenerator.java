package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;

/**
 * Generates SHACL shapes from the OWL restrictions of an ontology. This is a <em>profile
 * reading</em>: restrictions are read as requirements on the data (closed world), which OWL itself
 * never makes them (an {@code owl:someValuesFrom} without a value is no error in OWL). For every
 * named class ({@code owl:Class} or {@code rdfs:Class}) with at least one translatable restriction,
 * a NodeShape with property shapes:
 * <ul>
 *   <li>restrictions on {@code rdfs:subClassOf}, and the members of an {@code owl:intersectionOf} on
 *       {@code rdfs:subClassOf} or {@code owl:equivalentClass};</li>
 *   <li>{@code owl:allValuesFrom V} → {@code sh:class}, {@code sh:datatype}, {@code sh:nodeKind
 *       sh:Literal} (for {@code rdfs:Literal}) or {@code sh:or} (for an {@code owl:unionOf}) on every
 *       value; nothing for {@code owl:Thing} or {@code rdfs:Resource};</li>
 *   <li>{@code owl:someValuesFrom V} → at least one value of kind V: {@code sh:qualifiedValueShape}
 *       with {@code sh:qualifiedMinCount 1}, in a property shape of its own (or just
 *       {@code sh:minCount 1} for {@code owl:Thing});</li>
 *   <li>{@code owl:(min|max)QualifiedCardinality} and {@code owl:qualifiedCardinality} with
 *       {@code owl:onClass} or {@code owl:onDataRange} → {@code sh:qualifiedValueShape} with
 *       {@code sh:qualifiedMinCount}/{@code sh:qualifiedMaxCount};</li>
 *   <li>{@code owl:cardinality}/{@code minCardinality}/{@code maxCardinality} →
 *       {@code sh:minCount}/{@code sh:maxCount}; several on one path are merged (largest minimum,
 *       smallest maximum);</li>
 *   <li>{@code owl:hasValue} → {@code sh:hasValue};</li>
 *   <li>an anonymous property with {@code owl:inverseOf} → {@code sh:inversePath};</li>
 *   <li>convention (not OWL semantics): {@code owl:minCardinality 0} on a class and property cancels
 *       an {@code owl:someValuesFrom} on the same class and property, so that a profile can keep an
 *       existential restriction in the ontology without making it mandatory in the data.</li>
 * </ul>
 * What cannot be translated (a union with an anonymous member that is no union itself,
 * {@code owl:complementOf}, {@code owl:oneOf}, property chains, …) is skipped, never translated
 * into something stricter, and listed in {@link Result#notTranslated()}.
 */
public final class ShaclGenerator {

    /** The SHACL namespace. */
    public static final String SH = "http://www.w3.org/ns/shacl#";

    /** The namespace of the generated node shapes: their own, so that they never collide with terms. */
    public static final String SHAPE_NS = "urn:x-ld-mcp:shape:";

    /**
     * The generated shapes and what could not be translated.
     *
     * @param shapes         the shapes
     * @param nodeShapes     the number of node shapes
     * @param propertyShapes the number of property shapes
     * @param notTranslated  one line per restriction or class expression that was skipped, sorted
     */
    public record Result(Model shapes, int nodeShapes, int propertyShapes, List<String> notTranslated) {

        /**
         * Copies the list.
         *
         * @param shapes         the shapes
         * @param nodeShapes     the number of node shapes
         * @param propertyShapes the number of property shapes
         * @param notTranslated  what was skipped
         */
        public Result {
            notTranslated = List.copyOf(notTranslated);
        }
    }

    /** The datatypes of RDF that are not in the XSD namespace. */
    private static final Set<String> RDF_DATATYPES = Set.of(RDF.langString.getURI(), RDF.HTML.getURI(),
            RDF.xmlLiteral.getURI(), RDF.uri + "JSON", RDF.dirLangString.getURI());

    private ShaclGenerator() {
    }

    /**
     * Generates the shapes for all named classes in the ontology.
     *
     * @param ontology the ontologies to read class declarations and restrictions from
     * @return a new model with the generated shapes
     */
    public static Model generate(Model ontology) {
        return translate(ontology).shapes();
    }

    /**
     * Generates the shapes and reports what could not be translated.
     *
     * @param ontology the ontologies to read class declarations and restrictions from
     * @return the shapes and the report
     */
    public static Result translate(Model ontology) {
        Translation t = new Translation(ontology);
        Set<Resource> classes = new TreeSet<>(Comparator.comparing(Resource::getURI));
        for (Resource type : List.of(OWL.Class, RDFS.Class)) {
            ontology.listResourcesWithProperty(RDF.type, type).filterKeep(Resource::isURIResource)
                    .forEachRemaining(classes::add);
        }
        classes.forEach(t::nodeShape);
        return new Result(t.shacl, t.nodeShapes, t.propertyShapes,
                t.notTranslated.stream().sorted().distinct().toList());
    }

    /**
     * The IRI of the node shape of a class: in {@link #SHAPE_NS}, with the class IRI encoded.
     *
     * @param classIri the IRI of the class
     * @return the IRI of its generated node shape
     */
    static String shapeIri(String classIri) {
        return SHAPE_NS + URLEncoder.encode(classIri, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- value constraints

    /** What a value must be. */
    private sealed interface Value permits Value.Class, Value.Datatype, Value.AnyLiteral, Value.Or, Value.Anything {

        /** An instance of a class. */
        record Class(Resource uri) implements Value {
        }

        /** A literal of a datatype. */
        record Datatype(Resource uri) implements Value {
        }

        /** Any literal ({@code rdfs:Literal}). */
        record AnyLiteral() implements Value {
        }

        /** One of several. */
        record Or(List<Value> members) implements Value {
        }

        /** Anything ({@code owl:Thing}, {@code rdfs:Resource}): no constraint. */
        record Anything() implements Value {
        }
    }

    /** The restrictions of one class on one path. */
    private static final class PathGroup {
        final Resource onProperty;
        // Sets: the same restriction often occurs more than once (repeated in a file, or in two
        // files that both define the class), and must not give the same constraint twice.
        final Set<Value> allValues = new LinkedHashSet<>();
        final Set<Value> someValues = new LinkedHashSet<>();
        final Set<Qualified> qualified = new LinkedHashSet<>();
        final Set<RDFNode> hasValues = new LinkedHashSet<>();
        Integer min;
        Integer max;
        boolean relaxed;

        PathGroup(Resource onProperty) {
            this.onProperty = onProperty;
        }

        void min(Integer n) {
            if (n != null && n > 0) {
                min = min == null ? n : Math.max(min, n);
            }
        }

        void max(Integer n) {
            if (n != null) {
                max = max == null ? n : Math.min(max, n);
            }
        }
    }

    /** A qualified cardinality: how many values of a kind there must be. */
    private record Qualified(Value value, Integer min, Integer max) {
    }

    // ---------------------------------------------------------------- translation of one ontology

    private static final class Translation {

        final Model ontology;
        final Model shacl = ModelFactory.createDefaultModel();
        final List<String> notTranslated = new ArrayList<>();
        int nodeShapes;
        int propertyShapes;

        Translation(Model ontology) {
            this.ontology = ontology;
            shacl.setNsPrefix("sh", SH);
            shacl.setNsPrefix("owl", OWL.NS);
            shacl.setNsPrefix("rdfs", RDFS.getURI());
            shacl.setNsPrefix("xsd", XSD.NS);
        }

        void nodeShape(Resource cls) {
            List<Resource> restrictions = new ArrayList<>();
            ontology.listObjectsOfProperty(cls, RDFS.subClassOf)
                    .forEachRemaining(o -> collect(cls, o, restrictions, "superclass"));
            ontology.listObjectsOfProperty(cls, OWL.equivalentClass)
                    .forEachRemaining(o -> collect(cls, o, restrictions, "equivalent class"));

            Map<String, PathGroup> groups = new TreeMap<>();
            for (Resource r : restrictions) {
                Resource onProp = r.getPropertyResourceValue(OWL.onProperty);
                Optional<String> key = pathKey(onProp);
                if (key.isEmpty()) {
                    skip(cls, "restriction on " + describe(onProp)
                            + " (only a named property or an anonymous owl:inverseOf is translated)");
                    continue;
                }
                read(cls, r, groups.computeIfAbsent(key.get(), k -> new PathGroup(onProp)));
            }

            List<Resource> shapes = new ArrayList<>();
            groups.values().forEach(g -> shapes.addAll(propertyShapes(cls, g)));
            if (shapes.isEmpty()) {
                return; // a node shape without constraints would only inflate the counts
            }
            Resource ns = shacl.createResource(shapeIri(cls.getURI()));
            ns.addProperty(RDF.type, shacl.createResource(SH + "NodeShape"));
            ns.addProperty(sh("targetClass"), cls);
            shapes.forEach(ps -> ns.addProperty(sh("property"), ps));
            nodeShapes++;
            propertyShapes += shapes.size();
        }

        /**
         * Collects the restrictions in a class expression: a restriction itself, or the members of an
         * intersection. A named class is a superclass or an equivalent (nothing to collect); any other
         * expression is reported.
         */
        void collect(Resource cls, RDFNode expr, List<Resource> out, String role) {
            if (!expr.isResource() || expr.isURIResource()) {
                return;
            }
            Resource r = expr.asResource();
            if (r.hasProperty(OWL.onProperty)) {
                out.add(r);
            } else if (r.hasProperty(OWL.intersectionOf)) {
                list(r.getPropertyResourceValue(OWL.intersectionOf)).forEach(m -> collect(cls, m, out, role));
            } else {
                skip(cls, role + " " + describe(r));
            }
        }

        /** Reads one restriction into the group of its path. */
        void read(Resource cls, Resource r, PathGroup g) {
            boolean understood = false;
            Resource all = r.getPropertyResourceValue(OWL.allValuesFrom);
            if (all != null) {
                understood = true;
                Value v = value(all);
                if (v == null) {
                    skip(cls, "owl:allValuesFrom " + describe(all) + " on " + describe(g.onProperty));
                } else if (!(v instanceof Value.Anything)) {
                    g.allValues.add(v);
                }
            }
            Resource some = r.getPropertyResourceValue(OWL.someValuesFrom);
            if (some != null) {
                understood = true;
                Value v = value(some);
                if (v == null) {
                    skip(cls, "owl:someValuesFrom " + describe(some) + " on " + describe(g.onProperty));
                } else {
                    g.someValues.add(v);
                }
            }
            Integer exact = integer(r, OWL.cardinality);
            Integer min = integer(r, OWL.minCardinality);
            Integer max = integer(r, OWL.maxCardinality);
            if (exact != null || min != null || max != null) {
                understood = true;
                if (min != null && min == 0) {
                    g.relaxed = true;
                }
                g.min(exact != null ? exact : min);
                g.max(exact != null ? exact : max);
            }
            Integer qExact = integer(r, OWL2.qualifiedCardinality);
            Integer qMin = integer(r, OWL2.minQualifiedCardinality);
            Integer qMax = integer(r, OWL2.maxQualifiedCardinality);
            if (qExact != null || qMin != null || qMax != null) {
                understood = true;
                Resource on = Optional.ofNullable(r.getPropertyResourceValue(OWL2.onClass))
                        .orElse(r.getPropertyResourceValue(OWL2.onDataRange));
                Value v = on == null ? new Value.Anything() : value(on);
                Integer lo = qExact != null ? qExact : qMin;
                Integer hi = qExact != null ? qExact : qMax;
                if (v == null) {
                    skip(cls, "qualified cardinality on " + describe(g.onProperty) + " with " + describe(on));
                } else if (v instanceof Value.Anything) {
                    g.min(lo);
                    g.max(hi);
                } else if ((lo != null && lo > 0) || hi != null) {
                    g.qualified.add(new Qualified(v, lo != null && lo > 0 ? lo : null, hi));
                }
            }
            if (r.hasProperty(OWL.hasValue)) {
                understood = true;
                g.hasValues.add(r.getProperty(OWL.hasValue).getObject());
            }
            if (!understood) {
                skip(cls, "restriction on " + describe(g.onProperty) + " without a known constraint");
            }
        }

        /** The property shapes of one path: one for the plain constraints, one per qualified one. */
        List<Resource> propertyShapes(Resource cls, PathGroup g) {
            PathGroup plain = g;
            Set<Qualified> qualified = new LinkedHashSet<>(g.qualified);
            if (!g.relaxed) {
                for (Value v : g.someValues) {
                    if (v instanceof Value.Anything) {
                        plain.min(1);
                    } else {
                        qualified.add(new Qualified(v, 1, null));
                    }
                }
            }
            List<Resource> out = new ArrayList<>();
            if (!g.allValues.isEmpty() || g.min != null || g.max != null || !g.hasValues.isEmpty()) {
                Resource ps = propertyShape(g.onProperty);
                g.allValues.forEach(v -> constrain(ps, v));
                if (g.min != null) {
                    ps.addLiteral(sh("minCount"), (long) g.min);
                }
                if (g.max != null) {
                    ps.addLiteral(sh("maxCount"), (long) g.max);
                }
                g.hasValues.forEach(v -> ps.addProperty(sh("hasValue"), v));
                List<String> rules = new ArrayList<>();
                g.allValues.forEach(v -> rules.add("every value " + says(v)));
                if (g.min != null) {
                    rules.add("at least " + g.min + " value(s)");
                }
                if (g.max != null) {
                    rules.add("at most " + g.max + " value(s)");
                }
                g.hasValues.forEach(v -> rules.add("the value " + show(v)));
                message(ps, cls, g.onProperty, rules);
                out.add(ps);
            }
            for (Qualified q : qualified) {
                Resource ps = propertyShape(g.onProperty);
                Resource inner = shacl.createResource();
                constrain(inner, q.value());
                ps.addProperty(sh("qualifiedValueShape"), inner);
                if (q.min() != null) {
                    ps.addLiteral(sh("qualifiedMinCount"), (long) q.min());
                }
                if (q.max() != null) {
                    ps.addLiteral(sh("qualifiedMaxCount"), (long) q.max());
                }
                List<String> rules = new ArrayList<>();
                if (q.min() != null) {
                    rules.add("at least " + q.min() + " value(s) that " + says(q.value()));
                }
                if (q.max() != null) {
                    rules.add("at most " + q.max() + " value(s) that " + says(q.value()));
                }
                message(ps, cls, g.onProperty, rules);
                out.add(ps);
            }
            return out;
        }

        /** A readable message: Jena's own texts ("QualifiedValueShape[1,_,false] …") say little. */
        void message(Resource ps, Resource cls, Resource onProp, List<String> rules) {
            String path = onProp.isAnon() ? "^" + show(onProp.getPropertyResourceValue(OWL.inverseOf)) : show(onProp);
            ps.addProperty(sh("message"), show(cls) + ": " + path + " needs " + String.join("; ", rules)
                    + " (generated from the OWL restrictions)");
        }

        /** What a value constraint asks, as a verb phrase: "is a sosa:Sensor". */
        String says(Value v) {
            return switch (v) {
                case Value.Class c -> "is a " + show(c.uri());
                case Value.Datatype d -> "is a literal of " + show(d.uri());
                case Value.AnyLiteral l -> "is a literal";
                case Value.Or or -> "is one of (" + String.join(", ", or.members().stream()
                        .map(m -> says(m).replaceFirst("^is (a )?", "")).toList()) + ")";
                case Value.Anything a -> "exists";
            };
        }

        Resource propertyShape(Resource onProp) {
            Resource ps = shacl.createResource();
            ps.addProperty(sh("path"), path(onProp));
            return ps;
        }

        /** Translates a class expression or data range, or returns {@code null} if it cannot. */
        Value value(Resource v) {
            if (v.isURIResource()) {
                if (v.equals(OWL.Thing) || v.equals(RDFS.Resource)) {
                    return new Value.Anything();
                }
                if (v.equals(RDFS.Literal)) {
                    return new Value.AnyLiteral();
                }
                return isDatatype(v, ontology) ? new Value.Datatype(v) : new Value.Class(v);
            }
            if (v.hasProperty(OWL.unionOf)) {
                List<RDFNode> members = list(v.getPropertyResourceValue(OWL.unionOf));
                if (members.isEmpty()) {
                    return null; // an empty union admits nothing; never translated as sh:or ()
                }
                List<Value> values = new ArrayList<>();
                for (RDFNode m : members) {
                    Value mv = m.isResource() ? value(m.asResource()) : null;
                    if (mv == null) {
                        return null; // dropping a member would make the sh:or stricter
                    }
                    if (mv instanceof Value.Anything) {
                        return mv;
                    }
                    values.add(mv);
                }
                values.sort(Comparator.comparing(ShaclGenerator::sortKey));
                return values.size() == 1 ? values.get(0) : new Value.Or(values);
            }
            return null;
        }

        void constrain(Resource shape, Value v) {
            switch (v) {
                case Value.Class c -> shape.addProperty(sh("class"), c.uri());
                case Value.Datatype d -> shape.addProperty(sh("datatype"), d.uri());
                case Value.AnyLiteral l -> shape.addProperty(sh("nodeKind"), shacl.createResource(SH + "Literal"));
                case Value.Or or -> {
                    List<RDFNode> alternatives = new ArrayList<>();
                    for (Value m : or.members()) {
                        Resource alt = shacl.createResource();
                        constrain(alt, m);
                        alternatives.add(alt);
                    }
                    shape.addProperty(sh("or"), shacl.createList(alternatives.iterator()));
                }
                case Value.Anything a -> {
                    // nothing to check
                }
            }
        }

        RDFNode path(Resource onProp) {
            if (onProp.isAnon()) {
                Resource b = shacl.createResource();
                b.addProperty(sh("inversePath"), onProp.getPropertyResourceValue(OWL.inverseOf));
                return b;
            }
            return onProp;
        }

        void skip(Resource cls, String what) {
            notTranslated.add(show(cls) + ": " + what);
        }

        String show(RDFNode n) {
            if (n.isURIResource()) {
                String iri = n.asResource().getURI();
                String s = ontology.shortForm(iri);
                return s.equals(iri) ? "<" + iri + ">" : s;
            }
            return n.isLiteral() ? n.asLiteral().getLexicalForm() : describe(n.asResource());
        }

        /** A short description of a (possibly anonymous) class expression, by its defining property. */
        String describe(Resource r) {
            if (r == null) {
                return "nothing";
            }
            if (r.isURIResource()) {
                return show(r);
            }
            for (Property p : List.of(OWL.unionOf, OWL.intersectionOf, OWL.complementOf, OWL.oneOf,
                    OWL2.propertyChainAxiom, OWL.onProperty, OWL2.onDatatype, OWL.inverseOf)) {
                Statement st = r.getProperty(p);
                if (st == null) {
                    continue;
                }
                String name = "owl:" + p.getLocalName();
                RDFNode o = st.getObject();
                if (o.isResource() && o.isAnon() && o.asResource().canAs(RDFList.class)) {
                    List<String> members = list(o.asResource()).stream()
                            .map(m -> m.isAnon() ? "[…]" : show(m)).toList();
                    return name + "(" + String.join(", ", members) + ")";
                }
                return name + " " + (o.isAnon() ? "[…]" : show(o));
            }
            return "[anonymous class expression]";
        }

        Property sh(String local) {
            return shacl.createProperty(SH + local);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String sortKey(Value v) {
        return switch (v) {
            case Value.Class c -> c.uri().getURI();
            case Value.Datatype d -> d.uri().getURI();
            case Value.AnyLiteral l -> "~literal";
            case Value.Or or -> "~or";
            case Value.Anything a -> "~anything";
        };
    }

    private static List<RDFNode> list(Resource head) {
        if (head == null || !head.canAs(RDFList.class)) {
            return List.of();
        }
        return head.as(RDFList.class).asJavaList();
    }

    private static Integer integer(Resource r, Property p) {
        Statement st = r.getProperty(p);
        if (st == null || !st.getObject().isLiteral()) {
            return null;
        }
        try {
            return ((Literal) st.getObject()).getInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The property IRI, or "^IRI" for an anonymous owl:inverseOf property; empty otherwise. */
    private static Optional<String> pathKey(Resource onProp) {
        if (onProp == null) {
            return Optional.empty();
        }
        if (onProp.isURIResource()) {
            return Optional.of(onProp.getURI());
        }
        return Optional.ofNullable(onProp.getPropertyResourceValue(OWL.inverseOf))
                .filter(Resource::isURIResource)
                .map(inv -> "^" + inv.getURI());
    }

    /**
     * Whether a value is a datatype: in the XSD namespace, one of RDF's datatypes, or declared
     * {@code rdfs:Datatype} in the ontologies (e.g. {@code geo:wktLiteral}).
     *
     * @param r        the value of a restriction
     * @param ontology the ontologies
     * @return whether it is a datatype
     */
    static boolean isDatatype(Resource r, Model ontology) {
        String uri = r.getURI();
        return uri.startsWith(XSD.NS) || RDF_DATATYPES.contains(uri)
                || ontology != null && ontology.contains(r, RDF.type, RDFS.Datatype);
    }
}
