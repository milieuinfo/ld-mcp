package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Houdt de ingelezen ontologieën, regels en shapes bij, en leest ze opnieuw in zodra een bestand
 * toegevoegd, verwijderd of gewijzigd is. Bij elke oproep van {@link #current()} wordt een
 * vingerafdruk (pad → grootte + wijzigingsdatum) vergeleken met die van de laatste keer inlezen.
 */
public final class Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(Workspace.class);

    /** Wat de tools nodig hebben; wordt als geheel vervangen bij herladen. */
    public record State(VocabIndex index, DataValidator validator) {
    }

    private final List<Path> ontologies;
    private final Path rules;
    private final Path shapes;

    private Map<Path, String> fingerprint;
    private State state;

    /**
     * @param rules  Jena-regelbestand, of {@code null}
     * @param shapes SHACL-shapesbestand, of {@code null}
     */
    public Workspace(List<Path> ontologies, Path rules, Path shapes) {
        this.ontologies = ontologies;
        this.rules = rules;
        this.shapes = shapes;
    }

    /** De actuele toestand; herleest alles als er sinds de vorige keer iets gewijzigd is. */
    public synchronized State current() {
        Map<Path, String> now = fingerprint();
        if (!now.equals(fingerprint)) {
            if (fingerprint != null) {
                LOG.info("Wijziging gedetecteerd, opnieuw inlezen");
            }
            Model ontology = OntologyLoader.load(ontologies);
            VocabIndex index = new VocabIndex(ontology);
            state = new State(index, new DataValidator(ontology, index, rules, shapes));
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
                // Verdwenen tussen opsommen en lezen, of onleesbaar: telt als wijziging.
                result.put(file.toAbsolutePath(), "?");
            }
        }
        return result;
    }
}
