package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import be.vlaanderen.omgeving.ld.mcp.core.ServerArgs;

/**
 * The configuration of the server, read from its arguments. The MCP server and the command line
 * ({@code validate}, {@code generate-shapes}) share it.
 *
 * @param ontologies        ontology files and directories
 * @param rules             Jena rules files
 * @param shapes            SHACL shapes files
 * @param generateShapes    whether to generate shapes from the ontologies
 * @param prefixRegistry    the prefix registry, or {@code null}
 * @param lovUrl            base URL of the LOV API
 * @param core              namespaces that {@code vocabulary_profile} counts as the core
 * @param coreIsDefault     whether {@code core} is the built-in preset (no {@code --core-namespace})
 * @param offline           whether the server makes no network requests
 * @param alignments        the alignment register, or {@code null}
 * @param allowPrivateHosts whether hosts with a private address may be fetched
 * @param maxResponseBytes  the maximum size of a response body
 * @param networkBudget     the time one call of {@code vocabulary_profile} may take
 * @param dataRoot          the directory {@code validate_file} may read from
 */
public record VocabConfig(List<Path> ontologies, List<Path> rules, List<Path> shapes, boolean generateShapes,
                          Path prefixRegistry, String lovUrl, List<String> core, boolean coreIsDefault,
                          boolean offline, Path alignments, boolean allowPrivateHosts, long maxResponseBytes,
                          Duration networkBudget, Path dataRoot) {

    /** The arguments the server accepts. */
    static final Map<String, ServerArgs.Kind> ARGUMENTS = Map.ofEntries(
            Map.entry("ontologies", ServerArgs.Kind.REPEATABLE),
            Map.entry("rules", ServerArgs.Kind.REPEATABLE),
            Map.entry("shapes", ServerArgs.Kind.REPEATABLE),
            Map.entry("generate-shapes", ServerArgs.Kind.FLAG),
            Map.entry("prefix-registry", ServerArgs.Kind.SINGLE),
            Map.entry("lov-url", ServerArgs.Kind.SINGLE),
            Map.entry("core-namespace", ServerArgs.Kind.REPEATABLE),
            Map.entry("offline", ServerArgs.Kind.FLAG),
            Map.entry("alignments", ServerArgs.Kind.SINGLE),
            Map.entry("allow-private-hosts", ServerArgs.Kind.FLAG),
            Map.entry("max-response-bytes", ServerArgs.Kind.SINGLE),
            Map.entry("network-budget", ServerArgs.Kind.SINGLE),
            Map.entry("data-root", ServerArgs.Kind.SINGLE));

    /**
     * Reads the configuration from the arguments.
     *
     * @param args the arguments
     * @return the configuration
     * @throws IllegalArgumentException if an argument is unknown, malformed or missing
     */
    public static VocabConfig parse(String[] args) {
        return from(ServerArgs.parse(args, ARGUMENTS));
    }

    /**
     * Reads the configuration from parsed arguments.
     *
     * @param parsed the arguments, parsed with (at least) {@link #ARGUMENTS}
     * @return the configuration
     * @throws IllegalArgumentException if an argument is malformed or missing
     */
    static VocabConfig from(ServerArgs parsed) {
        List<String> core = parsed.all("core-namespace");
        return new VocabConfig(
                paths(parsed.required("ontologies")),
                paths(parsed.all("rules")),
                paths(parsed.all("shapes")),
                parsed.flag("generate-shapes"),
                parsed.first("prefix-registry").map(Path::of).orElse(null),
                parsed.first("lov-url").orElse(LovClient.DEFAULT_BASE_URL),
                core.isEmpty() ? VocabularyProfiler.DEFAULT_CORE : core,
                core.isEmpty(),
                parsed.flag("offline"),
                parsed.first("alignments").map(Path::of).orElse(null),
                parsed.flag("allow-private-hosts"),
                parsed.first("max-response-bytes").map(s -> positiveLong("max-response-bytes", s))
                        .orElse(GuardedFetcher.DEFAULT_MAX_BYTES),
                parsed.first("network-budget").map(s -> Duration.ofSeconds(positiveLong("network-budget", s)))
                        .orElse(VocabularyProfiler.DEFAULT_BUDGET),
                Path.of(parsed.first("data-root").orElse("")));
    }

    /**
     * Creates the workspace for this configuration (nothing is loaded yet).
     *
     * @param version the version of the jar, for the report header
     * @return the workspace
     * @throws IllegalArgumentException if a configured path does not exist
     */
    public Workspace workspace(String version) {
        return new Workspace(ontologies, rules, shapes, generateShapes, prefixRegistry)
                .describedAs(version, Map.of("offline", String.valueOf(offline)));
    }

    private static List<Path> paths(List<String> values) {
        return values.stream().map(Path::of).toList();
    }

    private static long positiveLong(String key, String s) {
        try {
            long n = Long.parseLong(s.strip());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("--" + key + " must be a positive whole number, not '" + s + "'");
    }
}
