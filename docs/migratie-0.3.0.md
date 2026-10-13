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
