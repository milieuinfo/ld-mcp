package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.atlas.RuntimeIOException;
import org.apache.jena.riot.RiotException;
import org.apache.jena.riot.system.StreamRDFBase;

import be.vlaanderen.omgeving.ld.mcp.core.JsonLd;

/**
 * A registry of well-known namespaces, read from the prefix declarations of an RDF file, such as
 * <a href="http://prefix.cc/popular/all.file.ttl">prefix.cc/popular/all.file.ttl</a>. Any RDF
 * syntax that Jena reads will do (Turtle, a JSON-LD {@code @context}, …); triples are ignored.
 * It tells a namespace that exists but is not loaded apart from one that is not known at all.
 */
public final class PrefixRegistry {

    /** Namespace → prefixes, in declaration order (on prefix.cc: most popular first). */
    private final Map<String, List<String>> prefixes = new HashMap<>();

    private PrefixRegistry() {
    }

    /**
     * Reads the prefix declarations of an RDF file.
     *
     * @param file RDF file; the syntax is derived from the extension
     * @return the registry
     * @throws IllegalArgumentException if the file cannot be read or parsed
     */
    public static PrefixRegistry load(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Prefix registry not found: " + file.toAbsolutePath());
        }
        PrefixRegistry registry = new PrefixRegistry();
        try {
            JsonLd.parser(file).parse(new StreamRDFBase() {
                @Override
                public void prefix(String prefix, String iri) {
                    List<String> names = registry.prefixes.computeIfAbsent(iri, k -> new ArrayList<>());
                    if (!names.contains(prefix)) {
                        names.add(prefix);
                    }
                }
            });
        } catch (RiotException | RuntimeIOException e) {
            throw new IllegalArgumentException("Cannot read prefix registry " + file + ": " + e.getMessage(), e);
        }
        return registry;
    }

    /**
     * Returns the prefixes registered for a namespace.
     *
     * @param namespace the namespace, exactly as registered (including the trailing {@code #} or
     *                  {@code /})
     * @return the prefixes in declaration order; empty if the namespace is unknown
     */
    public List<String> prefixes(String namespace) {
        return prefixes.getOrDefault(namespace, List.of());
    }

    /**
     * Returns the number of registered namespaces.
     *
     * @return the number of distinct namespaces in the registry
     */
    public int size() {
        return prefixes.size();
    }
}
