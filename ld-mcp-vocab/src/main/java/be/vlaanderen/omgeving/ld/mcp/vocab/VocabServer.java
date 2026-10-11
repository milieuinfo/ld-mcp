package be.vlaanderen.omgeving.ld.mcp.vocab;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import be.vlaanderen.omgeving.ld.mcp.core.ServerArgs;
import be.vlaanderen.omgeving.ld.mcp.core.StdioServer;
import be.vlaanderen.omgeving.ld.mcp.core.Tools;

/**
 * MCP-server om termen op te zoeken in een set ontologieën en databestanden te valideren.
 * Wijzigingen aan ontologieën, regels of shapes worden bij de volgende tool-oproep opgepikt.
 *
 * <pre>
 * java -jar ld-mcp-vocab.jar --ontologies src/main/resources [--ontologies extra.ttl]
 *     [--rules regels.rules] [--shapes shapes.ttl]
 * </pre>
 */
public final class VocabServer {

    private static final String VERSION = "0.1.0";

    private VocabServer() {
    }

    public static void main(String[] args) throws InterruptedException {
        ServerArgs parsed = ServerArgs.parse(args);
        List<Path> paths = parsed.required("ontologies").stream().map(Path::of).toList();
        Workspace workspace = new Workspace(paths,
                parsed.first("rules").map(Path::of).orElse(null),
                parsed.first("shapes").map(Path::of).orElse(null));
        int termCount = workspace.current().index().size();

        StdioServer.run("ld-mcp-vocab", VERSION,
                "Zoekt klassen en properties op in de opgeladen ontologieën ("
                        + termCount + " termen bij het opstarten). Gebruik lookup_term om te controleren of een "
                        + "term bestaat vóór je hem in RDF gebruikt; search_terms om een term te vinden; "
                        + "validate_file om een databestand te valideren.",
                List.of(
                        Tools.textTool("lookup_term",
                                "Beschrijft één klasse of property: type, label, definitie, "
                                        + "subClassOf/subPropertyOf, domain, range, inverseOf, subtermen. "
                                        + "Bestaat de term niet, dan volgen suggesties (bv. dezelfde lokale "
                                        + "naam in een andere namespace).",
                                Tools.objectSchema(
                                        Map.of("term", Tools.string(
                                                "Prefixvorm (sosa:Observation) of volledige IRI")),
                                        List.of("term")),
                                a -> workspace.current().index().lookup(Tools.requiredString(a, "term"))),
                        Tools.textTool("search_terms",
                                "Zoekt klassen en properties waarvan de lokale naam of het label de "
                                        + "zoekterm bevat (hoofdletterongevoelig).",
                                Tools.objectSchema(
                                        Map.of("query", Tools.string("Zoektekst, bv. 'sample'"),
                                                "limit", Tools.integer("Maximum aantal resultaten (standaard 25)")),
                                        List.of("query")),
                                a -> workspace.current().index().search(Tools.requiredString(a, "query"),
                                        Tools.optionalInt(a, "limit", 25))),
                        Tools.textTool("validate_file",
                                "Valideert een RDF-databestand (.ttl, …): onbekende properties/klassen "
                                        + "(met suggesties), regelinferentie, OWL-consistentie "
                                        + "(owl:disjointWith) en SHACL. Gewijzigde ontologieën, regels of "
                                        + "shapes worden automatisch opnieuw ingelezen.",
                                Tools.objectSchema(
                                        Map.of("path", Tools.string(
                                                "Pad naar het bestand, relatief tegenover de projectroot")),
                                        List.of("path")),
                                a -> workspace.current().validator().validate(Tools.requiredString(a, "path")))));
    }
}
