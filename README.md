# ld-mcp

Linked data [MCP](https://modelcontextprotocol.io) servers built on Apache Jena, for use in other
projects (e.g. OSLO themes). Each server runs locally as a process over **stdio**: the client
(Claude Code, …) starts the jar and talks JSON-RPC to it via stdin/stdout.

## Modules

| Module | Role |
|---|---|
| `ld-mcp-core` | Shared: stdio bootstrap, arguments, ontology loading, tool helpers |
| `ld-mcp-vocab` | Server: look up terms in a set of ontologies and validate data files |

### ld-mcp-vocab

| Tool | Purpose |
|---|---|
| `lookup_term` | Describes one class or property (type, label, definition, subClassOf/subPropertyOf, domain, range, inverseOf, subterms). If the term does not exist, suggestions follow, e.g. `ssn:implements` → `sosa:implements`. |
| `search_terms` | Finds terms by local name or label (case-insensitive). |
| `validate_file` | Validates an RDF data file: unknown properties/classes (with suggestions from `lookup_term`), rule inference (`--rules`), OWL consistency with OWLMiniReasoner on the `owl:disjointWith` subset (including `owl:AllDisjointClasses` and `owl:disjointUnionOf`, expanded into pairs), and SHACL (`--generate-shapes` and/or `--shapes`) on the original model. |

Arguments:

| Argument | Required | Meaning |
|---|---|---|
| `--ontologies <path>` | yes, repeatable | File or directory (recursive) with `.ttl`, `.rdf`, `.owl`, `.nt`, `.n3`, `.jsonld` |
| `--rules <file>` | no | Jena rules file for the inference step of `validate_file`; if absent, that step is skipped |
| `--generate-shapes` | no | Generate SHACL shapes for `validate_file` from the OWL restrictions in the ontologies (see below) |
| `--shapes <file>` | no | Additional (e.g. hand-written) SHACL shapes for `validate_file`. Without `--shapes` and `--generate-shapes` the SHACL step is skipped |

Ontologies, rules and shapes are loaded at start-up. On every tool call the server compares the
path, size and modification time of those files with the previous load; if anything has been
added, removed or modified, it reloads everything and regenerates the shapes. A restart is only
needed after building a new jar.

### Validation report

`validate_file` reports each step on its own line:

| Marker | Meaning |
|---|---|
| `[VOCAB] ok` / `[VOCAB ERROR]` | Every predicate and `rdf:type` object occurs in the ontologies / unknown terms, with suggestions |
| `[INFERENCE]` | Number of triples inferred by the rules, or `skipped` |
| `[OWL] consistent` / `[MODEL INVALID]` | OWL consistency after inference / disjointness violations |
| `[SHACL] conforms` / `[SHACL] n violation(s)` | SHACL result, or `skipped` |

A file that cannot be parsed yields `PARSE ERROR` with the position of the error.

### Generated shapes (`--generate-shapes`)

For every `owl:Class` a NodeShape `<class>Shape` with one PropertyShape per path:

| OWL | SHACL |
|---|---|
| `owl:someValuesFrom` / `owl:allValuesFrom` | `sh:class`, or `sh:datatype` for `xsd:` types |
| `owl:unionOf` as value | `sh:or` |
| `owl:cardinality` / `owl:minCardinality` / `owl:maxCardinality` | `sh:minCount` / `sh:maxCount` |
| `owl:someValuesFrom` | implied `sh:minCount 1`, unless the same class has an `owl:minCardinality 0` restriction on that property |
| anonymous property with `owl:inverseOf` | `sh:inversePath` |

Several restrictions on the same path are merged (largest minimum, smallest maximum).
Same result as `OwlToShaclGenerator` in OSLOthema-SSNSOSA-VL.

## Building

Requires Java 21 and Maven.

```bash
mvn package
# → ld-mcp-vocab/target/ld-mcp-vocab.jar
```

## Use in another project

Add a `.mcp.json` to the root of that project. Relative paths are resolved against the directory
from which the client is started (the project root):

```json
{
  "mcpServers": {
    "ld-vocab": {
      "command": "java",
      "args": [
        "-jar", "/home/<user>/git/ld-mcp/ld-mcp-vocab/target/ld-mcp-vocab.jar",
        "--ontologies", "src/main/resources",
        "--rules", "path/to/rules.rules",
        "--generate-shapes"
      ]
    }
  }
}
```

Or via the CLI:

```bash
claude mcp add ld-vocab -- java -jar /path/to/ld-mcp-vocab.jar --ontologies src/main/resources
```

## Manual testing

```bash
{ echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}'
  echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"lookup_term","arguments":{"term":"sosa:Sample"}}}'
  sleep 5; } | java -jar ld-mcp-vocab/target/ld-mcp-vocab.jar --ontologies <directory>
```

## Conventions for new servers

- One module per server, depending on `ld-mcp-core`; package `be.vlaanderen.omgeving.ld.mcp.<server>`.
- **Stdout is reserved for JSON-RPC.** Every server module has a `logback.xml` that logs to
  stderr; never use `System.out.println`.
- Hard-code nothing for a single project: ontologies, shapes, rules and prefixes come in through
  arguments.
- Errors in the model's input: throw an `IllegalArgumentException`; `Tools.textTool` returns it
  as a tool error (`isError`), so that the model can correct itself.
- Code, comments, Javadoc and tool output are in British English.
