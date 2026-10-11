package be.vlaanderen.omgeving.ld.mcp.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * Laadt RDF-bestanden en -mappen (recursief) in één Jena-model. Bestanden die niet parsen worden
 * gelogd en overgeslagen, zodat één fout bestand de server niet blokkeert.
 */
public final class OntologyLoader {

    private static final Logger LOG = LoggerFactory.getLogger(OntologyLoader.class);

    /**
     * Extensies die als ontologie opgeladen worden. Geen .xml (vaak configuratie, bv. logback.xml)
     * en geen .trig/.nq (datasets).
     */
    private static final Set<String> SUPPORTED =
            Set.of(".ttl", ".rdf", ".owl", ".nt", ".n3", ".jsonld");

    private OntologyLoader() {
    }

    public static Model load(List<Path> paths) {
        Model model = ModelFactory.createDefaultModel();
        files(paths).forEach(file -> read(model, file));
        LOG.info("{} triples geladen uit {}", model.size(), paths);
        return model;
    }

    /** De bestanden die {@link #load} zou inlezen, in dezelfde volgorde. */
    public static List<Path> files(List<Path> paths) {
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
                LOG.warn("Pad bestaat niet: {}", path);
            }
        }
        return result;
    }

    private static boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED.stream().anyMatch(name::endsWith);
    }

    private static void read(Model model, Path file) {
        try {
            Model part = RDFDataMgr.loadModel(file.toString());
            // Enkel triples kopiëren: Model.add(Model) neemt ook de prefixen over (incl. het lege).
            part.getGraph().find().forEach(model.getGraph()::add);
            // Eerste declaratie van een prefix wint; latere bestanden overschrijven niet.
            // Het lege prefix is per bestand verschillend en dus niet bruikbaar.
            part.getNsPrefixMap().forEach((prefix, ns) -> {
                if (!prefix.isEmpty() && model.getNsPrefixURI(prefix) == null) {
                    model.setNsPrefix(prefix, ns);
                }
            });
        } catch (RuntimeException e) {
            LOG.warn("Overgeslagen (parsefout): {} — {}", file, e.getMessage());
        }
    }
}
