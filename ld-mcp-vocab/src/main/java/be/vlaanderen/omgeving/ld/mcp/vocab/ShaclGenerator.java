package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;

/**
 * Generates SHACL shapes from the OWL restrictions of an ontology: for every {@code owl:Class} a
 * NodeShape ({@code <class>Shape}) with one PropertyShape per path.
 * <ul>
 *   <li>{@code owl:someValuesFrom}/{@code owl:allValuesFrom} → {@code sh:class} or
 *       {@code sh:datatype}; an {@code owl:unionOf} → {@code sh:or};</li>
 *   <li>{@code owl:cardinality}/{@code owl:minCardinality}/{@code owl:maxCardinality} →
 *       {@code sh:minCount}/{@code sh:maxCount}; {@code owl:someValuesFrom} implies
 *       {@code sh:minCount 1};</li>
 *   <li>a restriction with {@code owl:minCardinality 0} on the same class and property cancels
 *       that implied {@code sh:minCount 1} (a profile that does not want to make an imported
 *       existential restriction mandatory);</li>
 *   <li>an anonymous {@code owl:inverseOf} property → {@code sh:inversePath}.</li>
 * </ul>
 * Behaves the same as {@code OwlToShaclGenerator} in the OSLOthema-SSNSOSA-VL pipeline.
 */
public final class ShaclGenerator {

    /** The SHACL namespace. */
    public static final String SH = "http://www.w3.org/ns/shacl#";

    /** Value constraint of one restriction: a single class/datatype, or a union of them. */
    private record ValueKind(List<Member> members, boolean union) {
    }

    private record Member(String uri, boolean datatype) {
    }

    private ShaclGenerator() {
    }

    /**
     * Generates the shapes for all named classes in the ontology.
     *
     * @param ontology the ontologies to read {@code owl:Class} declarations and restrictions from
     * @return a new model with the generated shapes
     */
    public static Model generate(Model ontology) {
        Model shacl = ModelFactory.createDefaultModel();
        shacl.setNsPrefix("sh", SH);
        shacl.setNsPrefix("owl", OWL.NS);
        shacl.setNsPrefix("rdfs", RDFS.getURI());
        ontology.listResourcesWithProperty(RDF.type, OWL.Class)
                .filterKeep(Resource::isURIResource)
                .forEachRemaining(cls -> nodeShape(cls, ontology, shacl));
        return shacl;
    }

    private static void nodeShape(Resource cls, Model ontology, Model shacl) {
        Resource ns = shacl.createResource(cls.getURI() + "Shape");
        ns.addProperty(RDF.type, shacl.createResource(SH + "NodeShape"));
        ns.addProperty(sh(shacl, "targetClass"), cls);

        List<Resource> restrictions = new ArrayList<>();
        ontology.listStatements(cls, RDFS.subClassOf, (RDFNode) null).forEachRemaining(st -> {
            if (st.getObject().isResource()) {
                Resource r = st.getResource();
                if (r.hasProperty(RDF.type, OWL.Restriction)
                        && r.getPropertyResourceValue(OWL.onProperty) != null) {
                    restrictions.add(r);
                }
            }
        });
        Set<String> relaxed = pathsWithMinCardinalityZero(restrictions);

        // Group by path, sorted by path key.
        Map<String, List<Resource>> byPath = new TreeMap<>();
        Map<String, Resource> onProps = new LinkedHashMap<>();
        for (Resource r : restrictions) {
            Resource onProp = r.getPropertyResourceValue(OWL.onProperty);
            pathKey(onProp).ifPresent(k -> {
                byPath.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
                onProps.putIfAbsent(k, onProp);
            });
        }
        byPath.forEach((k, group) -> propertyShape(onProps.get(k), group, shacl, ns, relaxed));
    }

    /**
     * One property shape per path: several sh:class/sh:or must all hold (intersection of the OWL
     * restrictions); sh:minCount is the largest minimum, sh:maxCount the smallest maximum.
     */
    private static void propertyShape(Resource onProp, List<Resource> restrictions, Model shacl,
                                      Resource nodeShape, Set<String> relaxed) {
        Resource ps = shacl.createResource();
        ps.addProperty(sh(shacl, "path"), path(onProp, shacl));

        Set<ValueKind> kinds = new LinkedHashSet<>();
        restrictions.forEach(r -> valueKind(r).ifPresent(kinds::add));
        for (ValueKind kind : kinds) {
            if (kind.union()) {
                List<RDFNode> shapes = new ArrayList<>();
                for (Member m : kind.members()) {
                    Resource alt = shacl.createResource();
                    classOrDatatype(alt, m, shacl);
                    shapes.add(alt);
                }
                ps.addProperty(sh(shacl, "or"), shacl.createList(shapes.iterator()));
            } else {
                classOrDatatype(ps, kind.members().get(0), shacl);
            }
        }

        restrictions.stream().map(r -> minCount(r, relaxed)).flatMap(Optional::stream)
                .max(Integer::compare)
                .ifPresent(n -> ps.addLiteral(sh(shacl, "minCount"), (long) n));
        restrictions.stream().map(ShaclGenerator::maxCount).flatMap(Optional::stream)
                .min(Integer::compare)
                .ifPresent(n -> ps.addLiteral(sh(shacl, "maxCount"), (long) n));

        nodeShape.addProperty(sh(shacl, "property"), ps);
    }

    // ---------------------------------------------------------------- helpers

    private static Set<String> pathsWithMinCardinalityZero(List<Resource> restrictions) {
        Set<String> result = new LinkedHashSet<>();
        for (Resource r : restrictions) {
            Resource onProp = r.getPropertyResourceValue(OWL.onProperty);
            Statement min = r.getProperty(OWL.minCardinality);
            boolean zero = min != null && min.getObject().isLiteral() && min.getInt() == 0;
            if (onProp.isURIResource() && zero) {
                result.add(onProp.getURI());
            }
        }
        return result;
    }

    private static Optional<ValueKind> valueKind(Resource restriction) {
        Resource v = Optional.ofNullable(restriction.getPropertyResourceValue(OWL.someValuesFrom))
                .orElse(restriction.getPropertyResourceValue(OWL.allValuesFrom));
        if (v == null) {
            return Optional.empty();
        }
        if (v.hasProperty(OWL.unionOf)) {
            List<Member> members = v.getPropertyResourceValue(OWL.unionOf).as(RDFList.class)
                    .asJavaList().stream()
                    .filter(RDFNode::isURIResource)
                    .map(n -> member(n.asResource()))
                    .sorted(Comparator.comparing(Member::uri))
                    .toList();
            return Optional.of(new ValueKind(members, true));
        }
        return v.isURIResource()
                ? Optional.of(new ValueKind(List.of(member(v)), false))
                : Optional.empty();
    }

    private static Member member(Resource r) {
        return new Member(r.getURI(), r.getURI().startsWith(XSD.NS));
    }

    private static Optional<Integer> intValue(Resource restriction, Property p) {
        Statement st = restriction.getProperty(p);
        return st != null && st.getObject().isLiteral()
                ? Optional.of(((Literal) st.getObject()).getInt())
                : Optional.empty();
    }

    private static Optional<Integer> minCount(Resource restriction, Set<String> relaxed) {
        Resource onProp = restriction.getPropertyResourceValue(OWL.onProperty);
        Optional<Integer> explicit = intValue(restriction, OWL.cardinality)
                .or(() -> intValue(restriction, OWL.minCardinality));
        Optional<Integer> implied = restriction.hasProperty(OWL.someValuesFrom)
                && !(onProp.isURIResource() && relaxed.contains(onProp.getURI()))
                ? Optional.of(1) : Optional.empty();
        // sh:minCount 0 says nothing.
        return explicit.or(() -> implied).filter(n -> n > 0);
    }

    private static Optional<Integer> maxCount(Resource restriction) {
        return intValue(restriction, OWL.cardinality)
                .or(() -> intValue(restriction, OWL.maxCardinality));
    }

    /** The property IRI, or "^IRI" for an anonymous owl:inverseOf property. */
    private static Optional<String> pathKey(Resource onProp) {
        if (onProp.isURIResource()) {
            return Optional.of(onProp.getURI());
        }
        return Optional.ofNullable(onProp.getPropertyResourceValue(OWL.inverseOf))
                .filter(Resource::isURIResource)
                .map(inv -> "^" + inv.getURI());
    }

    private static RDFNode path(Resource onProp, Model shacl) {
        if (onProp.isAnon()) {
            Resource inv = onProp.getPropertyResourceValue(OWL.inverseOf);
            Objects.requireNonNull(inv); // pathKey filters out anonymous properties without inverseOf
            Resource b = shacl.createResource();
            b.addProperty(sh(shacl, "inversePath"), inv);
            return b;
        }
        return shacl.createResource(onProp.getURI());
    }

    private static void classOrDatatype(Resource shape, Member m, Model shacl) {
        shape.addProperty(sh(shacl, m.datatype() ? "datatype" : "class"), shacl.createResource(m.uri()));
    }

    private static Property sh(Model m, String local) {
        return m.createProperty(SH + local);
    }
}
