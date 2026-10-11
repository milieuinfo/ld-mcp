package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import be.vlaanderen.omgeving.ld.mcp.core.ServerArgs;
import be.vlaanderen.omgeving.ld.mcp.core.StdioServer;
import be.vlaanderen.omgeving.ld.mcp.core.Tools;

/**
 * MCP server for looking up terms in a set of ontologies and validating data files.
 * Changes to ontologies, rules or shapes are picked up on the next tool call.
 *
 * <pre>
 * java -jar ld-mcp-vocab.jar --ontologies src/main/resources [--ontologies extra.ttl]
 *     [--rules rules.rules] [--shapes shapes.ttl] [--generate-shapes]
 * </pre>
 */
public final class VocabServer {

    private static final String VERSION = "0.1.0";

    private VocabServer() {
    }

    /**
     * Loads the ontologies and serves the tools over stdio until the client disconnects.
     *
     * @param args see the class description
     * @throws InterruptedException if interrupted while serving
     */
    public static void main(String[] args) throws InterruptedException {
        ServerArgs parsed = ServerArgs.parse(args);
        List<Path> paths = parsed.required("ontologies").stream().map(Path::of).toList();
        Workspace workspace = new Workspace(paths,
                parsed.first("rules").map(Path::of).orElse(null),
                parsed.first("shapes").map(Path::of).orElse(null),
                parsed.flag("generate-shapes"));
        int termCount = workspace.current().index().size();

        StdioServer.run("ld-mcp-vocab", VERSION,
                "Looks up classes and properties in the loaded ontologies ("
                        + termCount + " terms at start-up). Use lookup_term to check that a term "
                        + "exists before using it in RDF, search_terms to find a term, and "
                        + "validate_file to validate a data file.",
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
                                        + "(with suggestions), rule inference, OWL consistency "
                                        + "(owl:disjointWith) and SHACL. Modified ontologies, rules or "
                                        + "shapes are reloaded automatically.",
                                Tools.objectSchema(
                                        Map.of("path", Tools.string(
                                                "Path to the file, relative to the project root")),
                                        List.of("path")),
                                a -> workspace.current().validator().validate(Tools.requiredString(a, "path")))));
    }
}
