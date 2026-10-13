package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.apache.jena.atlas.RuntimeIOException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.riot.RiotException;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import be.vlaanderen.omgeving.ld.mcp.core.JsonLd;

/**
 * A register of known alignment modules between vocabularies, such as
 * {@code registers/alignments.ttl} of oslo-ld-kit. An alignment can be published by neither of the
 * vocabularies it connects (e.g. the SOSA–SAREF alignment is a separate module of SSN/SOSA 2023),
 * so it cannot be found from the vocabularies themselves; the register records the ones found.
 *
 * <p>Each module is an {@code owl:Ontology} with {@code dcterms:references} to the namespaces it
 * connects, and optionally {@code dcterms:title}, {@code owl:versionIRI}, {@code dcterms:source},
 * {@code rdfs:seeAlso} and {@code rdfs:comment} (the status).
 */
public final class AlignmentRegister {

    private static final String DCT = "http://purl.org/dc/terms/";
    private static final Property REFERENCES = ResourceFactory.createProperty(DCT + "references");
    private static final Property TITLE = ResourceFactory.createProperty(DCT + "title");
    private static final Property SOURCE = ResourceFactory.createProperty(DCT + "source");

    /**
     * One alignment module.
     *
     * @param iri        the IRI of the module (its {@code owl:Ontology})
     * @param title      its title, or an empty string
     * @param versionIri its {@code owl:versionIRI}, or an empty string
     * @param namespaces the namespaces it connects
     * @param source     where its RDF is, or an empty string
     * @param seeAlso    its documentation, or an empty string
     * @param comment    its status and notes, or an empty string
     */
    public record Entry(String iri, String title, String versionIri, List<String> namespaces,
                        String source, String seeAlso, String comment) {
    }

    private final List<Entry> entries;

    private AlignmentRegister(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Reads a register.
     *
     * @param file RDF file; the syntax is derived from the extension
     * @return the register
     * @throws IllegalArgumentException if the file does not exist or cannot be parsed
     */
    public static AlignmentRegister load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Alignment register not found: " + file.toAbsolutePath());
        }
        Model m = ModelFactory.createDefaultModel();
        try {
            JsonLd.parser(file).parse(m);
        } catch (RiotException | RuntimeIOException e) {
            throw new IllegalArgumentException("Cannot read alignment register " + file + ": " + e.getMessage(), e);
        }
        return fromModel(m);
    }

    /**
     * Builds a register from a model.
     *
     * @param m the register as RDF
     * @return the register
     */
    public static AlignmentRegister fromModel(Model m) {
        List<Entry> list = new ArrayList<>();
        for (Resource r : m.listSubjectsWithProperty(RDF.type, OWL.Ontology).toList()) {
            if (!r.isURIResource() || !r.hasProperty(REFERENCES)) {
                continue;
            }
            List<String> ns = m.listObjectsOfProperty(r, REFERENCES).toList().stream()
                    .filter(RDFNode::isURIResource).map(n -> n.asResource().getURI()).sorted().toList();
            list.add(new Entry(r.getURI(), text(m, r, TITLE), text(m, r, OWL.versionIRI), ns,
                    text(m, r, SOURCE), text(m, r, RDFS.seeAlso), text(m, r, RDFS.comment)));
        }
        list.sort(Comparator.comparing(Entry::iri));
        return new AlignmentRegister(list);
    }

    /**
     * Returns the modules that connect a namespace: one of their namespaces is the same namespace,
     * with or without a trailing {@code /} or {@code #}. Not a prefix match: a short namespace such
     * as {@code http://www.w3.org/ns/} would match every W3C vocabulary.
     *
     * @param namespace the namespace of a vocabulary
     * @return the matching modules, sorted by IRI
     */
    public List<Entry> matching(String namespace) {
        return entries.stream()
                .filter(e -> e.namespaces().stream().anyMatch(n -> sameNamespace(n, namespace)))
                .toList();
    }

    /**
     * Whether two namespace IRIs are the same, with or without a trailing {@code /} or {@code #}.
     *
     * @param a a namespace
     * @param b a namespace
     * @return whether they name the same vocabulary
     */
    static boolean sameNamespace(String a, String b) {
        return bare(a).equals(bare(b));
    }

    private static String bare(String ns) {
        return ns.endsWith("/") || ns.endsWith("#") ? ns.substring(0, ns.length() - 1) : ns;
    }

    /**
     * Returns the number of modules.
     *
     * @return the number of entries
     */
    public int size() {
        return entries.size();
    }

    private static String text(Model m, Resource r, Property p) {
        Optional<RDFNode> o = m.listObjectsOfProperty(r, p).toList().stream().findFirst();
        if (o.isEmpty()) {
            return "";
        }
        return o.get().isURIResource() ? o.get().asResource().getURI() : o.get().asLiteral().getLexicalForm();
    }
}
