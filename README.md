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
| `lookup_term` | Describes one class, property or concept (type, label, definition, subClassOf/subPropertyOf, domain, range, inverseOf, SKOS mappings such as `broadMatch` to a QUDT unit, subterms). If the term does not exist, suggestions follow, e.g. `ssn:implements` → `sosa:implements`. |
| `search_terms` | Finds classes, properties and named individuals (e.g. QUDT units) by local name or label (case-insensitive); exact matches first, so `metre` finds `unit:M`. |
| `validate_file` | Validates an RDF data file: unknown properties, classes and other IRIs in a vocabulary namespace such as `unit:` (with suggestions from `lookup_term`, and hints for `http`/`https` and `#`/`/` near misses), resources minted by the data in a vocabulary namespace, rule inference (`--rules`), OWL consistency with OWLMiniReasoner on the `owl:disjointWith` subset (including `owl:AllDisjointClasses` and `owl:disjointUnionOf`, expanded into pairs), and SHACL (`--generate-shapes` and/or `--shapes`) on the original model. |

Arguments:

| Argument | Required | Meaning |
|---|---|---|
| `--ontologies <path>` | yes, repeatable | File or directory (recursive) with `.ttl`, `.rdf`, `.owl`, `.nt`, `.n3`, `.jsonld` |
| `--rules <file>` | no | Jena rules file for the inference step of `validate_file`; if absent, that step is skipped |
| `--generate-shapes` | no | Generate SHACL shapes for `validate_file` from the OWL restrictions in the ontologies (see below) |
| `--shapes <file>` | no | Additional (e.g. hand-written) SHACL shapes for `validate_file`. Without `--shapes` and `--generate-shapes` the SHACL step is skipped |
| `--prefix-registry <file>` | no | RDF file whose prefix declarations serve as a registry of well-known namespaces, e.g. a download of [prefix.cc/popular/all.file.ttl](http://prefix.cc/popular/all.file.ttl); any syntax Jena reads will do, triples are ignored. Lets `validate_file` tell a vocabulary that exists but is not loaded from a namespace that is not known at all (see below). It is never loaded as an ontology, even if it lies under `--ontologies` (other tools that read that directory, such as a build pipeline, may not be so careful). Not bundled with the jar: the project decides which snapshot it uses. If it cannot be read, a warning is logged and validation goes on without it |

Ontologies, rules, shapes and the prefix registry are loaded at start-up. On every tool call the server compares the
path, size and modification time of those files with the previous load; if anything has been
added, removed or modified, it reloads everything and regenerates the shapes. A restart is only
needed after building a new jar.

### Validation report

`validate_file` reports each step on its own line:

| Marker | Meaning |
|---|---|
| `[VOCAB] ok` / `[VOCAB ERROR]` | Every predicate, every `rdf:type` object and every other IRI in a vocabulary namespace occurs in the ontologies / unknown terms, with suggestions |
| `[INFERENCE]` | Number of triples inferred by the rules, or `skipped` |
| `[OWL] consistent` / `[MODEL INVALID]` | OWL consistency after inference / disjointness violations |
| `[SHACL] conforms` / `[SHACL] n violation(s), m warning(s)` | SHACL result, or `skipped`. Results are grouped by `sh:severity`: violations first, then warnings and info, marked `[warning]`/`[info]`. Note that `sh:severity` belongs on the shape, not on an `sh:sparql` constraint |

A *vocabulary namespace* is any namespace (IRI up to the last `#` or `/`) in which the ontologies
define resources. Under `[VOCAB ERROR]`:

- `Unknown property` / `Unknown class`: a predicate or `rdf:type` object that the ontologies do
  not mention;
- `Unknown resource`: any other IRI in a vocabulary namespace that the ontologies do not mention,
  e.g. a misspelt unit such as `unit:Meter` (the metre is `unit:M`), or an IRI in a near miss of a
  vocabulary namespace (see below);
- `Resource minted in a vocabulary namespace`: the data file describes a resource of its own in
  someone else's namespace, e.g. `unit:EUR a qudt:Unit`.

Near misses get a hint. If an unknown IRI differs from a known term only in the scheme
(`https` instead of `http`, common because many sites redirect to `https` while the `http` IRI
is the identifier) and/or the namespace separator (`#` versus `/`), the report says
`Did you mean <http://…>?`; if only the namespace matches, it says
`Namespace <…> is not loaded; did you mean <…>?`.

With `--prefix-registry`, an unknown IRI whose namespace is not loaded (and has no loaded near
miss) is also looked up in the registry:

| Hint | Meaning |
|---|---|
| `… is a known vocabulary ('dcat:' in the prefix registry), but it is not loaded` | The term may well exist; add the ontology to `--ontologies` to check it |
| `… is neither loaded nor registered; did you mean <…> ('schema:' in the prefix registry)?` | Near miss of a registered namespace |
| `… is neither loaded nor in the prefix registry; check it for a typo, or load its ontology` | Unknown everywhere: a typo, or a vocabulary that nobody registered (common for national vocabularies) |

Domains reserved for examples (`example.com`, `example.org`, `example.net`) get no registry hint;
prefix.cc lists some of them as if they were vocabularies.

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
