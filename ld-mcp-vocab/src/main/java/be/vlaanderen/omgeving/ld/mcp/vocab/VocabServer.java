package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.util.List;
import java.util.Optional;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import be.vlaanderen.omgeving.ld.mcp.core.StdioServer;

/**
 * MCP server for looking up terms in a set of ontologies and validating data files.
 * Changes to ontologies, rules, shapes or the prefix registry are picked up on the next tool call.
 *
 * <pre>
 * java -jar ld-mcp-vocab.jar --ontologies src/main/resources [--ontologies extra.ttl]
 *     [--rules rules.rules]… [--shapes shapes.ttl]… [--generate-shapes]
 *     [--prefix-registry prefix.cc.ttl]
 *     [--lov-url https://lov.linkeddata.es/dataset/api/v2] [--core-namespace http://…/] [--offline]
 *     [--alignments registers/alignments.ttl]
 *     [--allow-private-hosts] [--max-response-bytes 20971520] [--network-budget 30]
 *     [--data-root .]
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
        Setup setup;
        try {
            setup = setup(args);
        } catch (IllegalArgumentException e) {
            // A configuration error: say so and stop, with the exit code for usage errors.
            System.err.println("ld-mcp-vocab: " + e.getMessage());
            System.exit(2);
            return;
        }
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
     * @throws IllegalArgumentException if the arguments are wrong
     */
    static Setup setup(String[] args) {
        VocabConfig config = VocabConfig.parse(args);
        Workspace workspace = config.workspace(VERSION);
        int termCount = workspace.current().index().size();
        return new Setup(VocabTools.instructions(termCount), VocabTools.tools(config, workspace));
    }

    /**
     * Returns the version of the jar.
     *
     * @return the {@code Implementation-Version} of the manifest, or {@code "dev"}
     */
    static String version() {
        return VERSION;
    }
}
