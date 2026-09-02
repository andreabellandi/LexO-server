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

- Java 8 or later
- Apache Tomcat 9 (the current `javax.*` application is not compatible with
  Tomcat 10/Jakarta)
- [GraphDB Free](https://graphdb.ontotext.com/) - Semantic Graph Database, compliant with W3C Standards.

LexO-server persists lexical and textual data in GraphDB. It does not require
MySQL or another relational database.

## Installation

The recommended local installation uses Docker Compose:

```sh
cp .env.example .env
docker compose up -d --build
```

It starts GraphDB and LexO-server/Tomcat as separate services, waits for
GraphDB readiness, creates the two repositories, imports the schema, creates
the lexical indexes, and persists database and filesystem data in named
volumes. Open http://localhost:8080/LexO-server/ for Swagger. Backup, restore,
configuration, production hardening, and the versioned procedure for installing
a new WAR are documented in [docs/docker.md](docs/docker.md). Essential
quickstarts are available in
[English](docs/docker-quickstart.md) and
[Italian](docs/docker-quickstart-it.md).

For a traditional installation:

1. [Install](https://graphdb.ontotext.com/documentation/free/quick-start-guide.html)
   and start GraphDB Free at `http://localhost:7200`.
2. Download the project and run `mvn clean package` without a Maven profile.
3. Deploy `target/LexO-server.war` to Tomcat 9.
4. Open http://localhost:8080/LexO-server/ to access Swagger after bootstrap.

The non-public `klab.ilc.cnr.it:OntoApi:1.0` dependency is versioned under
`vendor/maven` and resolved automatically by Maven; a separate installation in
the developer's local Maven repository is not required.

The packaged GraphDB defaults are defined in
`src/main/resources/lexo-server.properties`:

```properties
GraphDb.url=http://localhost:7200
GraphDb.repository=LexOLexica
TextGraphDb.url=http://localhost:7200
TextGraphDb.repository=LexOTexts
```

They can be overridden without rebuilding the WAR through an external
properties file, JVM properties, or the corresponding `LEXO_*` environment
variables. Repository IDs remain `LexOLexica` and `LexOTexts` in the supplied
Compose environment.

## Logging

The WAR uses one logging stack: SLF4J 2 with Log4j 2 Core. Production events
are written as newline-delimited JSON to `logs/LexO-server.json`, with daily and
100 MB rotation, 30-day retention, and a 1 GB archive cap by default. Every HTTP
response returns `X-Request-ID` and its completion event includes status and
duration; Testo/NIF and Conversion jobs preserve the same diagnostic context.

The directory, level, rollover, and retention limits are configurable with JVM
system properties. Deployment settings, the event contract, migration boundary,
and operational guidance are documented in
[docs/logging.md](docs/logging.md).

## Tests

Run the unit suite with `mvn test`. Tests for the text services, including the
optional end-to-end tests for a deployed LexO-server and GraphDB Free, are
documented in [docs/text-services-tests.md](docs/text-services-tests.md).

The shared contract for the incremental rewrite of lexical CRUD services is
documented in [docs/lexicon-services.md](docs/lexicon-services.md). New lexical
endpoints use `/service/lexica`, while the legacy endpoint classes remain
available during the migration. Language-scoped lexical data is isolated in one
named graph per language under
`https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}`; lexical concepts and
concept sets use the fixed graph
`https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept`. Label languages must
occur in the first four columns of the bundled ISO 639 list.

## Lexical entry creation

`POST /service/lexica/entry` atomically creates a lexical entry in `LexOLexica`,
inside the named graph selected by its required ISO 639 `language`. It reuses a
`lime:Lexicon` in that graph when either `lime:language` or `dcterms:language`
matches the normalized input code; otherwise it creates the lexicon. The
optional `author` query parameter defaults to `anonymous` after authenticated
user resolution.

```json
{
  "label": "casa",
  "type": "ontolex:Word",
  "pos": "lexinfo:noun",
  "language": "it",
  "lemma": true,
  "metadata": [
    {
      "property": "https://example.org/vocabulary/source",
      "values": [
        {"value": "https://example.org/source/1", "type": "iri"},
        {"value": "fonte primaria", "type": "literal", "language": "it"}
      ]
    }
  ],
  "senses": [
    {
      "properties": [
        {
          "property": "http://www.w3.org/2004/02/skos/core#definition",
          "values": [
            {"value": "edificio adibito ad abitazione", "type": "literal", "language": "it"}
          ]
        }
      ],
      "metadata": [
        {
          "property": "https://example.org/vocabulary/confidence",
          "values": [
            {
              "value": "0.92",
              "type": "literal",
              "datatype": "http://www.w3.org/2001/XMLSchema#decimal"
            }
          ]
        }
      ]
    }
  ]
}
```

`label`, `type`, and `language` are required. `type` must be
`ontolex:LexicalEntry` or a subclass declared in the language or schema graph;
`pos`, when supplied, must identify a `lexinfo:PartOfSpeech` individual.
`lemma=true` creates the canonical `ontolex:Form`. Entry `metadata`, and sense
`properties` and `metadata`, are lists of `{property, values}` objects; every
`values` list is multivalued. Values support IRIs, plain literals,
language-tagged literals, and typed literals. Entry and sense metadata follow
the global metadata policy documented below; semantic properties such as
definitions belong in sense `properties`. The `type` member of each value is
ordinary request data (`literal` or `iri`) and is supported by the Jersey/MOXy
JSON binding.

The service returns HTTP `201`, sets `Location` to the new entry IRI, and returns
the lexicon, entry, optional canonical form, sense IRIs, normalized language,
initial `working` status, timestamp, and a `lexiconCreated` flag.

The created lexical entry receives `lexo:status "working"`; the containing
`lime:Lexicon` does not receive a workflow status.

## Lexical entry update

`PATCH /service/lexica/entry?author=editor` atomically updates the mutable core
properties of one lexical entry in its ISO-language-specific named graph. The
body requires `entry` and `language` and accepts any non-empty combination of
`label`, `type`, and `pos`:

```json
{
  "entry": "https://lexo.ilc.cnr.it#LexO_entry1",
  "language": "it",
  "expectedModified": "2026-08-04T10:20:30.000+02:00",
  "label": "casa editrice",
  "type": "ontolex:MultiWordExpression",
  "pos": "lexinfo:noun"
}
```

Omitted mutable fields remain unchanged; an explicit `pos: null` removes every
part-of-speech relation. `expectedModified` is optional and produces HTTP 409
when it does not match the stored typed timestamp. The service validates the
entry, replacement type, and part of speech before writing, updates
`dcterms:modified`, and preserves creator, creation time, status, forms, senses,
and custom metadata. Metadata changes continue to use `/service/metadata`.

## Lexical concept creation

`POST /service/lexica/lexicalConcept?author=editor` atomically creates one
`ontolex:LexicalConcept` in the fixed LexOLexica named graph
`https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept`. The JSON body contains
one or more required preferred labels and optional alternative labels, hidden
labels, definitions, lexical senses, parent concept, and concept set:

```json
{
  "label": [
    {"label": "casa", "language": "it"},
    {"label": "house", "language": "en"}
  ],
  "alternativeLabel": [
    {"label": "dimora", "language": "it"}
  ],
  "hiddenLabel": [],
  "definition": [
    {"label": "Edificio destinato ad abitazione", "language": "it"}
  ],
  "senses": [
    {"senseId": "https://example.org/sense/1", "language": "it"}
  ],
  "parent": "https://example.org/concept/parent",
  "conceptSetId": "https://example.org/concept-set/1",
  "metadata": [
    {
      "property": "https://example.org/vocabulary/source",
      "values": [
        {"value": "https://example.org/source/1", "type": "iri"}
      ]
    }
  ]
}
```

Every label and sense language is validated against the bundled ISO 639 list
and normalized to lowercase. A sense must already exist as an
`ontolex:LexicalSense`, or a declared subclass, in the language-specific graph
`https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}`. Parent concepts and
concept sets are instead validated in the fixed lexical-concept graph. The
manager validates the whole request before writing and rolls back on any
failure. It writes creator and shared `xsd:dateTime` created/modified timestamps,
`skos:prefLabel`,
`skos:alternativeLabel`, `skos:hiddenLabel`, `skos:definition`,
`ontolex:lexicalizedSense`, `skos:broader`, and `skos:inScheme` as applicable.
Custom `metadata` is optional: the member may be omitted or supplied as an empty
array, in which case no custom metadata triples are created.

Success returns HTTP `201`, sets `Location` to the new concept IRI, and returns
the IRI, resolved author, timestamp, and accepted links. Malformed input returns
`400`, missing linked resources return `404`, and incompatible RDF types return
`422`. A blank author resolves to `anonymous`.

## Lexical concept update

`PATCH /service/lexica/lexicalConcept?author=editor` atomically modifies only
the supplied semantic properties of an existing lexical concept in the fixed
`lexicalConcept` named graph. Sense links support both complete replacement and
incremental changes:

```json
{
  "lexicalConcept": "https://lexo.ilc.cnr.it#LexO_concept1",
  "expectedModified": "2026-08-04T10:20:30.000+02:00",
  "addLabels": [{"label": "abitazione", "language": "it"}],
  "removeAlternativeLabels": [
    {"label": "vecchia alternativa", "language": "it"}
  ],
  "addDefinitions": [
    {"label": "Edificio destinato ad abitazione", "language": "it"}
  ],
  "addSenses": [
    {"senseId": "https://lexo.ilc.cnr.it#LexO_sense2", "language": "it"}
  ],
  "removeSenseIds": ["https://lexo.ilc.cnr.it#LexO_sense1"],
  "parent": null
}
```

Omitted fields remain unchanged. A supplied list replaces all values of its RDF
predicate; an empty optional list removes them. Preferred `label`, when
supplied, must remain non-empty. Explicit `null` removes `parent` or
`conceptSetId`. Languages and linked resources are validated before the single
transaction, `expectedModified` optionally protects against stale updates, and
creator, creation time, and custom metadata remain untouched. Concept metadata
is modified only through `/service/metadata`. `senses` retains replacement
semantics: it replaces every `ontolex:lexicalizedSense` object and `[]` removes
all links. `addSenses` adds only its language-aware links and preserves existing
ones; adding an existing link is idempotent. `removeSenseIds` removes only the
listed IRIs from the fixed concept graph, without requiring a language, and
removing an absent link is idempotent. `senses` cannot be combined with either
incremental field, while `addSenses` and `removeSenseIds` may be used together
unless the same IRI appears in both. Empty incremental lists make no change.
Every added or replacement sense is validated in its declared language graph;
the relation itself is persisted in the fixed concept graph. Any sense-link
mutation also removes previously misdirected `ontolex:isLexicalizedSenseOf`
triples. The old input member `senseId` is rejected with
`SENSE_LANGUAGE_REQUIRED`; responses retain the `senseId` IRI list for
compatibility.

Text collections have the same dual contract, kept separate by SKOS category:
`label`, `alternativeLabel`, `hiddenLabel`, and `definition` replace their
complete value sets; the incremental pairs are `addLabels`/`removeLabels`,
`addAlternativeLabels`/`removeAlternativeLabels`,
`addHiddenLabels`/`removeHiddenLabels`, and
`addDefinitions`/`removeDefinitions`. An exact text value is identified by its
text and normalized language. Incremental operations are idempotent, preserve
all other values, and cannot be combined with the corresponding replacement
field. The same value cannot be added and removed together. A preferred-label
change must leave at least one `skos:prefLabel` on the concept.

## Lexical concept details

`GET /service/lexica/lexicalConcept?lexicalConcept={iri}` returns the complete
data of one lexical concept validated in the fixed `lexicalConcept` named
graph. The optional `author` query parameter follows the common lexical-service
fallback to `anonymous`.

The JSON response contains all `rdfs:label`, preferred, alternative, and hidden
SKOS labels with their property and language; every multilingual definition;
concept-set memberships; common RDF metadata; and four distinct hierarchy
collections under `children.direct`, `children.transitive`, `parents.direct`,
and `parents.transitive`. Related concepts include all their label types and
languages. The transitive fields use the standard SKOS predicates
`skos:broaderTransitive` and `skos:narrowerTransitive`.

Linked entries are discovered through both `ontolex:isEvokedBy` and
`ontolex:evokes`. Their labels prefer all `rdfs:label` values, then canonical
form written representations, then other-form written representations. Linked
senses are discovered through both `ontolex:lexicalizedSense` and
`ontolex:isLexicalizedSenseOf`; their labels prefer definitions, then RDFS
labels, then the same fallback on the entry linked through `ontolex:isSenseOf`
or `ontolex:sense`. Missing labels are empty arrays.

Entry and sense data is read only from valid ISO-language
`lexica/{language}` graphs. The legacy lexical graph, default graph, unrelated
contexts, and graph names containing invalid language codes are ignored.

## Common entity metadata

`GET`, `PATCH`, and `DELETE /service/metadata` provide one RDF metadata contract
for lexical entries, lexical senses, forms, lexical concepts, and attestations.
The same shared DTO, RDF codec, and global protection policy are used by entity
creation and future resources.

The common shape is a list of `{property, values}` objects. Values may be IRIs,
plain literals, BCP 47 language-tagged literals, or typed literals. In `PATCH`,
`properties` replaces the complete value set of each supplied property and
`values: []` removes that property. `addValues` and `removeValues` modify only
the exact RDF values listed and preserve every other value:

```json
{
  "entityType": "lexicalConcept",
  "resource": "https://lexo.ilc.cnr.it#LexO_concept1",
  "addValues": [{
    "property": "https://example.org/source",
    "values": [{"value": "https://example.org/new", "type": "iri"}]
  }],
  "removeValues": [{
    "property": "https://example.org/source",
    "values": [{"value": "old", "type": "literal", "language": "en"}]
  }]
}
```

Incremental metadata changes are idempotent. Replacement cannot be combined
with incremental changes for the same property, while add and remove may be
combined when their exact RDF values differ. `DELETE` accepts an explicit list
of property IRIs. Every mutation updates `dcterms:modified` and returns the
resulting canonical metadata.

The client supplies `entityType` and the context needed to select the graph:
`language` for `lexicalEntry`, `lexicalSense`, and `form`, no context for
`lexicalConcept`, and `fileId` for `attestation`. The service never accepts a
graph IRI from the client. It verifies the resource type, including transitive
OntoLex subclasses for language-scoped resources, and resolves respectively the
language graph, fixed lexical concept graph, or per-document attestation graph.
Metadata properties in the
OntoLex, FRAC, LIME, VarTrans, SynSem, SKOS, and Decomp namespaces are always
rejected, with the permanent exception of `skos:note`, which is accepted and
returned as metadata. The same applies to `dcterms:creator`, `dcterms:created`,
`dcterms:modified`, `rdf:type`, and `rdf:value`; protected predicates are also
omitted from metadata reads if already present in legacy data.

## Advanced lexical entry list

`GET /service/lexica/{language}/entries` returns all entries linked from a
`lime:Lexicon` whose `lime:language` or `dcterms:language` matches the path, in
the language-specific named graph. Optional query parameters are combined with
`AND`: `key`, `searchMode`, `case`, `type`, `pos`, `author`, `status`, and
`senseNumber`.

```text
GET /service/lexica/it/entries?key=cas&searchMode=contains&case=insensitive&status=working&senseNumber=2
```

`key` searches the entry's `rdfs:label`. Only when that property is absent does
the service use `ontolex:writtenRep` from `ontolex:canonicalForm`; only when the
entry has no canonical form does it use written representations from
`ontolex:otherForm`. `searchMode` accepts `startsWith` (the default), `contains`,
or `endsWith`. `case` accepts `sensitive` (the default) or `insensitive`.
`type` is an existing exact RDF type IRI; `pos` must identify a
LexInfo 3.0 `PartOfSpeech` individual. `status` accepts `working`, `completed`,
or `revised`, and `senseNumber` is an exact integer count greater than or equal
to zero. Missing or blank filters are ignored; when all are blank, every entry
of the selected lexicon is returned.

The response is a deterministically ordered JSON array. Each item contains
`entry`, the effective `label` when available, `type`, `pos`, `author`, `status`,
`senseNumber`, the `senses` IRI list, `canonicalFormNumber`, the deterministic
`canonicalForm` IRI when present, `otherFormNumber`, the `otherForms` IRI list,
and `metadata`. Metadata uses the common `{property, values}` RDF shape and
omits every predicate protected by the global `MetadataPolicy`. Empty relation
and metadata collections are returned as arrays; a missing canonical form is
returned as `null`. This contract returns the complete result and does not
apply implicit pagination.

## Lexical entry status changes

`PATCH /service/lexica/entries/status` atomically changes the status of one or
more lexical entries in the same language graph. The optional `author` query
parameter follows authenticated-user resolution and defaults to `anonymous`.

```json
{
  "language": "it",
  "entries": [
    {
      "entry": "https://lexo.ilc.cnr.it#lexo_entry_1",
      "fromStatus": "working",
      "toStatus": "completed"
    },
    {
      "entry": "https://lexo.ilc.cnr.it#lexo_entry_2",
      "fromStatus": "completed",
      "toStatus": "revised"
    }
  ]
}
```

Supported transitions are `working` to `completed`, `completed` to `working`,
`completed` to `revised`, and `revised` to `completed`. `fromStatus` must match
the stored value. Every entry must be an `ontolex:LexicalEntry` or subclass and
must have exactly one supported `lexo:status`; otherwise the entire batch is
rolled back. Successful changes update `dcterms:modified` and store the resolved
account in `lexo:statusChangedBy`.

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

## Canonical text and offsets

Plain TXT and JSON `text.content` preserve every line break in the canonical
text. CRLF and CR are normalized to LF; within each line, leading and trailing
whitespace is removed and every remaining whitespace run is normalized to one
space. Blank lines, repeated blank lines, and trailing LF characters are
preserved. Controlled CommonMark retains its existing rendering semantics, so
a single soft line break inside a Markdown paragraph becomes one space.

Every NIF, attestation, CoNLL-U, and annotation offset is a Unicode code-point
offset on the resulting canonical `nif:isString`. It does not necessarily refer
to a position in the physical uploaded file, whose line endings and other
whitespace may differ.

## Bulk text upload

`POST /service/texts/bulk` accepts multiple `file` parts containing `.txt`,
`.md`, `.markdown`, or fixed-schema `.json` documents and one shared required
`language`. File types may be mixed in the same request. The optional
`corpusId` query parameter applies only to TXT/CommonMark: a JSON-only request
that supplies it is rejected with `CORPUS_ID_NOT_ALLOWED_FOR_JSON`, while each
JSON document may select its own existing corpus with `metadata.corpus`.
CoNLL-U remains supported only by the single-document upload: the bulk request
is rejected atomically with `BULK_CONLLU_NOT_ALLOWED` when a `conllu` part or a
CoNLL-U extension is present.

```bash
curl -X POST 'http://localhost:8080/LexO-server/service/texts/bulk' \
  -H 'Authorization: Bearer TOKEN_LEXO' \
  -F 'language=it' \
  -F 'file=@documento-1.txt;type=text/plain' \
  -F 'file=@documento-2.json;type=application/json'
```

The JSON root accepts only `metadata`, `text`, and `attestations`. `metadata` is
optional and accepts exactly the text-import keys `id`, `title`, `author`,
`date`, `description`, `format`, and `corpus`; unlike TXT/CommonMark front
matter, any unknown JSON metadata key rejects the complete bulk. Metadata
values may be strings or arrays of strings, except `corpus`, which must be one
non-blank corpus id. `text.type` must be exactly `txt` and `text.content` is
converted as plain text, without interpreting front matter:

```json
{
  "metadata": {
    "id": "interview-45",
    "title": "Interview 45",
    "corpus": "interviews"
  },
  "text": {
    "type": "txt",
    "content": "A short text."
  },
  "attestations": [
    {
      "id": "source-row-12",
      "observable": "https://lexo.ilc.cnr.it#LexO_example",
      "type": "http://www.w3.org/ns/lemon/ontolex#LexicalSense",
      "value": "short",
      "gloss": "brief",
      "start_char": 2,
      "end_char": 7,
      "metadata": [
        {
          "property": "http://www.lexinfo.net/ontology/3.0/lexinfo#confidence",
          "values": [
            {
              "value": "0.9",
              "type": "literal",
              "datatype": "http://www.w3.org/2001/XMLSchema#decimal"
            }
          ]
        }
      ]
    }
  ]
}
```

Every attestation must declare one exact OntoLex type: `LexicalEntry`, `Form`,
or `LexicalSense` in the graph selected by the shared language, or
`LexicalConcept` in the fixed lexical-concept graph. The observable must already
exist there with that direct `rdf:type`. Offsets are Unicode code-point offsets
on the canonical `nif:isString`, and `value` must equal the selected substring.
The imported attestation uses `rdf:value` and `frac:gloss` language-tagged with
the shared language and audit creator `imported`. Its FRAC triples, metadata,
observable link, and frequency are stored in the document attestation graph;
the locus is stored in the document NIF graph. The optional input `id` is only
returned as a correlation value when that attestation cannot be saved.

The service returns HTTP `202` with one `bulkId` and an independent `fileId`
for every document. Poll `GET /service/texts/bulk/{bulkId}/status` for aggregate
and per-document states. Admission errors clean up the complete staged request;
after acceptance, conversion failures roll back only their own document and can
produce the aggregate state `PARTIALLY_COMPLETED`. Invalid individual JSON
attestations do not roll back the converted text or other attestations. Each
item reports `attestationState`, `attestationTotal`, `savedAttestations`, and
`unsavedAttestations`; the last field is `[]` when all attestations were saved,
otherwise it contains `id`, `observable`, `type`, a stable error `code`, and its
`cause`. Repeated identical input items remain distinct attestations and receive
distinct generated IRIs.

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

## Text deletion

`DELETE /service/texts/{fileId}` deletes the text record and NIF graph from
`LexOTexts`, detaches the text from its corpus, and removes its persisted files.
In `LexOLexica` it first identifies every attestation typed as
`frac:Attestation` or linked through `frac:attestation` in that document's
attestation graph. It then removes every statement in the repository that uses
one of those resources as subject or object, including cross-graph references,
before clearing the document's attestation and annotation graphs. When a removed
`frac:attestation` link occurred in another valid per-document graph, the
observable's existing frequency is recalculated from its remaining typed
attestations; the frequency resource is removed when the result is zero.
Resources and graphs belonging to other texts are otherwise preserved.

`DELETE /service/texts/bulk` starts the same deletion policy for multiple texts
in a background job. The JSON body contains a non-empty `fileIds` list of unique
identifiers:

```json
{"fileIds": ["text-a", "text-b", "text-c"]}
```

The service validates the complete request and returns HTTP `202` with a
`bulkId`. Poll `GET /service/texts/deletions/{bulkId}/status` to obtain the
aggregate counters and the ordered, independent outcome of every text. Item
states are `PENDING`, `RUNNING`, `DELETED`, `NOT_FOUND`, or `FAILED`; aggregate
states are `PENDING`, `RUNNING`, `COMPLETED`, `PARTIALLY_COMPLETED`, or `FAILED`.
A failure for one text does not prevent later texts from being processed.
`NOT_FOUND` is an idempotent successful outcome and does not make the aggregate
job fail.

Deletion is sequential inside the asynchronous job so that every item can run
the complete single-text cleanup without concurrent destructive operations.
The default limit is 100 identifiers and can be changed with the JVM system
property `lexo.text.maxBulkDeleteFiles`. Job status is held in memory and is no
longer available after an application restart.

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

The observable must be stored in its category named graph: lexical entries,
forms, and senses use an ISO-language-specific `lexica/{language}` graph;
lexical concepts use the fixed `lexicalConcept` graph. The legacy `lexica`
graph, the default graph, unrelated named graphs, and category/graph mismatches
are rejected.

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
non-empty `observables` object list:

```json
{
  "value": "gli stessi diritti",
  "start": 42,
  "end": 60,
  "observables": [
    {
      "observable": "https://lexo.ilc.cnr.it#LexO_entry1",
      "metadata": [
        {
          "property": "https://example.org/vocabulary/source",
          "values": [
            {"value": "https://example.org/source/1", "type": "iri"},
            {"value": "fonte primaria", "type": "literal", "language": "it"}
          ]
        }
      ]
    },
    {
      "observable": "https://lexo.ilc.cnr.it#LexO_sense1"
    }
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

Every `observables` item requires its own `observable` IRI and may include
`metadata` using the common `{property, values}` entity metadata shape. Each
accepted property/value pair is written with that observable's newly created
attestation IRI as subject in the per-document attestation graph. IRI, plain,
language-tagged, and typed literal values are supported. The global protected
predicate policy applies, and invalid metadata rejects the complete batch
before any attestation or locus is persisted.

Both creation services also maintain one FRAC frequency object for every
observable and specific text in the same per-document attestation named graph:

```turtle
<observable> frac:frequency [
    a frac:Frequency ;
    rdf:value "2"^^xsd:int ;
    frac:observedIn <text-context>
] .
```

`frac:observedIn` identifies the resolved NIF reference context, even when the
creation request names a containing corpus. A new object starts with the number
of attestations created for that text; a pre-existing value is incremented by
the batch size. Creation and paginated retrieval JSON items expose the resulting
integer in `frequency`.

`POST /service/attestations/{fileId}` returns the attestations of one text as a
paginated JSON response. `observable` optionally restricts the result to the
exact IRI of one supported observed lexical entity; `observableType` and
`author` optionally filter its RDF type and the exact `dcterms:creator` value.
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
limited to 50 nodes and five levels. The `observable`, `author`, and
`observableType` query parameters are combined with one another and with the
JSON filter using `AND`. Exact observable matching is applied before pagination,
so `totalHits`, `limit`, and `offset` refer only to that observable. As in the
other common RDF payloads, each `rdfValues[].type` is ordinary
data and accepts `literal` or `iri` under the Jersey/MOXy binding.

For the common unfiltered request, including the optional exact `observable`
constraint, GraphDB performs the distinct count, deterministic ordering,
`LIMIT`, and `OFFSET` before any result enrichment. The selected page's FRAC
properties, frequencies, and NIF loci are then fetched in batches per named
graph instead of issuing one repository request for every field of every
attestation. Requests with creator, observable-type, or JSON filter conditions
retain the same semantics and also use batched page enrichment after filtering.

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
with `skos:definition`. When a sense has no definition, the linked entry label
is returned alone; when it has no usable entry label, the definition is returned
alone. The service resolves that entry from the asserted `ontolex:sense` link
inside the language named graph; `ontolex:isSenseOf` remains an OWL inference
and is not written by entry creation. Lexical concepts prefer `skos:prefLabel` and then
`rdfs:label`. Language tags are preserved in the compact `value@language`
format (for example, `casa@it`); missing values fall back to `"no label"`.

`PATCH /service/attestations/{fileId}/locus` changes the locus of one
attestation. `start` and `end` are Unicode code-point offsets on the canonical
`nif:isString`; the service derives the replacement text rather than accepting
it from the client. It relinks the selected attestation to the corresponding
deterministic `#char=start,end` IRI and updates `rdf:value` and
`dcterms:modified`. `frac:gloss` is updated by default and is preserved when
`updateGloss` is `false`.

```json
{
  "attestation": "https://lexo.ilc.cnr.it#LexO_attestation1",
  "start": 42,
  "end": 60,
  "updateGloss": true
}
```

The previous NIF locus is never rewritten or moved. A system locus or a locus
still shared by another attestation is preserved. An orphan previous locus is
removed only when it is marked
`prov:wasGeneratedBy lexo:AttestationService`. If the target IRI does not exist,
the service creates a new LexO-owned NIF locus with
`nif:anchorOf`, `nif:beginIndex`, `nif:endIndex`, and `nif:referenceContext`.
If the target already exists with the same identity data, it is reused without
changing its types or ownership marker; incompatible data produces
`LOCUS_CONFLICT`.

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

Attestation metadata mutations use the common `/service/metadata` endpoints
documented above with `entityType: "attestation"` and the required `fileId`.
The former attestation-specific metadata endpoint has been removed to keep one
contract and one implementation for every supported entity.

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
`retainedLoci`. Both deletion services recalculate all affected observable
frequencies; `PATCH /attestations/{fileId}/observable` does the same for the old
and replacement observables. When an observable no longer has attestations in
the text, its frequency object is removed. Mutation responses expose the final
values in a `frequencies` map keyed by observable IRI, using `0` for a removed
frequency.

## License

MIT

**Free Software, Hell Yeah!**
