# WordNet REST services

The WordNet API is independent from the legacy and incremental `/lexica` APIs.
It is exposed below `/service/wordnet`, uses Swagger tag `Wordnet`, and stores
all application and schema data only in the third GraphDB repository
`LexOWordnets` through `RepositoryTarget.WORDNET`.

There is no automatic migration from `LexOLexica` to `LexOWordnets`. Existing
experimental WordNet data needs a separately reviewed migration procedure. This
release intentionally has no WordNet import, export or validation endpoint.

## Repository and graph topology

| Target | Default repository | Content |
| --- | --- | --- |
| `LEXICON` | `LexOLexica` | ordinary lexical data, attestations and annotations |
| `TEXT` | `LexOTexts` | NIF texts, corpora and text organization |
| `WORDNET` | `LexOWordnets` | WordNet application graphs, schema and bootstrap marker |

One WordNet owns exactly one application graph:

```text
https://lexo.ilc.cnr.it/graphs/wordnet/{language}/{wordnetId}
```

The graph is selected only by `WordnetGraphResolver` after ISO-language and
slug validation. It contains the root `lime:Lexicon`, entries, forms, senses,
synsets, VarTrans relations, named auxiliary resources, RDF member-list cells,
arbitrary metadata and audit triples. Reads use explicit contexts with
`includeInferred=false`; writes always pass that context. The client cannot
select a repository or raw graph IRI, and the default graph remains empty.

Schema and bootstrap data use distinct graphs in the same repository:

```text
https://lexo.ilc.cnr.it/graphs/wordnet/schema
https://lexo.ilc.cnr.it/graphs/wordnet/bootstrap
```

Discovery ignores both. A schema reimport replaces only the schema graph and
preserves every application graph.

## Configuration and bootstrap

| Property | Packaged default |
| --- | --- |
| `WordnetGraphDb.url` | `http://localhost:7200` |
| `WordnetGraphDb.repository` | `LexOWordnets` |
| `WordnetGraphDb.size` | `5` |
| `WordnetGraphDb.namedGraphBase` | `https://lexo.ilc.cnr.it/graphs/wordnet/` |
| `WordnetGraphDb.schemaNamedGraph` | `https://lexo.ilc.cnr.it/graphs/wordnet/schema` |
| `Bootstrap.wordnet.label` | `LexO WordNets` |
| `Bootstrap.wordnet.repositoryConfig` | `bootstrap/repositories/wordnet-repository.ttl` |
| `Bootstrap.wordnet.schema.manifest` | `bootstrap/schema/wordnet-schema-imports.json` |
| `Bootstrap.wordnet.metadata.namedGraph` | `https://lexo.ilc.cnr.it/graphs/wordnet/bootstrap` |

Only the WordNet server URL may fall back to `GraphDb.url` when omitted. The
repository ID, pool size and graph base never fall back to lexical settings.
Environment overrides use the standard conversion, for example
`LEXO_WORDNET_GRAPH_DB_URL`.

Startup creates `LexOWordnets` from its GraphDB 10 template with the
`owl-horst-optimized` ruleset. It does not run lexical connector-index setup in
that repository. Schema import is transactional, checksum-driven and entirely
local at runtime. The WordNet repository and schema phase runs before lexical
or text bootstrap work, so a WordNet repository, checksum, RDF/XML or schema
validation failure cannot modify `LexOLexica` or `LexOTexts` in that startup
attempt.

The source GWA ontology is stored byte-for-byte as
`bootstrap/schema/wn-lemon-1.4.ttl` with SHA-256:

```text
deecbb23eb84ff9f9a3d40adb6077810f2a0bd6ab9ccedd7d0b3b26af3f422bb
```

`wn-lemon-1.4.rdf` is a graph-isomorphic RDF/XML conversion used by the existing
manifest importer. The manifest also includes local LIME, OntoLex, SKOS,
SynSem and VarTrans dependencies. A local API-profile resource records version
`1.4`, source, namespace, checksum and runtime artifact without changing the
upstream ontology's own `owl:versionInfo "Development"`. The schema endpoint
reads this profile and enumerates PoS and both relation-category lists from the
schema graph.

## Endpoint catalog

| Methods | Path |
| --- | --- |
| `GET`, `POST` | `/service/wordnet` |
| `GET`, `PATCH`, `DELETE` | `/service/wordnet/{wordnetId}` |
| `GET` | `/service/wordnet/schema` |
| `GET`, `POST` | `/service/wordnet/{wordnetId}/entries` |
| `GET`, `PATCH`, `DELETE` | `/service/wordnet/{wordnetId}/entries/{id}` |
| `GET` | `/service/wordnet/{wordnetId}/entries/{id}/status` |
| `PATCH` | `/service/wordnet/{wordnetId}/entries/status` |
| `GET`, `POST` | `/service/wordnet/{wordnetId}/forms` |
| `GET`, `PATCH`, `DELETE` | `/service/wordnet/{wordnetId}/forms/{id}` |
| `GET`, `POST` | `/service/wordnet/{wordnetId}/senses` |
| `GET`, `PATCH`, `DELETE` | `/service/wordnet/{wordnetId}/senses/{id}` |
| `GET`, `POST` | `/service/wordnet/{wordnetId}/synsets` |
| `GET`, `PATCH`, `DELETE` | `/service/wordnet/{wordnetId}/synsets/{id}` |
| `GET`, `POST` | `/service/wordnet/{wordnetId}/relations` |
| `GET`, `PATCH`, `DELETE` | `/service/wordnet/{wordnetId}/relations/{id}` |

All operations accept optional `author`; authenticated identity takes
precedence and a missing or blank value becomes `anonymous`. Collection GETs
support validated conjunctive filters and `{items,total,limit,offset}` paging,
with default limit 25 and maximum 100. Detail GETs accept `sections`; omitted or
`all` includes all direct sections, while `core` or an explicit comma-separated
list bounds the projection. Empty, unknown and duplicate names are rejected.
Sense and Synset detail requests that include `relations` may also use
`direction=in|out|both` (default `both`); supplying `direction` without that
section is rejected.

Routed resource IDs are unpadded Base64URL encodings of full RDF IRIs. The root
route instead uses the validated, globally unique `wordnetId` slug. POST returns
201 with the absolute REST `Location`; PATCH returns 200 and DELETE returns 204.
Errors use `{"error":{"code":"...","message":"...","details":[]}}`.

## RDF model and integrity

- A root is a `lime:Lexicon` with one normalized `lime:language`.
- An entry is an `ontolex:LexicalEntry` attached by `lime:entry`, with validated
  `wn:partOfSpeech`, exactly one canonical form and controlled Entry workflow.
- Forms are named `ontolex:Form` resources. `ontolex:writtenRep` carries the
  WordNet language tag. Tags and pronunciations are named resources; audio is
  an `xsd:anyURI` literal and `wn:phonemic` is boolean.
- A synset is an `ontolex:LexicalConcept` with `skos:inScheme`, PoS, optional
  external ILI IRI, named definitions/examples and one `wn:memberList`.
- A sense is an `ontolex:LexicalSense` owned by exactly one Entry and referring
  to exactly one Synset. Its positive `wn:n` is unique within the Entry.
- `wn:memberList` is an RDF list of Sense IRIs, not Entry IRIs. Sense create,
  move and delete rebuild only affected lists and verify that list members equal
  the set of senses referring to the Synset. Cons cells are the only blank nodes
  created by the API, and every cell must be exclusively owned by that list
  before a rebuild or deletion.
- Relations are named `vartrans:ConceptualRelation` or
  `vartrans:SenseRelation` resources. Source, target and category are explicit;
  category type is checked in the schema graph. Duplicate explicit tuples are
  rejected and no inverse or SKOS assertion is materialized.

Multi-resource changes use one RDF4J transaction. Deletion refuses resources
with independent dependants, incident relations, shared children or external
references. `expectedModified` on ordinary PATCH/DELETE is an exact concurrency
guard checked in the mutation transaction; Entry workflow `fromStatus` is
validated in the same transaction as the batch update. No application statement
is written to the default, schema, bootstrap or another WordNet graph.
Mutation targets and relation filters must also satisfy structural ownership:
an explicitly typed but orphaned Entry, malformed Synset or inconsistent Sense
is rejected with `GRAPH_INTEGRITY_CONFLICT`. This includes Entry workflow
reads/transitions and the `sourceSenseId` reference of a Synset definition, so
neither path can operate on a merely typed orphan resource. On Sense PATCH,
`subcategorizationIds: []` clears links, omission preserves them, and explicit
`null` is invalid.

## Metadata, annotations and nested PATCH

Every main and named child resource uses the common `RdfMetadataProperty`,
`RdfMetadataValue`, `MetadataPatchRequest`, `RdfMetadataCodec` and
`MetadataPolicy` contract. Creation accepts a metadata property list; PATCH
supports complete property replacement and exact `addValues`/`removeValues`.
IRI, plain, language-tagged and typed literal values remain RDF values.

All `wn:` predicates are protected from arbitrary metadata. The native fields
`notes`, `wordnetStatus` and `confidenceScore` manage `wn:note`, `wn:status` and
`wn:confidenceScore` directly; confidence is an `xsd:decimal` in `[0,1]`.
Managed audit, RDF structural and LexO workflow predicates are protected too.
`rdfs:label`, allowed Dublin Core terms and the permanent `skos:note` exception
remain available through the common metadata model.

Nested collections use flat, presence-aware updates:

```json
{
  "add": [{"text":"new","language":"en","metadata":[]}],
  "update": [{"id":"...","text":"changed","metadata":{"addValues":[]}}],
  "remove": ["..."]
}
```

Omitted fields remain unchanged; explicit null removes optional fields. Updates
preserve child IRIs and apply structural fields, native annotations and metadata
in the owner's transaction. A child ID cannot occur in multiple operations and
ownership is checked in the selected WordNet graph.

## Entry workflow

`wordnetStatus` is the unrestricted native annotation `wn:status`. It is
independent from Entry-only `workflowStatus`, stored as `lexo:status`. Entries
start in `working`; allowed transitions are:

```text
working -> completed
completed -> working
completed -> revised
revised -> completed
```

`GET .../entries/{id}/status` returns explicit status and `statusChangedBy`.
`PATCH .../entries/status` validates a nonempty batch, unique IDs, every
Entry's ownership, `fromStatus` and every transition before writing, then
changes the whole batch atomically with one timestamp. General Entry PATCH
cannot change workflow, and the implementation does not call ordinary
`/lexica/entries/status` services.

## Deployment and verification

The resolver enforces graph ownership, but concurrent creation of the same
`wordnetId` is coordinated only inside one JVM. Run WordNet mutations with a
single active LexO-server writer unless an external distributed lock is added.

Unit and in-memory RDF4J tests cover routing, graph isolation, empty default
graph, ontology checksum/isomorphism, idempotent schema import, CRUD, member
lists, relations, rollback, nested metadata, workflow and REST annotations.
Remote GraphDB end-to-end verification requires three dedicated test
repositories and must never target development or production data.
