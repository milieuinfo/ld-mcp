package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.rdf.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.vlaanderen.omgeving.ld.mcp.core.OntologyLoader;

/**
 * Keeps track of the loaded ontologies, rules and shapes, and reloads them as soon as a file has
 * been added, removed or modified. Every call to {@link #current()} compares a fingerprint
 * (path → size + last-modified time) with the one taken at the last load.
 */
public final class Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(Workspace.class);

    /**
     * What the tools need; replaced as a whole on reload.
     *
     * @param index     index over the loaded ontologies
     * @param validator validator built from the same ontologies, rules and shapes
     */
    public record State(VocabIndex index, DataValidator validator) {
    }

    private final List<Path> ontologies;
    private final Path rules;
    private final Path shapes;
    private final boolean generateShapes;

    private Map<Path, String> fingerprint;
    private State state;

    /**
     * Nothing is loaded until the first call to {@link #current()}.
     *
     * @param ontologies     ontology files and/or directories (walked recursively)
     * @param rules          Jena rules file, or {@code null}
     * @param shapes         SHACL shapes file, or {@code null}
     * @param generateShapes generate shapes from the ontology, afresh on every reload
     */
    public Workspace(List<Path> ontologies, Path rules, Path shapes, boolean generateShapes) {
        this.ontologies = ontologies;
        this.rules = rules;
        this.shapes = shapes;
        this.generateShapes = generateShapes;
    }

    /**
     * Returns the state for the files as they are now on disk.
     *
     * @return the current state; everything is reloaded if anything has changed since the
     *         previous call
     */
    public synchronized State current() {
        Map<Path, String> now = fingerprint();
        if (!now.equals(fingerprint)) {
            if (fingerprint != null) {
                LOG.info("Change detected, reloading");
            }
            Model ontology = OntologyLoader.load(ontologies);
            VocabIndex index = new VocabIndex(ontology);
            state = new State(index, new DataValidator(ontology, index, rules, shapes, generateShapes));
            fingerprint = now;
        }
        return state;
    }

    private Map<Path, String> fingerprint() {
        List<Path> files = new ArrayList<>(OntologyLoader.files(ontologies));
        for (Path extra : new Path[] {rules, shapes}) {
            if (extra != null) {
                files.add(extra);
            }
        }
        Map<Path, String> result = new HashMap<>();
        for (Path file : files) {
            try {
                result.put(file.toAbsolutePath(),
                        Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                // Disappeared between listing and reading, or unreadable: counts as a change.
                result.put(file.toAbsolutePath(), "?");
            }
        }
        return result;
    }
}
