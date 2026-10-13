package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.shacl.Shapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.vlaanderen.omgeving.ld.mcp.core.JsonLd;
import be.vlaanderen.omgeving.ld.mcp.core.OntologyLoader;

/**
 * Keeps track of the loaded ontologies, rules, shapes and prefix registry, and reloads them as
 * soon as a file has been added, removed or modified. Every call to {@link #current()} compares a
 * fingerprint (path → size + last-modified time) with the one taken at the last load.
 *
 * <p>A faulty file does not take the server down. An ontology file that does not parse is skipped;
 * rules or shapes that do not parse are replaced by the previous version, or, if there is none,
 * their step is skipped. Each of these is reported in {@link State#problems()}. If loading fails
 * altogether, the previous state stays in use. The fingerprint of a failed attempt is kept too, so
 * that only the next change triggers another load.
 */
public final class Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(Workspace.class);

    /**
     * What the tools need; replaced as a whole on reload.
     *
     * @param index     index over the loaded ontologies
     * @param validator validator built from the same ontologies, rules and shapes
     * @param problems  what could not be loaded, and what was used instead; empty if all is well
     */
    public record State(VocabIndex index, DataValidator validator, List<String> problems) {

        /**
         * Copies the problems.
         *
         * @param index     index over the loaded ontologies
         * @param validator validator built from the same ontologies, rules and shapes
         * @param problems  what could not be loaded
         */
        public State {
            problems = List.copyOf(problems);
        }

        /**
         * Puts the problems in front of a tool's output, so that the user sees why a term may be
         * missing or a step skipped.
         *
         * @param output the output of a tool
         * @return the output, preceded by the problems if there are any
         */
        public String withProblems(String output) {
            if (problems.isEmpty()) {
                return output;
            }
            StringBuilder sb = new StringBuilder("Configuration problems:\n");
            problems.forEach(p -> sb.append("- ").append(p).append('\n'));
            return sb.append('\n').append(output).toString();
        }
    }

    private final List<Path> ontologies;
    private final List<Path> rules;
    private final List<Path> shapes;
    private final boolean generateShapes;
    private final Path prefixRegistry;

    private Map<Path, String> fingerprint;
    private State state;
    private String serverVersion = "dev";
    private Map<String, String> serverOptions = Map.of();
    /** The last rules and hand-written shapes that parsed, to fall back on. */
    private List<Rule> lastRules;
    private Model lastShapes;

    /**
     * Nothing is loaded until the first call to {@link #current()}.
     *
     * @param ontologies     ontology files and/or directories (walked recursively)
     * @param rules          Jena rules files, concatenated; empty to skip inference
     * @param shapes         SHACL shapes files, merged into one graph
     * @param generateShapes generate shapes from the ontology, afresh on every reload
     * @param prefixRegistry RDF file whose prefix declarations form the registry (see
     *                       {@link PrefixRegistry}), or {@code null}
     * @throws IllegalArgumentException if an ontology, rules or shapes path does not exist: a
     *                                  typing error in the configuration should stop the server,
     *                                  not silently skip a step
     */
    public Workspace(List<Path> ontologies, List<Path> rules, List<Path> shapes, boolean generateShapes,
                     Path prefixRegistry) {
        requireExisting("ontologies", ontologies);
        requireExisting("rules", rules);
        requireExisting("shapes", shapes);
        this.ontologies = List.copyOf(ontologies);
        this.rules = List.copyOf(rules);
        this.shapes = List.copyOf(shapes);
        this.generateShapes = generateShapes;
        this.prefixRegistry = prefixRegistry;
    }

    private static void requireExisting(String option, List<Path> paths) {
        for (Path p : paths) {
            if (!Files.exists(p)) {
                throw new IllegalArgumentException("--" + option + ": path does not exist: " + p.toAbsolutePath());
            }
        }
    }

    /**
     * Returns the state for the files as they are now on disk.
     *
     * @return the current state; everything is reloaded if anything has changed since the
     *         previous call
     * @throws RuntimeException if the very first load fails (there is no previous state)
     */
    public synchronized State current() {
        Map<Path, String> now = fingerprint();
        if (!now.equals(fingerprint)) {
            if (fingerprint != null) {
                LOG.info("Change detected, reloading");
            }
            // Also for a failed attempt: retry only after the next change, not on every call.
            fingerprint = now;
            try {
                state = load();
            } catch (RuntimeException e) {
                if (state == null) {
                    throw e;
                }
                LOG.error("Reload failed; using the previous configuration", e);
                List<String> problems = new ArrayList<>();
                problems.add("reload failed (" + describe(e) + "); using the previous configuration");
                problems.addAll(state.problems());
                state = new State(state.index(), state.validator(), problems);
            }
        }
        return state;
    }

    /**
     * Sets what the header of a validation report says about the server, besides the files.
     *
     * @param version the version of the jar
     * @param options the active options that the workspace does not know itself (e.g. offline)
     * @return this workspace
     */
    public synchronized Workspace describedAs(String version, Map<String, String> options) {
        this.serverVersion = version;
        this.serverOptions = Map.copyOf(options);
        return this;
    }

    private State load() {
        List<String> problems = new ArrayList<>();
        OntologyLoader.Loaded loaded = OntologyLoader.loadReporting(ontologies, excluded());
        problems.addAll(loaded.problems());
        Model ontology = loaded.model();
        VocabIndex index = new VocabIndex(ontology);
        List<Rule> parsedRules = rules(problems);
        Shapes parsedShapes = shapes(ontology, problems);
        PrefixRegistry registry = registry(problems);
        DataValidator validator = new DataValidator(ontology, index, parsedRules, parsedShapes, registry)
                .withConfiguration(configuration(problems));
        return new State(index, validator, problems);
    }

    /** The files and options of this load, for the report header. */
    private ValidationResult.Configuration configuration(List<String> problems) {
        List<ValidationResult.LoadedFile> files = new ArrayList<>();
        OntologyLoader.files(ontologies, excluded()).forEach(f -> files.add(loadedFile("ontology", f)));
        rules.forEach(f -> files.add(loadedFile("rules", f)));
        shapes.forEach(f -> files.add(loadedFile("shapes", f)));
        if (prefixRegistry != null) {
            files.add(loadedFile("prefix-registry", prefixRegistry));
        }
        Map<String, String> options = new TreeMap<>(serverOptions);
        options.put("generate-shapes", String.valueOf(generateShapes));
        return new ValidationResult.Configuration(serverVersion, files, options, problems);
    }

    private static ValidationResult.LoadedFile loadedFile(String role, Path file) {
        String hash;
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                sha.update(buffer, 0, n);
            }
            hash = HexFormat.of().formatHex(sha.digest());
        } catch (IOException e) {
            hash = "unreadable";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JVM has SHA-256
        }
        return new ValidationResult.LoadedFile(role, file.toAbsolutePath().toString(), hash);
    }

    /** The rules of all files; on a failure the previous rules, or none. */
    private List<Rule> rules(List<String> problems) {
        if (rules.isEmpty()) {
            return null;
        }
        List<Rule> all = new ArrayList<>();
        for (Path file : rules) {
            try {
                all.addAll(DataValidator.parseRules(file));
            } catch (RuntimeException e) {
                problems.add("rules file " + file + ": " + describe(e) + "; "
                        + (lastRules != null ? "using the previous rules" : "inference skipped"));
                return lastRules;
            }
        }
        lastRules = List.copyOf(all);
        return lastRules;
    }

    /**
     * The generated shapes plus those of all files. If a file cannot be parsed, the previous
     * hand-written shapes are used, or none; if the shapes cannot be used together, SHACL is skipped.
     */
    private Shapes shapes(Model ontology, List<String> problems) {
        Model generated = null;
        if (generateShapes) {
            try {
                generated = ShaclGenerator.generate(ontology);
            } catch (RuntimeException e) {
                problems.add("generating shapes failed (" + describe(e) + "); generated shapes skipped");
            }
        }
        Model fromFiles = null;
        if (!shapes.isEmpty()) {
            fromFiles = ModelFactory.createDefaultModel();
            for (Path file : shapes) {
                try {
                    fromFiles.add(JsonLd.load(file));
                } catch (RuntimeException e) {
                    problems.add("shapes file " + file + ": " + describe(e) + "; "
                            + (lastShapes != null ? "using the previous shapes" : "its shapes skipped"));
                    fromFiles = lastShapes;
                    break;
                }
            }
            if (fromFiles != null) {
                lastShapes = fromFiles;
            }
        }
        try {
            return DataValidator.shapes(generated, fromFiles);
        } catch (RuntimeException e) {
            problems.add("the shapes cannot be used (" + describe(e) + "); SHACL skipped");
            return null;
        }
    }

    /** Files under the ontology paths that are not ontologies: the shapes and the prefix registry. */
    private Set<Path> excluded() {
        Set<Path> excluded = new java.util.HashSet<>(shapes);
        if (prefixRegistry != null) {
            excluded.add(prefixRegistry);
        }
        return excluded;
    }

    /** The prefix registry, or {@code null} if none is configured or it cannot be read. */
    private PrefixRegistry registry(List<String> problems) {
        if (prefixRegistry == null) {
            return null;
        }
        try {
            PrefixRegistry registry = PrefixRegistry.load(prefixRegistry);
            LOG.info("{} namespaces loaded from prefix registry {}", registry.size(), prefixRegistry);
            return registry;
        } catch (IllegalArgumentException e) {
            // Only hints depend on the registry; validation goes on without it.
            LOG.warn("Prefix registry skipped: {}", e.getMessage());
            problems.add("prefix registry skipped: " + e.getMessage());
            return null;
        }
    }

    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private Map<Path, String> fingerprint() {
        Map<Path, String> result = new HashMap<>();
        List<Path> files = new ArrayList<>();
        try {
            files.addAll(OntologyLoader.files(ontologies, excluded()));
        } catch (UncheckedIOException e) {
            // E.g. an unreadable directory: counts as a change; the load reports it.
            result.put(Path.of("<listing>"), "error: " + e.getMessage());
        }
        files.addAll(rules);
        files.addAll(shapes);
        if (prefixRegistry != null) {
            files.add(prefixRegistry);
        }
        for (Path file : files) {
            try {
                result.put(file.toAbsolutePath(),
                        Files.size(file) + "@" + Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                // Disappeared between listing and reading, or unreadable: counts as a change.
                result.put(file.toAbsolutePath(), "?");
            }
        }
        // A missing ontology path must count too, or its reappearance goes unnoticed.
        for (Path p : ontologies) {
            if (!Files.exists(p)) {
                result.put(p.toAbsolutePath(), "missing");
            }
        }
        return result;
    }
}
