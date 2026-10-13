package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import be.vlaanderen.omgeving.ld.mcp.core.ServerArgs;
import be.vlaanderen.omgeving.ld.mcp.core.StdioServer;
import be.vlaanderen.omgeving.ld.mcp.core.Tools;

/**
 * MCP server for looking up terms in a set of ontologies and validating data files.
 * Changes to ontologies, rules, shapes or the prefix registry are picked up on the next tool call.
 *
 * <pre>
 * java -jar ld-mcp-vocab.jar --ontologies src/main/resources [--ontologies extra.ttl]
 *     [--rules rules.rules] [--shapes shapes.ttl] [--generate-shapes]
 *     [--prefix-registry prefixes.json]
 *     [--lov-url https://lov.linkeddata.es/dataset/api/v2] [--core-namespace http://…/] [--offline]
 *     [--alignments registers/alignments.ttl]
 *     [--allow-private-hosts] [--max-response-bytes 20971520] [--network-budget 30]
 * </pre>
 *
 * The tools {@code lov_search} and {@code vocabulary_profile} use the network (LOV and the
 * vocabularies themselves); {@code --offline} disables them. {@code --core-namespace} (repeatable)
 * sets the namespaces that {@code vocabulary_profile} counts as the core; the default is
 * {@link VocabularyProfiler#DEFAULT_CORE}. {@code --alignments} names a register of known alignment
 * modules ({@link AlignmentRegister}), read again on every call of {@code vocabulary_profile}.
 */
public final class VocabServer {

    /**
     * The version of the jar, from {@code Implementation-Version} in its manifest (set from the
     * Maven project version when the jar is built); {@code "dev"} when run from the classes, e.g.
     * in an IDE.
     */
    private static final String VERSION = Optional
            .ofNullable(VocabServer.class.getPackage().getImplementationVersion())
            .orElse("dev");

    private VocabServer() {
    }

    /**
     * Loads the ontologies and serves the tools over stdio until the client disconnects.
     *
     * @param args see the class description
     * @throws InterruptedException if interrupted while serving
     */
    public static void main(String[] args) throws InterruptedException {
        Setup setup = setup(args);
        StdioServer.run("ld-mcp-vocab", VERSION, setup.instructions(), setup.tools());
    }

    /**
     * What the server offers: the instructions for the model and the tools.
     *
     * @param instructions guidance for the model, reported in the handshake
     * @param tools        the tools
     */
    record Setup(String instructions, List<SyncToolSpecification> tools) {
    }

    /**
     * Reads the arguments, loads the ontologies and declares the tools.
     *
     * @param args see the class description
     * @return the instructions and the tools
     */
    static Setup setup(String[] args) {
        ServerArgs parsed = ServerArgs.parse(args);
        List<Path> paths = parsed.required("ontologies").stream().map(Path::of).toList();
        Workspace workspace = new Workspace(paths,
                parsed.first("rules").map(Path::of).orElse(null),
                parsed.first("shapes").map(Path::of).orElse(null),
                parsed.flag("generate-shapes"),
                parsed.first("prefix-registry").map(Path::of).orElse(null));
        int termCount = workspace.current().index().size();

        boolean offline = parsed.flag("offline");
        boolean allowPrivate = parsed.flag("allow-private-hosts");
        long maxBytes = parsed.first("max-response-bytes").map(VocabServer::positiveLong)
                .orElse(GuardedFetcher.DEFAULT_MAX_BYTES);
        Duration budget = parsed.first("network-budget").map(s -> Duration.ofSeconds(positiveLong(s)))
                .orElse(VocabularyProfiler.DEFAULT_BUDGET);
        Fetcher fetcher = offline ? Fetcher.offline()
                : new GuardedFetcher(Duration.ofSeconds(20), maxBytes, allowPrivate);
        // LOV often hangs or drops the handshake: a shorter time-out per attempt, and retries.
        LovClient lov = new LovClient(parsed.first("lov-url").orElse(LovClient.DEFAULT_BASE_URL),
                offline ? Fetcher.offline() : new GuardedFetcher(Duration.ofSeconds(10), maxBytes, allowPrivate),
                LovClient.DEFAULT_ATTEMPTS, LovClient.DEFAULT_PAUSE);
        List<String> core = parsed.all("core-namespace").isEmpty()
                ? VocabularyProfiler.DEFAULT_CORE : parsed.all("core-namespace");
        VocabularyProfiler profiler = new VocabularyProfiler(lov, fetcher, core, budget);
        Duration searchBudget = budget.dividedBy(2);
        Path alignments = parsed.first("alignments").map(Path::of).orElse(null);

        return new Setup(
                "Looks up classes and properties in the loaded ontologies ("
                        + termCount + " terms at start-up). Use lookup_term to check that a term "
                        + "exists before using it in RDF, search_terms to find a term, and "
                        + "validate_file to validate a data file. lov_search and vocabulary_profile "
                        + "gather facts for choosing a vocabulary outside the loaded ones; they do not "
                        + "choose for you.",
                List.of(
                        Tools.textTool("lookup_term",
                                "Describes one class or property: type, label, definition, "
                                        + "subClassOf/subPropertyOf, domain, range, inverseOf, subterms. "
                                        + "If the term does not exist, suggestions follow (e.g. the same "
                                        + "local name in another namespace).",
                                Tools.objectSchema(
                                        Map.of("term", Tools.string(
                                                "Prefixed name (sosa:Observation) or full IRI")),
                                        List.of("term")),
                                a -> workspace.current().index().lookup(Tools.requiredString(a, "term"))),
                        Tools.textTool("search_terms",
                                "Finds classes and properties whose local name or label contains the "
                                        + "query (case-insensitive).",
                                Tools.objectSchema(
                                        Map.of("query", Tools.string("Text to search for, e.g. 'sample'"),
                                                "limit", Tools.integer("Maximum number of results (default 25)")),
                                        List.of("query")),
                                a -> workspace.current().index().search(Tools.requiredString(a, "query"),
                                        Tools.optionalInt(a, "limit", 25))),
                        Tools.textTool("validate_file",
                                "Validates an RDF data file (.ttl, …): unknown properties/classes "
                                        + "(with suggestions, including http/https and '#'/'/' "
                                        + "near misses), rule inference, OWL consistency "
                                        + "(owl:disjointWith) and SHACL. Modified ontologies, rules or "
                                        + "shapes are reloaded automatically.",
                                Tools.objectSchema(
                                        Map.of("path", Tools.string(
                                                "Path to the file, relative to the project root")),
                                        List.of("path")),
                                a -> workspace.current().validator().validate(Tools.requiredString(a, "path"))),
                        Tools.textTool("lov_search",
                                "Searches Linked Open Vocabularies (LOV) for candidate terms and marks "
                                        + "which ones are already loaded. LOV ranks by text match: a hit "
                                        + "is a candidate to assess, not a recommendation.",
                                Tools.objectSchema(
                                        Map.of("query", Tools.string("Text to search for, e.g. 'Sample'"),
                                                "type", Tools.string("'class' or 'property'; omit for both"),
                                                "limit", Tools.integer("Maximum number of hits (default 15)")),
                                        List.of("query")),
                                online(offline, a -> lov.searchTerms(Tools.requiredString(a, "query"),
                                        Tools.optionalString(a, "type"), Tools.optionalInt(a, "limit", 15),
                                        iri -> workspace.current().index().contains(iri),
                                        Deadline.after(searchBudget)))),
                        Tools.textTool("vocabulary_profile",
                                "Builds a fact sheet about a vocabulary for deciding whether to reuse it: "
                                        + "its LOV record, whether the namespace and a sample term "
                                        + "dereference to RDF (Turtle, RDF/XML, JSON-LD), version, licence, "
                                        + "labels and definitions, links to the core, axioms about other "
                                        + "vocabularies' terms, SKOS between classes, deprecated terms, and alignments: known "
                                        + "alignment modules from the register (--alignments) and axioms between the "
                                        + "vocabulary and the core in the loaded ontologies.",
                                Tools.objectSchema(
                                        Map.of("vocabulary", Tools.string(
                                                        "LOV prefix (saref) or namespace IRI (https://saref.etsi.org/core/)"),
                                                "term", Tools.string(
                                                        "Optional term IRI to dereference as a sample")),
                                        List.of("vocabulary")),
                                online(offline, a -> profiler.profile(Tools.requiredString(a, "vocabulary"),
                                        Tools.optionalString(a, "term"), register(alignments),
                                        workspace.current().index().model())))));
    }

    private static long positiveLong(String s) {
        try {
            long n = Long.parseLong(s.strip());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("Expected a positive whole number, not '" + s + "'");
    }

    /**
     * Returns the version of the jar.
     *
     * @return the {@code Implementation-Version} of the manifest, or {@code "dev"}
     */
    static String version() {
        return VERSION;
    }

    /**
     * Reads the alignment register, if one is configured. A register that cannot be read is
     * reported on stderr and treated as absent, so that the profile is still built.
     *
     * @param file the register, or {@code null}
     * @return the register, or {@code null}
     */
    private static AlignmentRegister register(Path file) {
        if (file == null) {
            return null;
        }
        try {
            return AlignmentRegister.load(file);
        } catch (IllegalArgumentException e) {
            System.err.println("WARN " + e.getMessage());
            return null;
        }
    }

    /**
     * Wraps the handler of a tool that uses the network, so that it reports an error when the
     * server runs with {@code --offline}.
     *
     * @param offline whether the network tools are disabled
     * @param handler the handler to use when online
     * @return the wrapped handler
     */
    private static Function<Map<String, Object>, String> online(boolean offline,
                                                                Function<Map<String, Object>, String> handler) {
        return offline
                ? a -> {
                    throw new IllegalArgumentException("This tool uses the network and the server runs with --offline.");
                }
                : handler;
    }
}
