# Migration to ld-mcp 0.4.0

*From 0.2.1 to 0.4.0. There is no release 0.4.0: the changes that the work plan (phases 0–4)
foresaw for 0.2.2, 0.4.0 and 0.4.0 are all in 0.4.0. First what a user should check in their
configuration (phase 1), then, per work package of phase 3, what changes in the markers and the
semantics of `validate_file`, with the effect on the examples of OSLOthema-SSNSOSA-VL (golden suite,
`ld-mcp-vocab/src/test/golden/`, project at `c85bd34`), and finally what is new.*

To be changed outside ld-mcp, on the day of the release (summary; details per package below):
- **oslo-ld-kit**, `guide/linked-data.md` (Validation): the marker table and the paragraph "SHACL
  validates the non-inferred graph" (see 3.1 and 3.3); the translation table under "Ontologies and
  application profiles" (see 3.8–3.10).
- **OSLOthema-SSNSOSA-VL**, §5 of `CLAUDE.md`: the markers, and the sentence that `validate_file`
  gives the same result as `generated-shapes.ttl` (D4: no longer true).

---

## Check the configuration (phase 1)

What can break an existing `.mcp.json` or an existing way of working:

| What | Before | Now | What to do |
|---|---|---|---|
| Unknown argument (e.g. `--shape`), a value after a switch, a second value for a single-valued argument | silently ignored (`--shape` switched SHACL off unnoticed) | the server does not start: message on stderr, exit code 2 | correct the arguments |
| A path of `--ontologies`, `--rules` or `--shapes` that does not exist | silently ignored | start-up error | correct the path |
| `validate_file` on a file outside the working directory | read | refused (`Refused: … lies outside the data root`), also through a symbolic link | pass `--data-root <directory>` |
| JSON-LD with a remote `@context` | the context was fetched, also with `--offline` | `PARSE ERROR … remote @context not allowed` | an inline context or a local copy |
| `vocabulary_profile`/`lov_search` to a host on the intranet (loopback, private, link-local address) | fetched | refused | `--allow-private-hosts` |
| `--shapes` or `--rules` more than once | only the first file | all files (merged) | nothing, unless you relied on the old behaviour |
| An ontology, rules or shapes file that does not parse | ontology: silently skipped; rules/shapes: all tools failed with a protocol error | skipped, or the previous version used, with `Configuration problems:` in front of every tool output | fix the file |

New arguments: `--allow-private-hosts`, `--max-response-bytes` (default 20 MB), `--network-budget`
(default 30 s for `vocabulary_profile`, half of it for `lov_search`) and `--data-root`. `--offline`
now makes no network requests at all.

## 3.1 Markers

| Before | Now |
|---|---|
| `[VOCAB ERROR] n unknown term(s)` | `[VOCAB] n error(s), m suspect, k hint(s)`; every line starts with `[error]`, `[suspect]` or `[hint]`; errors first |
| `[OWL] consistent` | `[DISJOINTNESS] ok` |
| `[MODEL INVALID] n message(s)` | `[DISJOINTNESS] n violation(s)` (and `, m warning(s)` if there are warnings, marked `[warning]`) |
| `[SHACL] conforms` | `[SHACL] conforms (a of n shapes applied to k focus node(s); graph: …)` |
| `[SHACL] conforms` without a single shape applied | `[SHACL] no shape applies to this data (n shapes loaded; graph: …)` |
| `[SHACL] n violation(s), …` | the same, followed by the same coverage in brackets |

New: the line `Configuration: ld-mcp-vocab <version>; …; content hash <12 hex>` after the first line
(2.2).

**Certainty of a VOCAB finding** (a departure from review M6, which calls "neither loaded nor
registered" a hint):

| Certainty | When |
|---|---|
| `error` | an unknown term in a loaded namespace; a resource minted in a vocabulary namespace |
| `suspect` | a near miss (`http`/`https`, `#`/`/`); an unknown term in a namespace that is neither loaded nor registered (usually a typo; without a prefix registry this used to be an error) |
| `hint` | the namespace is in the prefix registry but not loaded: the term may exist |

**What the user changes:** the marker table in the kit and in §5 of `CLAUDE.md`. "All `[VOCAB ERROR]`
warnings must be resolved" becomes "all `[error]` findings under `[VOCAB]`"; check `[suspect]`.

**Golden diff (24 files):** only the markers. One change in substance:
`EnergieManagementSystem/bash/prefixen.ttl` (0 triples) gave `[SHACL] conforms` and now gives
`[SHACL] no shape applies to this data`: more honest, and no error in the example.

## 3.2 Disjointness follows the class hierarchy

Besides the disjointness axioms, the reasoner gets `rdfs:subClassOf` between named classes. An
instance of a subclass now clashes with the disjointness of its superclass also without `--rules`
(`ex:o a sosa:Observation, prov:Entity` gives a violation, because `sosa:Observation ⊑ prov:Activity`,
which is disjoint with `prov:Entity`).

**Golden diff:** none. OSLOthema-SSNSOSA-VL uses `--rules` with a subclass rule, so that inference
happened already. Time: r-factor (2,939 triples) 0.8 → 1.2 s.

## 3.3 SHACL sees the class hierarchy

**Graph of the SHACL step:** the data, plus `rdfs:subClassOf` between named classes from the
ontologies (direct triples suffice: SHACL follows `rdfs:subClassOf*` itself), plus the `rdf:type`
that the ontologies give to terms the data only refers to (a unit, a concept). No triples inferred
by `--rules` (D2). The report line says so:
`graph: asserted data + class hierarchy + types of referenced terms`.

**Adjustment of D2.** With data + hierarchy alone, the golden suite gave 17 new, false violations:
`sh:class qudt:Unit` on `unit:DEG_C`, `unit:L`, … Through a superclass, the shape now reached the
result nodes, and the unit has its type in QUDT, not in the data. Hence the types of referenced
terms. Those terms do not become subjects of validation themselves: results on such a node are
dropped, unless it is also a focus node without the added types (e.g. through `sh:targetObjectsOf`
in the kit). This keeps "an inferred type does not fill in an `sh:minCount`" intact: the added
triples are asserted, and only about nodes that the data does not describe itself.

**Golden diff:**
- `r-factor/rfactor.ttl`: 160 → 2 violations. Gone: 158 false findings (`sh:or` on
  `sosa:hasMember` and `sh:class sosa:Procedure` for instances of subclasses). New:
  `ex:unit-rfactor` (`a qudt:Unit`, minted by the example itself) lacks `qudt:hasQuantityKind` and
  `qudt:hasDimensionVector`, which the shape generated from QUDT for a superclass requires. A real
  finding in the profile reading (D3).
- `waterkwaliteit/waterlopen/waterlopen_meetplaatsen.ttl`: conforms → 91 violations
  `sosa:isFeatureOfInterestOf` minCount 1. Cause: `code:Vhag ⊑ code:VhaWaterloopobject ⊑ … ⊑
  sosa:FeatureOfInterest` in the project's own ontology, and SSN/SOSA 2023 says that every feature
  of interest is `isFeatureOfInterestOf some (Deployment ⊔ Execution ⊔ ExecutionCollection)`. A
  direct `sosa:FeatureOfInterest` got that finding already. **Decision for the project:** must a
  reference list of watercourses meet that restriction? If not: the `minCardinality 0` convention on
  the project's own class, or do not type the watercourses as features of interest.
- Kit: the paragraph "SHACL validates the non-inferred graph … type the superclasses explicitly" is
  no longer correct. From now on: the class hierarchy and the types of referenced terms count;
  inferred types (domain/range from `--rules`) do not.

## 3.4 "Known" means defined; role check

A term is known when the ontologies declare it (a type, or the subject of `subClassOf`,
`subPropertyOf`, `domain`, `range`), or when it is a term of RDF, RDFS, OWL 2 or XSD (list from
Jena). Before, it was enough that the IRI occurred somewhere in the ontologies. New findings:
`Term only referenced, not defined` (suspect), `Property used as class` and `Class used as property`
(error). `lookup_term` uses the same definition: a term that is only mentioned is "Not found", with
the mention added; a built-in term is called "built-in". A minted resource is reported only once
(not also as `Unknown resource` where the data uses it).

**Golden diff:** only that deduplication (`gebouw#stadskantoor`, `gebouw#fabriek_kortrijk` in the
EMS revisions: one error fewer per file). No undefined terms or role errors in the examples.

## 3.5 Datatypes and literals

Datatype IRIs are checked against the datatypes that Jena implements plus the `rdfs:Datatype`s from
the ontologies. Note: Jena's parser registers every datatype IRI it meets as an empty
`BaseDatatype`; those do not count as known. New findings:
- `Unknown datatype`: an error in `xsd:`, `rdf:` or a loaded namespace, with a suggestion
  (`xsd:strin` → `xsd:string`); otherwise a suspect;
- `Not a datatype` (error): a known term that is no datatype (a unit, a property);
- `Invalid literal` (error): the lexical form does not fit the datatype
  (`"not a date"^^xsd:dateTime`); before, only a WARN on stderr.

**Golden diff** (real findings):
- `waterkwaliteit/waterlopen/waterlopen_meetplaatsen.ttl`: `qudt:length "…"^^unit:M` uses a unit as
  a datatype (QUDT: a `qudt:QuantityValue` with `qudt:numericValue` and `qudt:hasUnit`);
  `skos:notation "24165"^^code:uidn` (and `oidn`, `vhag`, `vhas`): the SKOS convention of a notation
  datatype per code system is fine, but in `waterlopen.ttl` `code:uidn` is an `rdf:Property`, not an
  `rdfs:Datatype`. Solution in the ontology: a datatype of its own per notation.
- `waterkwaliteit/nieuw_model/oppervlaktewater_concentraties.ttl`: the same for `waterlopen:vhag` and
  `waterlopen:uidn`.

## 3.6 Deprecated terms

`lookup_term` shows `deprecated: true` (for `owl:deprecated true`, `owl:DeprecatedClass`,
`owl:DeprecatedProperty`), with the replacement (`dct:isReplacedBy`, otherwise an equivalent class
or property). `[VOCAB]` reports `Deprecated term` (suspect), with `Replaced by …` when there is one.
In `vocabulary_profile`, `owl:deprecated false` no longer counts as deprecated.

**Golden diff: 17 files get `[suspect] Deprecated term: sosa:Result`.** The loaded edition of
SSN/SOSA 2023 (`org/w3/www/ns/sosa/ssn-sosa_2023.ttl`) declares `sosa:Result owl:deprecated true`
(w3c/sdw-sosa-ssn #113, #163; also mentioned in the SOSA–SAREF alignment). **A point for the kit:**
R3 recommends `sosa:Result` for local results. That needs to be reconsidered (e.g. only
`qudt:QuantityValue`, or check the status of `sosa:Result` in the final edition). Until then the
finding is justified and, if it is deliberately left, to be mentioned in the discussion (§6).

## 3.7 Vocabulary namespaces delimited

A vocabulary namespace is now a namespace in which the ontologies *declare* terms: classes,
properties, datatypes, typed individuals (units, concepts). No longer: `owl:Ontology` headers and
SHACL shapes (`sh:NodeShape`, `sh:PropertyShape`, `sh:Shape`), which also disappear from
`search_terms`. Besides a type and `subClassOf`/`subPropertyOf`/`domain`/`range`, "declared" also
covers `owl:equivalentClass`, `owl:equivalentProperty`, `owl:disjointWith`, `owl:disjointUnionOf`
and `owl:inverseOf` (also for 3.4). The `--shapes` files are no longer loaded as ontologies, not
even when they lie under `--ontologies`.

Limitation (D5): a *typed* example individual in the ontology directory still makes its namespace a
vocabulary namespace. Keep example data out of `--ontologies`.

**Golden diff:** none. (In OSLOthema-SSNSOSA-VL, `generated-shapes.ttl` lies under
`src/main/resources`; its shapes no longer count as terms.)

## 3.8 Generator: datatypes

`--generate-shapes` treats as a datatype: everything in `xsd:`, `rdf:langString`, `rdf:HTML`,
`rdf:XMLLiteral`, `rdf:JSON`, `rdf:dirLangString`, and everything that is `a rdfs:Datatype` in the
ontologies (e.g. `geo:wktLiteral`). Before, `owl:allValuesFrom rdf:langString` became an `sh:class`,
so that every correct value failed. `rdfs:Literal` becomes `sh:nodeKind sh:Literal`; `owl:Thing` and
`rdfs:Resource` (also as a member of an `owl:unionOf`) impose nothing.

**Golden diff:** none.

## 3.9 Generator: someValuesFrom, qualified cardinality, hasValue, intersectionOf

| OWL | Before | Now |
|---|---|---|
| `owl:someValuesFrom C` | `sh:class C` on *all* values + `sh:minCount 1` | at least one value is a C: a property shape of its own with `sh:qualifiedValueShape [ sh:class C ] ; sh:qualifiedMinCount 1` |
| `owl:someValuesFrom C` + `owl:allValuesFrom D` | merged into "all values C and D" | both separately |
| `owl:someValuesFrom owl:Thing` | `sh:class owl:Thing` | `sh:minCount 1` |
| `owl:(min\|max)QualifiedCardinality`, `owl:qualifiedCardinality` (`onClass`/`onDataRange`) | not translated | `sh:qualifiedValueShape` + `sh:qualifiedMinCount`/`sh:qualifiedMaxCount` |
| `owl:hasValue` | not translated | `sh:hasValue` |
| restrictions in `owl:intersectionOf` (also within `owl:equivalentClass`) | not translated | translated per member |
| classes that are only `rdfs:Class` | no shape | a shape |
| `owl:unionOf` with an anonymous member | member left out (a stricter `sh:or`, or an empty `sh:or ()` that always fails) | not translated (reported, 3.10) |
| class without a translatable restriction | empty node shape | no node shape |

**Decision point: the `minCardinality 0` convention (B6).** Before, `owl:minCardinality 0` only lifted
the requirement, and `sh:class C` stayed on all values (∃p.C + min 0 became ∀p.C). Now the
existential restriction disappears altogether: there is no requirement, and no class check on the
values either. That follows the description in `waterkwaliteit.ttl` ("a someValuesFrom on the same
property is not made mandatory") and the rule used there, "whoever wants a class check uses
`owl:allValuesFrom`". Consequence for `wk:Emissie`: `prov:wasGeneratedBy`, `sosa:isResultOf` and
`prov:wasDerivedFrom` are no longer checked for their class. Whoever wants that: add an
`owl:allValuesFrom`. (Alternative, not chosen: "if there are values, at least one is a C", with
`sh:or ( [sh:maxCount 0] [qualified ≥ 1] )`.)

Identical restrictions (the same restriction twice in one file, or in two files that define the
class) give one constraint. SSN/SOSA 2023 repeats the restrictions on `sosa:FeatureOfInterest`, and
the merged copy in OSLOthema-SSNSOSA-VL does so once more.

**Golden diff:** no other counts. The coverage counts fewer shapes (439 → 170: empty node shapes
disappear; `nace2008`/`nace2025` now say "no shape applies"). The 91 findings in
`waterlopen_meetplaatsen` are now `QualifiedValueShape` instead of `minCount`.

## 3.10 Generator: what is not translated is reported

Besides the shapes, `ShaclGenerator.translate` returns the list of what was not translated. The
configuration line of every report shows `generate-shapes (n node shapes, m property shapes, k
restriction(s) not translated)`; the server log lists them, and so does the structured result
(`Configuration.notTranslated`). The README describes the profile reading (B7), the
`minCardinality 0` convention as a convention of the tool (B6), the translation table including what
is not translated, and no longer claims parity with `OwlToShaclGenerator` (D4).

**Golden diff:** none (only the configuration line, which the golden suite leaves out).

## 3.11 Shape IRIs of their own, and `sh:message`

Generated node shapes are called `urn:x-ld-mcp:shape:<encoded class IRI>` instead of `<class>Shape`
(which minted IRIs such as `sosa:ObservationShape` in someone else's namespace). Every generated
property shape has an `sh:message`, e.g. `sosa:FeatureOfInterest: sosa:isFeatureOfInterestOf needs
at least 1 value(s) that is one of (sosa:Deployment, sosa:Execution, sosa:ExecutionCollection)
(generated from the OWL restrictions)`. For a finding about one value (`sh:class`, `sh:datatype`,
`sh:nodeKind`, `sh:or`, `sh:in`, `sh:pattern`), the report adds the value when the message does not
name it: `… (value: ex:x)`.

**Golden diff:** only the message text of the 93 generated findings (waterlopen, r-factor).

## 3.12 Display and small errors

- IRIs are shortened only at a namespace boundary and with the longest namespace: no more
  `ex:voc/part` or `waterloop:waterloopsegment/101021` (invalid CURIEs); such IRIs are written in
  full.
- "Not found" no longer shows the IRI twice; `nl-BE`, `en-GB` … count as Dutch/English.
- The near-miss explanation is neutral: the identifier is the IRI that the ontology defines (OSLO
  uses `https`).
- `vocabulary_profile`: `owl:AnnotationProperty` does not count for "without domain/range"; links
  within a core vocabulary itself do not count as "links to the core"; the alignment register
  matches on the same namespace (with or without a trailing `/`/`#`), no longer on a prefix
  (`http://www.w3.org/ns/` matched every W3C vocabulary).
- An unreadable alignment register is reported in the profile (since 2.3), not only on stderr.

**Golden diff:** `waterlopen_meetplaatsen`: segments written in full instead of as an invalid CURIE
(and therefore in a different order).

## 3.13 Richer lookup and profile

`lookup_term` also shows `altLabel`, `notation`, `scopeNote`, `vann:usageNote`, `inScheme`,
`broader`, `narrower` (also derived from the `skos:broader` of other concepts) and `relatedMatch`;
`search_terms` also searches `skos:altLabel`. `vocabulary_profile` counts SKOS mappings
(`exactMatch`, `closeMatch`, `broadMatch`, `narrowMatch`, `relatedMatch`) as alignments, and follows
blank nodes (restrictions, unions) to the first named terms.

**Golden diff:** none.

## 3.14 Neutral tool output

`lov_search` and `vocabulary_profile` no longer refer to "R17 of oslo-ld-kit", but to "your own
criteria"; the profile says which core is active (the default set, or the `--core-namespace`
values) and how to change it. The kit can go on saying in its guide that R17 is the framework.

**Golden diff:** none.

## After 3.2: disjointness without OWLMiniReasoner (performance)

An end-to-end test of the CLI on `r-factor/rfactor.jsonld` (34 MB, 678,000 triples) ran for more
than 15 minutes. Measured per step: the disjointness step grew superlinearly, and since 3.2 (the
class hierarchy in the schema of OWLMiniReasoner) it was nine times slower (160,000 triples:
1 s → 9–11 s). The step now computes, per resource, the superclasses (`rdfs:subClassOf`,
`owl:equivalentClass` in both directions) of its types (after `--rules`) itself, and checks them
against the disjoint pairs. The same meaning, linear: the file of 678,000 triples now takes 12 s (of
which 9 s parsing), the golden suite 9 s instead of 13–17 s. The finding also says by which type:
`ex:x is a voc:Animal (as a voc:Beast) and a voc:Plant, which are disjoint classes`.

## New in 0.4.0 (not breaking)

- **Report header:** `Configuration: ld-mcp-vocab <version>; …; content hash <12 hex>`, with the
  loaded files per role, the active options and a hash over their SHA-256 (phase 2).
- **Structured result:** besides the text, `validate_file` returns `structuredContent` following
  [`validation-result.schema.json`](../ld-mcp-vocab/src/main/resources/validation-result.schema.json),
  with all findings (not capped at 50) (4.2).
- **Command line:** `java -jar ld-mcp-vocab.jar validate …` with `--format text|json|ttl`,
  `--fail-on error|suspect`, `--extensions` and `--include-trig`, exit codes 0/1/2; and
  `generate-shapes --out …`, deterministic (4.1, 4.4). Without a command the jar is still the MCP
  server.
- **TriG and N-Quads** are validated with the default graph and all named graphs together (before,
  the named graphs were silently dropped).
- **Network:** one policy for all requests (only `http`/`https`, every redirect checked, a byte
  limit, one time-out for the whole response), a time budget per call, `Retry-After` with LOV, and
  content from third parties cut short and marked as `untrusted content`.
- **Tools:** every runtime error is a tool error (`isError`), not a protocol error; bounds on
  `limit` in the schema; annotations (`readOnlyHint`, `openWorldHint`).
- **Releases** are made locally with `scripts/tag-release.sh` (the organisation has no GitHub
  Actions); every jar comes with a `.sha256`.

---

## In summary: what has to happen outside ld-mcp

**oslo-ld-kit**
1. `guide/linked-data.md`, Validation: the marker table (`[VOCAB] … error(s), … suspect, …
   hint(s)` with `[error]`/`[suspect]`/`[hint]`; `[DISJOINTNESS]`; the coverage with `[SHACL]`), the
   new kinds of findings (undefined term, role errors, datatypes, deprecated), and the paragraph on
   the SHACL graph (3.3).
2. `guide/linked-data.md`, Ontologies and application profiles: `owl:someValuesFrom C` becomes "at
   least one value is a C" (`sh:qualifiedValueShape`), no longer `sh:class C` + `sh:minCount 1`; the
   `minCardinality 0` convention lifts the existential restriction completely (3.9).
3. R3 (`guide/ssn-sosa.md`): in the loaded edition of SSN/SOSA 2023, `sosa:Result` is
   `owl:deprecated` (3.6).

**OSLOthema-SSNSOSA-VL**
1. §5 of `CLAUDE.md`: the markers; delete "same result as `generated-shapes.ttl`" (D4); the
   differences with the Scala pipeline (until phase 4b).
2. Decide on the 91 findings `sosa:isFeatureOfInterestOf` in `waterlopen_meetplaatsen.ttl` (3.3) and
   on `ex:unit-rfactor` (3.3).
3. `waterlopen_meetplaatsen.ttl`/`oppervlaktewater_concentraties.ttl`: a unit used as a datatype, and
   notation datatypes declared as `rdf:Property` (3.5).
4. Phase 4b (replacing the Scala pipeline) with `validate --extensions ttl src/main/input` and
   `generate-shapes`; the check script is in the README of ld-mcp.
