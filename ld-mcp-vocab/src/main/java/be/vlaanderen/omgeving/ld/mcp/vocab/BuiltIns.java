package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL2;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;

/**
 * The terms of RDF, RDFS, OWL 2 and XSD, which are known whether or not a loaded ontology mentions
 * them. Taken from Jena's vocabulary classes, so that the list is exact: {@code rdfs:label} is
 * known, {@code rdfs:lable} is not.
 */
final class BuiltIns {

    private static final Set<String> CLASSES = new HashSet<>();
    private static final Set<String> PROPERTIES = new HashSet<>();
    /** rdf:_1, rdf:_2, …: container membership properties. */
    private static final Pattern MEMBERSHIP = Pattern.compile(Pattern.quote(RDF.uri) + "_[1-9][0-9]*");

    static {
        for (Class<?> vocabulary : new Class<?>[] {RDF.class, RDFS.class, OWL2.class, XSD.class}) {
            for (Field f : vocabulary.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                try {
                    Object value = f.get(null);
                    if (value instanceof Property p && p.isURIResource()) {
                        PROPERTIES.add(p.getURI());
                    } else if (value instanceof Resource r && r.isURIResource()
                            && !r.getURI().equals(OWL2.getURI()) && !r.getURI().equals(RDF.uri)) {
                        CLASSES.add(r.getURI());
                    }
                } catch (IllegalAccessException e) {
                    // not a public constant
                }
            }
        }
    }

    private BuiltIns() {
    }

    /**
     * Tells whether an IRI is a term of RDF, RDFS, OWL 2 or XSD.
     *
     * @param iri a full IRI
     * @return whether it is built in
     */
    static boolean isBuiltIn(String iri) {
        return isBuiltInClass(iri) || isBuiltInProperty(iri);
    }

    /**
     * Tells whether an IRI is a built-in class, datatype or individual (e.g. {@code owl:Thing},
     * {@code xsd:string}, {@code rdf:nil}).
     *
     * @param iri a full IRI
     * @return whether it is one
     */
    static boolean isBuiltInClass(String iri) {
        return CLASSES.contains(iri);
    }

    /**
     * Tells whether an IRI is a built-in property (e.g. {@code rdfs:label}, {@code rdf:_3}).
     *
     * @param iri a full IRI
     * @return whether it is one
     */
    static boolean isBuiltInProperty(String iri) {
        return PROPERTIES.contains(iri) || MEMBERSHIP.matcher(iri).matches();
    }
}
