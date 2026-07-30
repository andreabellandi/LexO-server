# LexO-server: _REST services for Linguistic Linked Data_ 

[![Build Status](images/build-passing.png)](https://github.com/andreabellandi/LexO-backend) [![N|Solid](images/clarin.png)](https://ilc4clarin.ilc.cnr.it/) 

LexO-server is a backend software providing REST services for building and managing linguistic resources in the context of the Semantic Web, in particular:
- lexical and terminological resources are based on the [_OntoLex-Lemon_](https://www.w3.org/2016/05/ontolex/) model;
- lexicographic resources (dictionaries) are based on the [_Lexicog_](https://www.w3.org/2019/09/lexicog/) model;
- Explanatory Combinatorial Dictionaries (ECDs) are represented using a combination of these two models.

LexO-server uses the [Swagger](https://swagger.io/) open source tool. It helps one to design and to document APIs at scale, for easing and supporting the front end GUI development process

## Features

- Targeted for web apps oriented at different lexicographic-based tasks, such as editing, linking, data visualization, dictionary making, linguistic annotation
- Lexical level implemented by the [_OntoLex-Lemon_](https://www.w3.org/2016/05/ontolex/) and the [_Lexicog_](https://www.w3.org/2019/09/lexicog/) models.
- Conceptual level implemented by the [_SKOS_](https://www.w3.org/2004/02/skos/) model 
- Integrated user authentication addressed by [KeyCloak](https://www.keycloak.org/) 
- Possibility to manage bibliographical items with [Zotero](https://www.zotero.org/) 
- Integration with remote SPARQL endpoints 
- Export data as Linked Data (RDF/XML, Turtle, N3, NQuads)

## Tech

LexO-server uses the following technology to work properly:

- Java 15 or later
- Apache Tomcat 9 or later
- [GraphDB Free](https://graphdb.ontotext.com/) - Semantic Graph Database, compliant with W3C Standards.

LexO-server persists lexical and textual data in GraphDB. It does not require
MySQL or another relational database.

## Installation

1. [Install](https://graphdb.ontotext.com/documentation/free/quick-start-guide.html)
   and start GraphDB Free at `http://localhost:7200`.
2. Download the project and run `mvn clean package` without a Maven profile.
3. Deploy `target/LexO-server.war` to Tomcat.
4. At webapp startup LexO-server creates, when missing, `LexOLexica` and
   `LexOTexts`, imports the schema resources and creates the lexical indexes.
5. Open http://localhost:8080/LexO-server/ to access Swagger.

The two GraphDB repositories are fixed in `src/main/resources/lexo-server.properties`:

```properties
GraphDb.url=http://localhost:7200
GraphDb.repository=LexOLexica
TextGraphDb.url=http://localhost:7200
TextGraphDb.repository=LexOTexts
```

## Tests

Run the unit suite with `mvn test`. Tests for the text services, including the
optional end-to-end tests for a deployed LexO-server and GraphDB Free, are
documented in [docs/text-services-tests.md](docs/text-services-tests.md).

## Text upload language

`POST /service/texts/upload` requires a multipart `language` field in addition
to the TXT/CommonMark `file` and optional `conllu` file. The value must occur in
one of the first four columns of the bundled ISO 639 list (ISO 639-1, the two
ISO 639-2 forms, or ISO 639-3). Matching is case-insensitive and the stored code
is normalized to lowercase.

```bash
curl -X POST 'http://localhost:8080/LexO-server/service/texts/upload' \
  -H 'Authorization: Bearer TOKEN_LEXO' \
  -F 'language=it' \
  -F 'file=@intervista.txt;type=text/plain'
```

The language supplied by the upload is stored as `dcterms:language` during NIF
conversion and is used as the NIF literal language tag. A `language` key in the
file front matter is ignored. Supported front-matter keys are `id`, `title`,
`author`, `date`, `description`, `format`, and `corpus`.

## Bulk text upload

`POST /service/texts/bulk` accepts multiple `file` parts containing only `.txt`,
`.md`, or `.markdown` documents and one shared required `language`. An optional
`corpusId` query parameter adds every successfully converted document to the
same existing corpus. CoNLL-U is supported only by the single-document upload:
the bulk request is rejected atomically with `BULK_CONLLU_NOT_ALLOWED` when a
`conllu` part or a CoNLL-U extension is present.

```bash
curl -X POST 'http://localhost:8080/LexO-server/service/texts/bulk' \
  -H 'Authorization: Bearer TOKEN_LEXO' \
  -F 'language=it' \
  -F 'file=@documento-1.txt;type=text/plain' \
  -F 'file=@documento-2.md;type=text/markdown'
```

The service returns HTTP `202` with one `bulkId` and an independent `fileId`
for every document. Poll `GET /service/texts/bulk/{bulkId}/status` for aggregate
and per-document states. Admission errors clean up the complete staged request;
after acceptance, conversion failures roll back only their own document and can
produce the aggregate state `PARTIALLY_COMPLETED`.

The default admission limits are 100 files and 200 MiB for the complete bulk,
in addition to the existing 50 MiB per-text limit. They can be overridden with
the JVM properties `lexo.text.maxBulkFiles`, `lexo.text.maxBulkBytes`, and
`lexo.text.maxTextBytes`.

## Text and corpus totals

Two idempotent services create or replace FRAC totals directly in `LexOTexts`:

- `PUT /service/texts/{fileId}/total` updates a converted text;
- `PUT /service/texts/corpora/{corpusId}/total` updates a corpus.

Both accept the same JSON body:

```json
{
  "value": 2312,
  "unit": "tokens"
}
```

`value` must be a non-negative `xsd:int`. `unit` accepts `tokens`, `types`,
`lemmas`, or `sentences`; the equivalent `lexo:` compact values and full
`https://lexo.ilc.cnr.it#...` IRIs are also accepted. Values are normalized to
`lexo:tokens`, `lexo:types`, `lexo:lemmas`, or `lexo:sentences` in RDF.

The resulting triples are stored in the resource's document or corpus named
graph:

```turtle
<text-or-corpus> frac:total [
    a frac:Frequency ;
    rdf:value "2312"^^xsd:int ;
    frac:unit lexo:tokens
] .
```

A request replaces every existing `frac:total` object with the same unit while
preserving totals expressed in the other supported units. The text subject is
its NIF context IRI; the corpus subject is its corpus IRI. A missing resource
returns HTTP 404 and invalid values or units return HTTP 400.

## Lexical concepts

`GET /service/create/lexicalConcept` accepts the optional query parameters
`label` and `language`. When `label` is supplied, `language` is required and
must occur in one of the first four columns of the bundled ISO 639 list. The
code is matched case-insensitively and normalized to lowercase. The value is
stored as a language-tagged `skos:prefLabel` in the lexical named graph, and
the creation response returns it in the `label` field together with the
normalized `language`. Without `label`, the service preserves the existing
fallback label derived from the generated ID and the configured default
language; an explicitly supplied `language` is validated and applies to that
fallback label.

```bash
curl -G 'http://localhost:8080/LexO-server/service/create/lexicalConcept' \
  -H 'Authorization: Bearer TOKEN_LEXO' \
  --data-urlencode 'prefix=example' \
  --data-urlencode 'baseIRI=https://example.org/lexicon/' \
  --data-urlencode 'desiredID=animal' \
  --data-urlencode 'label=animal' \
  --data-urlencode 'language=en'
```

Each item returned by `GET /service/data/lexicalConcepts` includes an integer
`attestations` field. It is the number of distinct objects linked to that
lexical concept through `frac:attestation` in the configured per-document
attestation named graphs; concepts without attestations return `0`. The same
field is included by `POST /service/data/filteredLexicalConcepts`, which uses the
same lexical-concept item response model.

## Attestations

`POST /service/attestations` creates multiple FRAC attestations for one OntoLex
lexical entry, form, lexical sense, or lexical concept. Required query parameters
are `observable` and `corpus`; `external` and `author` are optional. The JSON body
is a non-empty list whose items contain required `value`, `start`, and `end`
fields. Offsets are Unicode code-point offsets on the canonical `nif:isString`
value.

```json
[
  {"value": "example", "start": 10, "end": 17},
  {"value": "example", "start": 42, "end": 49}
]
```

The whole list is validated before persistence. All FRAC resources and NIF loci
are then written as one batch transaction per repository; a failed occurrence
does not leave the preceding occurrences stored. When the text context has a
`dcterms:language` metadata value, the same language tag is applied to
`nif:anchorOf`, `frac:gloss`, and `rdf:value`; otherwise these values remain
plain string literals.

For local evidence, `corpus` must identify a `dcmitype:Collection`,
`dcmitype:Dataset`, or `dcmitype:Text` in `LexOTexts`. The service validates the
selected substring and writes the NIF locus in the document graph. With
`external=true`, `corpus` must be an HTTP(S) URL and no remote content is
downloaded. FRAC data is written to the per-text attestation graph in
`LexOLexica`; application data is never written to a default graph.

`POST /service/attestations/by-locus` creates one attestation for each lexical
entity observed at the same textual interval. It uses the same required
`corpus` query parameter and optional `external` and `author` parameters as the
other creation endpoint. The JSON body requires `value`, `start`, `end`, and a
non-empty `observables` IRI list:

```json
{
  "value": "gli stessi diritti",
  "start": 42,
  "end": 60,
  "observables": [
    "https://lexo.ilc.cnr.it#LexO_entry1",
    "https://lexo.ilc.cnr.it#LexO_sense1"
  ]
}
```

The service validates the complete observable list before writing, creates a
single shared NIF locus, and returns the same JSON attestation array produced by
`POST /service/attestations`. If the deterministic `#char=start,end` resource
already exists as a compatible NIF word, sentence, or other structural span,
the service reuses it without changing its RDF types. A `LOCUS_CONFLICT` is
returned only when its anchor, offsets, or reference context differ.
New loci created by either attestation endpoint are marked with
`prov:wasGeneratedBy lexo:AttestationService`; pre-existing compatible loci are
reused without receiving this marker.

`POST /service/attestations/{fileId}` returns the attestations of one text as a
paginated JSON response. `observableType` and `author` optionally filter the RDF
type of the observed lexical entity and the exact `dcterms:creator` value.
`limit` defaults to 50 and `offset` defaults to 0. Each result combines FRAC
metadata from `LexOLexica` with anchor, offsets, language, RDF types and reference
context read from the corresponding NIF locus in `LexOTexts`. The response does
not expose attestation descriptions; the creation endpoint likewise neither
accepts nor persists them.

The same endpoint also accepts an optional JSON filter tree. Group nodes use
`AND` or `OR`; leaf nodes filter exact attestation creator values, RDF metadata
of the containing text, or one or more observable type IRIs. Values within one
`creator` or `observableType` leaf are OR alternatives. Observable type matching
follows `rdfs:subClassOf`, so a `LexicalEntry` condition also finds instances of
its subclasses. Text metadata comparisons preserve whether the RDF value is an
IRI or a literal and, for literals, its language or datatype.

```json
{
  "operator": "AND",
  "filters": [
    {
      "operator": "IN",
      "field": "creator",
      "values": ["user7", "user8"]
    },
    {
      "operator": "EQ",
      "field": "textMetadata",
      "property": "http://purl.org/dc/terms/title",
      "rdfValues": [
        {"type": "literal", "value": "Intervista", "language": "it"}
      ]
    },
    {
      "operator": "IN",
      "field": "observableType",
      "values": [
        "http://www.w3.org/ns/lemon/ontolex#LexicalEntry",
        "http://www.w3.org/ns/lemon/ontolex#Form"
      ]
    }
  ]
}
```

`textMetadata` also supports `EXISTS` without `rdfValues`. Filter trees are
limited to 50 nodes and five levels. The legacy `author` and `observableType`
query parameters remain supported and are combined with the JSON filter using
`AND`.

`POST /service/attestations/by-observable?observable={iri}` returns the same
paginated response across every configured per-text attestation graph. It uses
the same optional filter body and the same `limit` and `offset` query parameters.
Only graph IRIs that are valid members of the configured attestation graph
family are considered; locus data is resolved from the matching document graph
in `LexOTexts`.

The `observableLabel` field is resolved according to
the observable type: lexical
entries prefer `rdfs:label` and then their canonical form's
`ontolex:writtenRep`; forms prefer `ontolex:writtenRep` and then `rdfs:label`;
lexical senses combine their entry label or canonical written representation
with `skos:definition`; lexical concepts prefer `skos:prefLabel` and then
`rdfs:label`. Language tags are preserved in the compact `value@language`
format (for example, `casa@it`); missing values fall back to `"no label"`.

`PATCH /service/attestations/{fileId}/locus` changes the locus of one
attestation. `start` and `end` are Unicode code-point offsets on the canonical
`nif:isString`; the service derives the replacement text rather than accepting
it from the client. It moves the NIF resource to the corresponding deterministic
`#char=start,end` IRI and updates `nif:anchorOf`, `nif:beginIndex`,
`nif:endIndex`, `rdf:value`, and `dcterms:modified`. `frac:gloss` is updated by
default and is preserved when `updateGloss` is `false`.

```json
{
  "attestation": "https://lexo.ilc.cnr.it#LexO_attestation1",
  "start": 42,
  "end": 60,
  "updateGloss": true
}
```

Only a locus marked `prov:wasGeneratedBy lexo:AttestationService` and referenced
exclusively by the selected attestation can be changed. A reused/imported or
shared locus produces `LOCUS_NOT_MODIFIABLE` without modifying either
repository. A pre-existing destination IRI produces `LOCUS_CONFLICT`.

`PATCH /service/attestations/{fileId}/observable` replaces the inverse
`observable frac:attestation attestation` relation for one or more attestations.
The replacement must be a supported OntoLex observable in the lexical graph.
The complete list is validated before a single `LexOLexica` transaction.

```json
{
  "observable": "https://lexo.ilc.cnr.it#LexO_entry2",
  "attestations": [
    "https://lexo.ilc.cnr.it#LexO_attestation1",
    "https://lexo.ilc.cnr.it#LexO_attestation2"
  ]
}
```

`PATCH /service/attestations/{fileId}/metadata` atomically replaces selected RDF
metadata properties on one or more attestations in that text's attestation named
graph. Property names and IRI values must be absolute IRIs. Literal values may
optionally carry either a BCP 47 language tag or an RDF datatype IRI. An empty
`values` list removes the property; properties omitted from the request remain
unchanged.

```json
{
  "updates": [
    {
      "attestation": "https://lexo.ilc.cnr.it#LexO_2026-07-29...",
      "properties": [
        {
          "property": "https://example.org/vocabulary/confidence",
          "values": [
            {
              "value": "0.92",
              "type": "literal",
              "datatype": "http://www.w3.org/2001/XMLSchema#decimal"
            }
          ]
        },
        {
          "property": "http://purl.org/dc/terms/source",
          "values": [
            {
              "value": "https://example.org/sources/corpus-1",
              "type": "iri"
            }
          ]
        }
      ]
    }
  ]
}
```

Every attestation must already be a `frac:Attestation` in the graph selected by
`fileId`. Structural properties such as `rdf:type`, `rdf:value`, `frac:locus`,
`frac:observedIn`, `frac:gloss`, creator and timestamps cannot be changed through
this endpoint. The complete batch is validated before its single LexOLexica
transaction, and every updated attestation receives a new `dcterms:modified`
value. Paginated attestation results expose custom properties in a `metadata`
object keyed by property IRI while preserving multiple values and their RDF
kind, language or datatype.

`DELETE /service/attestations/{fileId}/by-observable` atomically deletes
attestations of one observable from the per-text attestation graph. The JSON
body must provide `observable` and exactly one selection mode: either a
non-empty `attestations` IRI list or `all: true`.

```json
{
  "observable": "https://lexo.ilc.cnr.it#LexO_entry1",
  "attestations": [
    "https://lexo.ilc.cnr.it#LexO_attestation1",
    "https://lexo.ilc.cnr.it#LexO_attestation2"
  ]
}
```

`DELETE /service/attestations/{fileId}/by-locus` provides the corresponding
operation for one shared NIF locus. Its body must provide `locus` and either an
`attestations` IRI list or `all: true`:

```json
{
  "locus": "https://lexo.ilc.cnr.it/texts/example#char=42,60",
  "all": true
}
```

Both services validate the complete selection before writing and remove the
attestation resource together with every incoming `frac:attestation` link in
the graph selected by `fileId`. After deletion, a locus is removed from
`LexOTexts` only when no attestation in the configured attestation graph family
still references it and it carries the exact generation marker
`prov:wasGeneratedBy lexo:AttestationService`. Reused or still-attested loci are
retained. The response reports deleted attestations plus `deletedLoci` and
`retainedLoci`.

## License

MIT

**Free Software, Hell Yeah!**
