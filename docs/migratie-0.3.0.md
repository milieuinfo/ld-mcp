# Migratie naar ld-mcp 0.3.0

*Release 0.3.0 bundelt alle wijzigingen aan markers en semantiek van `validate_file` (fase 3 van
[`plan-software-architect.md`](plan-software-architect.md)). Per werkpakket: wat er verandert, wat
een gebruiker moet aanpassen, en het effect op de voorbeelden van OSLOthema-SSNSOSA-VL (golden
suite, `ld-mcp-vocab/src/test/golden/`, project op `c85bd34`).*

Aan te passen buiten ld-mcp, dezelfde dag als de release:
- **oslo-ld-kit**, `guide/linked-data.md` (Validation): de markertabel en de alinea "SHACL validates
  the non-inferred graph" (zie 3.1 en 3.3); de vertaaltabel onder "Ontologies and application
  profiles" (zie 3.8–3.10).
- **OSLOthema-SSNSOSA-VL**, §5 van `CLAUDE.md`: de markers, en de zin dat `validate_file` hetzelfde
  resultaat geeft als `generated-shapes.ttl` (D4: niet meer zo).

---

## 3.1 Markers

| Vroeger | Nu |
|---|---|
| `[VOCAB ERROR] n unknown term(s)` | `[VOCAB] n error(s), m suspect, k hint(s)`; elke regel begint met `[error]`, `[suspect]` of `[hint]`; fouten eerst |
| `[OWL] consistent` | `[DISJOINTNESS] ok` |
| `[MODEL INVALID] n message(s)` | `[DISJOINTNESS] n violation(s)` (en `, m warning(s)` als er waarschuwingen zijn, gemarkeerd `[warning]`) |
| `[SHACL] conforms` | `[SHACL] conforms (a of n shapes applied to k focus node(s); graph: …)` |
| `[SHACL] conforms` zonder één toegepaste shape | `[SHACL] no shape applies to this data (n shapes loaded; graph: …)` |
| `[SHACL] n violation(s), …` | idem, gevolgd door dezelfde dekking tussen haakjes |

Nieuw: de regel `Configuration: ld-mcp-vocab <versie>; …; content hash <12 hex>` na de eerste regel
(2.2).

**Zekerheid van een VOCAB-melding** (afwijking van review M6, die "neither loaded nor registered" een
hint noemt):

| Zekerheid | Wanneer |
|---|---|
| `error` | onbekende term in een geladen namespace; resource gemunt in een vocabularium-namespace |
| `suspect` | near miss (`http`/`https`, `#`/`/`); onbekende term in een namespace die geladen noch geregistreerd is (meestal een typfout; zonder prefixregister was dit vroeger een fout) |
| `hint` | de namespace staat in het prefixregister maar is niet geladen: de term kan bestaan |

**Wat de gebruiker aanpast:** de markertabel in de kit en in §5 van `CLAUDE.md`. "Alle
`[VOCAB ERROR]`-waarschuwingen moeten opgelost worden" wordt "alle `[error]`-meldingen onder
`[VOCAB]`"; `[suspect]` nakijken.

**Golden-diff (24 bestanden):** enkel de markers. Eén inhoudelijke verandering:
`EnergieManagementSystem/bash/prefixen.ttl` (0 triples) gaf `[SHACL] conforms` en geeft nu
`[SHACL] no shape applies to this data` — eerlijker, geen fout in het voorbeeld.

## 3.2 Disjointness volgt de klassehiërarchie

De reasoner krijgt naast de disjunctie-axioma's ook `rdfs:subClassOf` tussen benoemde klassen. Een
instantie van een subklasse botst nu ook zonder `--rules` met de disjunctie van haar superklasse
(`ex:o a sosa:Observation, prov:Entity` geeft een violation, want `sosa:Observation ⊑
prov:Activity`, disjunct met `prov:Entity`).

**Golden-diff:** geen. OSLOthema-SSNSOSA-VL gebruikt `--rules` met een subklasse-regel, dus die
afleiding gebeurde al. Tijd: r-factor (2.939 triples) 0,8 → 1,2 s.

## 3.3 SHACL ziet de klassehiërarchie

**Graaf van de SHACL-stap:** de data, plus `rdfs:subClassOf` tussen benoemde klassen uit de
ontologieën (directe triples volstaan: SHACL volgt `rdfs:subClassOf*` zelf), plus de `rdf:type` die
de ontologieën geven aan termen waar de data enkel naar verwijst (een eenheid, een concept). Geen
afgeleide triples uit `--rules` (D2). De rapportregel zegt het:
`graph: asserted data + class hierarchy + types of referenced terms`.

**Bijsturing van D2.** Met enkel data + hiërarchie gaf de golden suite 17 nieuwe, valse violations:
`sh:class qudt:Unit` op `unit:DEG_C`, `unit:L`, … De shape kwam via een superklasse nu bij de
resultaatknopen, en de eenheid draagt haar type in QUDT, niet in de data. Vandaar de types van
verwezen termen. Die termen worden zelf geen onderwerp van validatie: resultaten op zo'n knoop vallen
weg, tenzij hij ook zonder de toegevoegde types een focusnode is (bv. via `sh:targetObjectsOf` in de
kit). Zo blijft "een afgeleid type vult geen `sh:minCount` in" overeind: de toegevoegde triples zijn
geasserteerd, en enkel over knopen die de data niet zelf beschrijft.

**Golden-diff:**
- `r-factor/rfactor.ttl`: 160 → 2 violations. Weg: 158 valse meldingen (`sh:or` op `sosa:hasMember`
  en `sh:class sosa:Procedure` voor instanties van subklassen). Nieuw: `ex:unit-rfactor`
  (`a qudt:Unit`, zelf gemunt) mist `qudt:hasQuantityKind` en `qudt:hasDimensionVector`, die de uit
  QUDT gegenereerde shape van een superklasse eist. Echt in de profiellezing (D3).
- `waterkwaliteit/waterlopen/waterlopen_meetplaatsen.ttl`: conforms → 91 violations
  `sosa:isFeatureOfInterestOf` minCount 1. Oorzaak: `code:Vhag ⊑ code:VhaWaterloopobject ⊑ … ⊑
  sosa:FeatureOfInterest` in de eigen ontologie, en SSN/SOSA 2023 zegt dat elk feature of interest
  `isFeatureOfInterestOf some (Deployment ⊔ Execution ⊔ ExecutionCollection)`. Een rechtstreekse
  `sosa:FeatureOfInterest` kreeg die melding al. **Beslissing voor het project:** moet een
  referentielijst van waterlopen die restrictie halen? Zo niet: de `minCardinality 0`-conventie op
  de eigen klasse, of de waterlopen niet als feature of interest typeren.
- Kit: de alinea "SHACL validates the non-inferred graph … type the superclasses explicitly" klopt
  niet meer. Voortaan: de klassehiërarchie en de types van verwezen termen tellen mee; afgeleide
  types (domain/range uit `--rules`) niet.

## 3.4 "Bekend" is gedefinieerd; rolcontrole

Een term is bekend als de ontologieën hem declareren (type, of onderwerp van `subClassOf`,
`subPropertyOf`, `domain`, `range`), of als het een term van RDF, RDFS, OWL 2 of XSD is (lijst uit
Jena). Vroeger volstond dat het IRI ergens in de ontologieën voorkwam. Nieuwe meldingen:
`Term only referenced, not defined` (suspect), `Property used as class` en `Class used as property`
(error). `lookup_term` gebruikt dezelfde definitie: een term die enkel vermeld wordt, is "Not found",
met de vermelding erbij; een ingebouwde term heet "built-in". Een gemunte resource wordt nog maar
één keer gemeld (niet ook als `Unknown resource` waar de data hem gebruikt).

**Golden-diff:** enkel die ontdubbeling (`gebouw#stadskantoor`, `gebouw#fabriek_kortrijk` in de
EMS-herzieningen: één fout minder per bestand). Geen onbepaalde termen of rolfouten in de voorbeelden.

## 3.5 Datatypes en literals

Datatype-IRI's worden getoetst aan de datatypes die Jena implementeert plus de `rdfs:Datatype`s uit de
ontologieën. Let op: Jena's parser registreert elk datatype-IRI dat hij tegenkomt als een lege
`BaseDatatype`; die tellen niet als bekend. Nieuwe meldingen:
- `Unknown datatype` — error in `xsd:`, `rdf:` of een geladen namespace, met een suggestie
  (`xsd:strin` → `xsd:string`); anders suspect;
- `Not a datatype` (error) — een bekende term die geen datatype is (een eenheid, een property);
- `Invalid literal` (error) — de lexicale vorm past niet bij het datatype (`"not a date"^^xsd:dateTime`);
  vroeger enkel een WARN op stderr.

**Golden-diff** (echte bevindingen):
- `waterkwaliteit/waterlopen/waterlopen_meetplaatsen.ttl`: `qudt:length "…"^^unit:M` gebruikt een
  eenheid als datatype (QUDT: `qudt:QuantityValue` met `qudt:numericValue` en `qudt:hasUnit`);
  `skos:notation "24165"^^code:uidn` (en `oidn`, `vhag`, `vhas`): de SKOS-conventie van een
  notatie-datatype per codesysteem is goed, maar `code:uidn` is in `waterlopen.ttl` een
  `rdf:Property`, geen `rdfs:Datatype`. Oplossing in de ontologie: een eigen datatype per notatie.
- `waterkwaliteit/nieuw_model/oppervlaktewater_concentraties.ttl`: idem voor `waterlopen:vhag` en
  `waterlopen:uidn`.

## 3.6 Verouderde termen

`lookup_term` toont `deprecated: true` (bij `owl:deprecated true`, `owl:DeprecatedClass`,
`owl:DeprecatedProperty`), met de vervanger (`dct:isReplacedBy`, anders een equivalente klasse of
property). `[VOCAB]` meldt `Deprecated term` (suspect), met `Replaced by …` als die er is. In
`vocabulary_profile` telt `owl:deprecated false` niet meer als verouderd.

**Golden-diff: 17 bestanden krijgen `[suspect] Deprecated term: sosa:Result`.** De geladen editie van
SSN/SOSA 2023 (`org/w3/www/ns/sosa/ssn-sosa_2023.ttl`) verklaart `sosa:Result owl:deprecated true`
(w3c/sdw-sosa-ssn #113, #163; ook vermeld in de SOSA–SAREF-uitlijning). **Punt voor de kit:** R3
beveelt `sosa:Result` aan voor lokale resultaten. Dat moet herbekeken worden (bv. enkel
`qudt:QuantityValue`, of de status van `sosa:Result` in de definitieve editie nagaan). Tot dan is de
melding terecht en, als ze bewust blijft staan, te vermelden in de bespreking (§6).

## 3.7 Vocabularium-namespaces afgebakend

Een vocabularium-namespace is nu een namespace waarin de ontologieën termen *declareren*: klassen,
properties, datatypes, getypeerde individuen (eenheden, concepten). Niet meer: `owl:Ontology`-headers
en SHACL-shapes (`sh:NodeShape`, `sh:PropertyShape`, `sh:Shape`), die ook uit `search_terms`
verdwijnen. "Gedeclareerd" omvat naast een type en `subClassOf`/`subPropertyOf`/`domain`/`range`
ook `owl:equivalentClass`, `owl:equivalentProperty`, `owl:disjointWith`, `owl:disjointUnionOf` en
`owl:inverseOf` (ook voor 3.4). De `--shapes`-bestanden worden niet meer als ontologie geladen,
ook niet als ze onder `--ontologies` staan.

Beperking (D5): een *getypeerd* voorbeeldindividu in de ontologiemap maakt zijn namespace nog altijd
tot vocabularium-namespace. Houd voorbeelddata buiten `--ontologies`.

**Golden-diff:** geen. (In OSLOthema-SSNSOSA-VL staat `generated-shapes.ttl` onder
`src/main/resources`; zijn shapes tellen nu niet meer als termen.)

## 3.8 Generator: datatypes

`--generate-shapes` behandelt als datatype: alles in `xsd:`, `rdf:langString`, `rdf:HTML`,
`rdf:XMLLiteral`, `rdf:JSON`, `rdf:dirLangString`, en alles wat in de ontologieën `a rdfs:Datatype`
is (bv. `geo:wktLiteral`). Vroeger werd `owl:allValuesFrom rdf:langString` een `sh:class`, waardoor
elke correcte waarde faalde. `rdfs:Literal` wordt `sh:nodeKind sh:Literal`; `owl:Thing` en
`rdfs:Resource` (ook als lid van een `owl:unionOf`) leggen niets op.

**Golden-diff:** geen.

## 3.9 Generator: someValuesFrom, gekwalificeerde cardinaliteit, hasValue, intersectionOf

| OWL | Vroeger | Nu |
|---|---|---|
| `owl:someValuesFrom C` | `sh:class C` op *alle* waarden + `sh:minCount 1` | minstens één waarde is een C: eigen property shape met `sh:qualifiedValueShape [ sh:class C ] ; sh:qualifiedMinCount 1` |
| `owl:someValuesFrom C` + `owl:allValuesFrom D` | samengevoegd tot "alle waarden C én D" | beide apart |
| `owl:someValuesFrom owl:Thing` | `sh:class owl:Thing` | `sh:minCount 1` |
| `owl:(min\|max)QualifiedCardinality`, `owl:qualifiedCardinality` (`onClass`/`onDataRange`) | niet vertaald | `sh:qualifiedValueShape` + `sh:qualifiedMinCount`/`sh:qualifiedMaxCount` |
| `owl:hasValue` | niet vertaald | `sh:hasValue` |
| restricties in `owl:intersectionOf` (ook binnen `owl:equivalentClass`) | niet vertaald | per lid vertaald |
| klassen die enkel `rdfs:Class` zijn | geen shape | wel |
| `owl:unionOf` met een anoniem lid | lid weggelaten (strengere `sh:or`, of een lege `sh:or ()` die altijd faalt) | niet vertaald (gemeld, 3.10) |
| klasse zonder vertaalbare restrictie | lege node shape | geen node shape |

**Beslispunt: de `minCardinality 0`-conventie (B6).** Vroeger hief `owl:minCardinality 0` enkel de
verplichting op, en bleef `sh:class C` op alle waarden staan (∃p.C + min 0 werd ∀p.C). Nu valt de
existentiële restrictie helemaal weg: er is geen verplichting, en ook geen klassecontrole op de
waarden. Dat volgt de beschrijving in `waterkwaliteit.ttl` ("een someValuesFrom op dezelfde property
wordt niet verplicht") en de daar gebruikte regel "wie een klassecontrole wil, gebruikt
`owl:allValuesFrom`". Gevolg voor `wk:Emissie`: `prov:wasGeneratedBy`, `sosa:isResultOf` en
`prov:wasDerivedFrom` worden niet meer op klasse gecontroleerd. Wie dat wel wil: een
`owl:allValuesFrom` toevoegen. (Alternatief, niet gekozen: "als er waarden zijn, is er minstens één
een C", met `sh:or ( [sh:maxCount 0] [qualified ≥ 1] )`.)

Identieke restricties (dezelfde restrictie twee keer in een bestand, of in twee bestanden die de klasse
definiëren) geven één constraint. SSN/SOSA 2023 herhaalt de restricties op `sosa:FeatureOfInterest`,
en de samengevoegde kopie in OSLOthema-SSNSOSA-VL nog eens.

**Golden-diff:** geen andere aantallen. De dekking telt minder shapes (439 → 170: lege node shapes
verdwijnen; `nace2008`/`nace2025` zeggen nu "no shape applies"). De 91 meldingen in
`waterlopen_meetplaatsen` zijn nu `QualifiedValueShape` in plaats van `minCount`.

## 3.10 Generator: wat niet vertaald wordt, wordt gemeld

`ShaclGenerator.translate` geeft naast de shapes de lijst van wat niet vertaald werd. De
configuratieregel van elk rapport toont `generate-shapes (n node shapes, m property shapes, k
restriction(s) not translated)`; het serverlog lijst ze op, en het gestructureerde resultaat
(`Configuration.notTranslated`) ook. De README beschrijft de profiellezing (B7), de
`minCardinality 0`-conventie als conventie van de tool (B6), de vertaaltabel met wat niet vertaald
wordt, en claimt geen pariteit met `OwlToShaclGenerator` meer (D4).

**Golden-diff:** geen (enkel de configuratieregel, die de golden suite weglaat).
