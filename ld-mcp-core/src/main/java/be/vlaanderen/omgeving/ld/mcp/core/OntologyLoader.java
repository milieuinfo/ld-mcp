package be.vlaanderen.omgeving.ld.mcp.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads RDF files and directories (recursively) into a single Jena model. Files that fail to
 * parse are logged and skipped, so that one faulty file does not prevent the server from starting.
 */
public final class OntologyLoader {

    private static final Logger LOG = LoggerFactory.getLogger(OntologyLoader.class);

    /**
     * Extensions that are loaded as ontologies. Not .xml (often configuration, e.g. logback.xml)
     * and not .trig/.nq (datasets).
     */
    private static final Set<String> SUPPORTED =
            Set.of(".ttl", ".rdf", ".owl", ".nt", ".n3", ".jsonld");

    private OntologyLoader() {
    }

    /**
     * Loads all supported RDF files under the given paths into one model.
     *
     * @param paths files and/or directories; directories are walked recursively
     * @return a new model with the triples of all files that parsed successfully, and the first
     *         declaration of every non-empty namespace prefix
     */
    public static Model load(List<Path> paths) {
        return load(paths, Set.of());
    }

    /**
     * Loads all supported RDF files under the given paths into one model, except the excluded ones.
     *
     * @param paths   files and/or directories; directories are walked recursively
     * @param exclude files to skip even if they lie under {@code paths} (e.g. a prefix registry)
     * @return a new model with the triples of all files that parsed successfully, and the first
     *         declaration of every non-empty namespace prefix
     */
    public static Model load(List<Path> paths, Set<Path> exclude) {
        Model model = ModelFactory.createDefaultModel();
        files(paths, exclude).forEach(file -> read(model, file));
        LOG.info("{} triples loaded from {}", model.size(), paths);
        return model;
    }

    /**
     * Lists the files that {@link #load(List)} would read, in the same order. Paths that do not
     * exist are logged and ignored.
     *
     * @param paths files and/or directories; directories are walked recursively
     * @return the supported RDF files, sorted per directory
     */
    public static List<Path> files(List<Path> paths) {
        return files(paths, Set.of());
    }

    /**
     * Lists the files that {@link #load(List, Set)} would read, in the same order.
     *
     * @param paths   files and/or directories; directories are walked recursively
     * @param exclude files to leave out
     * @return the supported RDF files, sorted per directory, without the excluded ones
     */
    public static List<Path> files(List<Path> paths, Set<Path> exclude) {
        Set<Path> excluded = new HashSet<>();
        exclude.forEach(p -> excluded.add(p.toAbsolutePath().normalize()));
        List<Path> result = new ArrayList<>();
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(Files::isRegularFile)
                            .filter(OntologyLoader::isSupported)
                            .sorted()
                            .forEach(result::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isRegularFile(path)) {
                result.add(path);
            } else {
                LOG.warn("Path does not exist: {}", path);
            }
        }
        result.removeIf(p -> excluded.contains(p.toAbsolutePath().normalize()));
        return result;
    }

    private static boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED.stream().anyMatch(name::endsWith);
    }

    private static void read(Model model, Path file) {
        try {
            Model part = RDFDataMgr.loadModel(file.toString());
            // Copy the triples only: Model.add(Model) would also copy the prefixes (including the empty one).
            part.getGraph().find().forEach(model.getGraph()::add);
            // The first declaration of a prefix wins; later files do not override it.
            // The empty prefix differs from file to file and is therefore of no use.
            part.getNsPrefixMap().forEach((prefix, ns) -> {
                if (!prefix.isEmpty() && model.getNsPrefixURI(prefix) == null) {
                    model.setNsPrefix(prefix, ns);
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Skipped (parse error): {} — {}", file, e.getMessage());
        }
    }
}
