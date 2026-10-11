# ld-mcp

Linked-data [MCP](https://modelcontextprotocol.io)-servers op basis van Apache Jena, voor gebruik
in andere projecten (bv. OSLO-thema's). Elke server draait lokaal als proces over **stdio**: de
client (Claude Code, …) start de jar en praat er JSON-RPC mee via stdin/stdout.

## Modules

| Module | Rol |
|---|---|
| `ld-mcp-core` | Gedeeld: stdio-bootstrap, argumenten, ontologieën laden, tool-hulpmiddelen |
| `ld-mcp-vocab` | Server: termen opzoeken in een set ontologieën |

### ld-mcp-vocab

| Tool | Doel |
|---|---|
| `lookup_term` | Beschrijft één klasse of property (type, label, definitie, subClassOf/subPropertyOf, domain, range, inverseOf, subtermen). Bestaat de term niet, dan volgen suggesties, bv. `ssn:implements` → `sosa:implements`. |
| `search_terms` | Zoekt termen op lokale naam of label (hoofdletterongevoelig). |
| `validate_file` | Valideert een RDF-databestand: onbekende properties/klassen (met suggesties uit `lookup_term`), regelinferentie (`--rules`), OWL-consistentie met OWLMiniReasoner op de `owl:disjointWith`-deelset (inclusief `owl:AllDisjointClasses` en `owl:disjointUnionOf`, omgezet naar paren), en SHACL (`--shapes`) op het oorspronkelijke model. |

Argumenten:

| Argument | Verplicht | Betekenis |
|---|---|---|
| `--ontologies <pad>` | ja, herhaalbaar | Bestand of map (recursief) met `.ttl`, `.rdf`, `.owl`, `.nt`, `.n3`, `.jsonld` |
| `--rules <bestand>` | nee | Jena-regelbestand voor de inferentiestap van `validate_file`; ontbreekt het, dan wordt die stap overgeslagen |
| `--shapes <bestand>` | nee | SHACL-shapes voor `validate_file`; ontbreekt het, dan wordt de SHACL-stap overgeslagen |

Ontologieën, regels en shapes worden bij het opstarten geladen. Bij elke tool-oproep vergelijkt de
server pad, grootte en wijzigingsdatum van die bestanden met de vorige keer; is er iets toegevoegd,
verwijderd of gewijzigd, dan leest hij alles opnieuw in. Herstarten is enkel nodig na een nieuwe jar.

## Bouwen

Vereist Java 21 en Maven.

```bash
mvn package
# → ld-mcp-vocab/target/ld-mcp-vocab.jar
```

## Gebruik in een ander project

Voeg een `.mcp.json` toe in de root van dat project. Relatieve paden zijn relatief tegenover de
map van waaruit de client gestart wordt (de projectroot):

```json
{
  "mcpServers": {
    "ld-vocab": {
      "command": "java",
      "args": [
        "-jar", "/home/<gebruiker>/git/ld-mcp/ld-mcp-vocab/target/ld-mcp-vocab.jar",
        "--ontologies", "src/main/resources"
      ]
    }
  }
}
```

Of via de CLI:

```bash
claude mcp add ld-vocab -- java -jar /pad/naar/ld-mcp-vocab.jar --ontologies src/main/resources
```

## Manueel testen

```bash
{ echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"lookup_term","arguments":{"term":"sosa:Sample"}}}'
  sleep 5; } | java -jar ld-mcp-vocab/target/ld-mcp-vocab.jar --ontologies <map>
```

## Conventies voor nieuwe servers

- Eén module per server, afhankelijk van `ld-mcp-core`; package `be.vlaanderen.omgeving.ld.mcp.<server>`.
- **Stdout is voorbehouden voor JSON-RPC.** Elke servermodule heeft een `logback.xml` die naar
  stderr logt; nooit `System.out.println`.
- Niets hardcoderen naar één project: ontologieën, shapes, regels en prefixen komen binnen via
  argumenten.
- Fouten in de invoer van het model: gooi een `IllegalArgumentException`; `Tools.textTool`
  geeft die terug als tool-fout (`isError`), zodat het model kan corrigeren.
