package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import be.vlaanderen.omgeving.ld.mcp.core.Tools;

/** The tools of the server: their descriptions, schemas and handlers. */
final class VocabTools {

    private static final Logger LOG = LoggerFactory.getLogger(VocabTools.class);

    private VocabTools() {
    }

    /**
     * Returns the instructions for the model.
     *
     * @param termCount the number of terms loaded at start-up
     * @return the instructions
     */
    static String instructions(int termCount) {
        return "Looks up classes and properties in the loaded ontologies ("
                + termCount + " terms at start-up). Use lookup_term to check that a term "
                + "exists before using it in RDF, search_terms to find a term, and "
                + "validate_file to validate a data file. lov_search and vocabulary_profile "
                + "gather facts for choosing a vocabulary outside the loaded ones; they do not "
                + "choose for you.";
    }

    /**
     * Declares the tools.
     *
     * @param config    the configuration
     * @param workspace the loaded ontologies, rules and shapes
     * @return the tools
     */
    static List<SyncToolSpecification> tools(VocabConfig config, Workspace workspace) {
        boolean offline = config.offline();
        Fetcher fetcher = offline ? Fetcher.offline()
                : new GuardedFetcher(Duration.ofSeconds(20), config.maxResponseBytes(), config.allowPrivateHosts());
        // LOV often hangs or drops the handshake: a shorter time-out per attempt, and retries.
        Fetcher lovFetcher = offline ? Fetcher.offline()
                : new GuardedFetcher(Duration.ofSeconds(10), config.maxResponseBytes(), config.allowPrivateHosts());
        LovClient lov = new LovClient(config.lovUrl(), lovFetcher, LovClient.DEFAULT_ATTEMPTS, LovClient.DEFAULT_PAUSE);
        VocabularyProfiler profiler = new VocabularyProfiler(lov, fetcher, config.core(), config.networkBudget());
        Duration searchBudget = config.networkBudget().dividedBy(2);
        DataRoot dataRoot = new DataRoot(config.dataRoot());

        return List.of(
                Tools.textTool("lookup_term",
                        "Describes one class, property, individual or concept (e.g. a unit): type, "
                                + "label, definition, subClassOf/subPropertyOf, domain, range, inverseOf, "
                                + "SKOS mappings, subterms. If the term does not exist, suggestions follow "
                                + "(e.g. the same local name in another loaded namespace).",
                        Tools.objectSchema(
                                Map.of("term", Tools.string("Prefixed name (sosa:Observation) or full IRI")),
                                List.of("term")),
                        Tools.readOnly(false),
                        a -> {
                            Workspace.State state = workspace.current();
                            return state.withProblems(state.index().lookup(Tools.requiredString(a, "term")));
                        }),
                Tools.textTool("search_terms",
                        "Finds classes, properties, individuals and concepts whose local name or "
                                + "label contains the query (case-insensitive); exact matches first.",
                        Tools.objectSchema(
                                Map.of("query", Tools.string("Text to search for, e.g. 'sample'"),
                                        "limit", Tools.integer("Maximum number of results (default 25)", 1, 200)),
                                List.of("query")),
                        Tools.readOnly(false),
                        a -> {
                            Workspace.State state = workspace.current();
                            return state.withProblems(state.index().search(Tools.requiredString(a, "query"),
                                    Tools.optionalInt(a, "limit", 25, 1, 200)));
                        }),
                Tools.textTool("validate_file",
                        "Validates an RDF data file (.ttl, …): unknown properties/classes "
                                + "(with suggestions, including http/https and '#'/'/' "
                                + "near misses), rule inference, OWL consistency "
                                + "(owl:disjointWith) and SHACL. Modified ontologies, rules or "
                                + "shapes are reloaded automatically.",
                        Tools.objectSchema(
                                Map.of("path", Tools.string(
                                        "Path to the file, relative to the project root (--data-root); "
                                                + "files outside it are refused")),
                                List.of("path")),
                        Tools.readOnly(false),
                        a -> {
                            Workspace.State state = workspace.current();
                            String given = Tools.requiredString(a, "path");
                            return state.withProblems(state.validator().validate(dataRoot.resolve(given), given));
                        }),
                Tools.textTool("lov_search",
                        "Searches Linked Open Vocabularies (LOV) for candidate terms and marks "
                                + "which ones are already loaded. LOV ranks by text match: a hit "
                                + "is a candidate to assess, not a recommendation.",
                        Tools.objectSchema(
                                Map.of("query", Tools.string("Text to search for, e.g. 'Sample'"),
                                        "type", Tools.string("'class' or 'property'; omit for both"),
                                        "limit", Tools.integer("Maximum number of hits (default 15)", 1, 50)),
                                List.of("query")),
                        Tools.readOnly(true),
                        online(offline, a -> lov.searchTerms(Tools.requiredString(a, "query"),
                                Tools.optionalString(a, "type"), Tools.optionalInt(a, "limit", 15, 1, 50),
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
                                        "term", Tools.string("Optional term IRI to dereference as a sample")),
                                List.of("vocabulary")),
                        Tools.readOnly(true),
                        online(offline, a -> profile(profiler, config.alignments(), workspace,
                                Tools.requiredString(a, "vocabulary"), Tools.optionalString(a, "term")))));
    }

    /**
     * Builds a profile. A register that cannot be read is said in front of the profile and
     * otherwise treated as absent, so that the profile is still built.
     */
    private static String profile(VocabularyProfiler profiler, Path alignments, Workspace workspace,
                                  String vocabulary, String term) {
        AlignmentRegister register = null;
        String problem = null;
        if (alignments != null) {
            try {
                register = AlignmentRegister.load(alignments);
            } catch (IllegalArgumentException e) {
                LOG.warn("Alignment register skipped: {}", e.getMessage());
                problem = "Alignment register skipped: " + e.getMessage() + "\n\n";
            }
        }
        String profile = profiler.profile(vocabulary, term, register, workspace.current().index().model());
        return problem == null ? profile : problem + profile;
    }

    /**
     * Wraps the handler of a tool that uses the network, so that it reports an error when the
     * server runs with {@code --offline}.
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
