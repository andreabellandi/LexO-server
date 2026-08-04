# New lexical CRUD services

This document defines the shared contract for the gradual replacement of the
legacy lexical CRUD API. The legacy endpoint classes remain available while the
new API is introduced incrementally. Contracts are cumulative: every endpoint
follows the shared lexical rules in this document plus the rules of the category
specified by the user. Category-specific persistence rules take precedence over
the general language-scoped graph rule.

## Service class and route

All new lexical CRUD endpoints belong to
`src/main/java/it/cnr/ilc/lexo/service/Lexicon.java`. The resource class uses:

```java
@javax.ws.rs.Path("lexica")
@Api("Lexica")
```

Do not move, rename, or remove the legacy lexical endpoint classes as part of an
incremental CRUD rewrite.

## Repository and named graphs

All new lexical CRUD services use `RepositoryTarget.LEXICON`. The selected named
graph depends on the service category.

### Language-scoped lexical resources

CRUD services for lexical resources whose persistence is scoped by language
restrict every application read and write to the named graph of the resource
language:

```text
https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}
```

The language code must occur in one of the first four columns of the bundled
`src/main/resources/iso639/lista_ufficiale_isocode_ISO_639.csv` file. Validation
is case-insensitive and the accepted code is normalized to lowercase through
the shared `Iso639LanguageValidator`; checking only that the value contains two
or three letters is not sufficient. Each distinct language therefore has its
own graph; for example, Italian data uses
`https://lexo.ilc.cnr.it/graphs/lexical/lexica/it`, while English data uses
`https://lexo.ilc.cnr.it/graphs/lexical/lexica/en`. New managers must obtain the
graph IRI through `LexiconCrudSupport.lexicalGraphUri(language)` instead of
constructing it directly.

Every persistence test for this category must verify the expected statements in
the language-specific lexical named graph, isolation from the graphs of other
languages, and the absence of application statements in the default graph.

### Lexical concepts and concept sets

Every future CRUD service in the lexical-concepts and concept-sets category
restricts all application reads and writes to this exact, fixed named graph:

```text
https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept
```

The graph belongs to `LexOLexica` and is not derived from a language code. Even
when an endpoint accepts a language for labels or other RDF values, it must not
append that language to the graph IRI or use
`https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}`. The fixed category
graph rule takes precedence over the general language-scoped rule above.

Persistence tests for this category must verify the expected statements in the
fixed `lexicalConcept` graph, the absence of those statements from every
language-specific lexical graph, and an empty default graph. Future endpoint
sections will define their individual request, response, validation, and RDF
contracts when the user requests each service; those details must not be
inferred from the legacy API.

No new lexical CRUD service in any category may read application data from, or
write it to, the default graph.

## Resource IRI generation

Every newly created resource IRI is the exact concatenation of:

1. `repository.lexicon.namespace` from `lexo-server.properties`;
2. `repository.instance.id` from `lexo-server.properties`;
3. a millisecond timestamp created with
   `new Timestamp(System.currentTimeMillis())` and formatted with
   `manager.operationTimestampFormat` from `lexo-server.properties`.

The formatted timestamp is normalized with:

```java
.replaceAll("\\s+", "")
.replaceAll(":", "_")
.replaceAll("\\.", "_")
```

New managers must reuse `LexiconCrudSupport.newResourceUri()` so that timestamp
creation and formatting are not reimplemented by individual services.

## Lexical prefixes

All new lexical CRUD queries use the following exact prefix mappings:

```sparql
PREFIX decomp:  <http://www.w3.org/ns/lemon/decomp#>
PREFIX vartrans: <http://www.w3.org/ns/lemon/vartrans#>
PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>
PREFIX synsem:  <http://www.w3.org/ns/lemon/synsem#>
PREFIX lexinfo: <http://www.lexinfo.net/ontology/3.0/lexinfo#>
PREFIX lime:    <http://www.w3.org/ns/lemon/lime#>
PREFIX lexicog: <http://www.w3.org/ns/lemon/lexicog#>
PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
```

Reuse centralized prefix declarations where available. Do not introduce local
aliases with different namespace IRIs; in particular, new services use LexInfo
3.0 rather than the older LexInfo 2.0 namespace still present in some legacy
resources.

## Author

Every new lexical endpoint exposes an optional `author` parameter. The service
first applies the existing authenticated-user resolution and then normalizes a
missing, empty, or whitespace-only value to the literal `anonymous`. New
services use the helper exposed by `Lexicon` rather than implementing their own
fallback.

## Global RDF metadata protection policy

The metadata contract uses one permanent predicate policy for every current and
future entity. Creation, common CRUD, and metadata serialization must all reuse
`MetadataPolicy`; entity-specific protected-predicate lists must not override or
weaken it.

No metadata property may belong to any of these namespaces:

```text
ontolex:  http://www.w3.org/ns/lemon/ontolex#
frac:     http://www.w3.org/ns/lemon/frac#
lime:     http://www.w3.org/ns/lemon/lime#
vartrans: http://www.w3.org/ns/lemon/vartrans#
synsem:   http://www.w3.org/ns/lemon/synsem#
skos:     http://www.w3.org/2004/02/skos/core#
decomp:   http://www.w3.org/ns/lemon/decomp#
```

The exact predicates `dcterms:creator`, `dcterms:created`, and
`dcterms:modified` are also protected because their values are managed by the
service. `rdf:type` and `rdf:value` remain protected as RDF structural
predicates. Other predicates, including other Dublin Core Terms and RDFS
predicates, are accepted unless a future global policy explicitly adds them.
Protected predicates are rejected on writes and omitted from metadata output
even when legacy data already contains them.

## Swagger documentation

Every endpoint has an English `@ApiOperation` annotation. Every individual
header, path, query, form, and body parameter has an English `@ApiParam`
annotation, including the optional `author` parameter. Follow the descriptive
style already used by the legacy services, for example:

```java
@ApiOperation(
        value = "Lexicon language creation",
        notes = "This method creates a new lexicon language and returns its id and some metadata")

@ApiParam(
        name = "lang",
        value = "language code (2 or 3 digits)",
        example = "en",
        required = true)
```

## Implementation checklist

For each new CRUD endpoint:

- preserve the legacy endpoint that it will eventually replace;
- keep the `service` to `manager` to persistence/query separation;
- use the lexical repository and the named graph prescribed by the category;
- for language-scoped categories, validate the language against the bundled ISO
  639 list;
- for lexical concepts and concept sets, use only the fixed `lexicalConcept`
  graph and never derive the graph IRI from a language;
- use the required lexical prefix mappings;
- use the centralized resource IRI and author rules;
- document the endpoint and every parameter in English;
- add tests for validation, response compatibility, isolation of the graph
  prescribed by the category, and the empty default graph;
- update API documentation, `HANDOFF.md`, and the `Unreleased` changelog when
  the endpoint becomes user-visible.

## POST `/lexica/entry`

This endpoint creates one lexical entry and all requested child resources in a
single transaction in `RepositoryTarget.LEXICON`. Its JSON body contains the
required `label`, `type`, and `language`, plus optional `pos`, `lemma`,
entry-level `metadata`, and `senses`. `author` is an optional query parameter
and follows the shared authenticated-user and `anonymous` fallback rule.

`type` accepts an absolute IRI or one of the exact supported compact prefixes
and must resolve to `ontolex:LexicalEntry` or a transitive subclass declared in
the language-specific graph or schema graph. `pos` follows the same IRI rules
and must be an individual whose RDF type is `lexinfo:PartOfSpeech` or a subclass.
The input language is validated and normalized before any repository write.

Entry-level `metadata` uses the same multivalued RDF shape as sense metadata: a
list of `{property, values}` groups, each with a non-empty `values` list. It
preserves IRI, plain literal, language-tagged literal, and typed literal values.
The service applies the global RDF metadata protection policy above. In
particular, every property in a protected namespace is rejected, including
future vocabulary terms that are not otherwise known to the service.

Within the selected language graph, the manager deterministically reuses the
first IRI typed `lime:Lexicon` whose `lime:language` or `dcterms:language`
literal equals the normalized input code. A lexicon with no language or a
different language is not reused. If no matching lexicon exists, the manager
creates one with creator, created/modified timestamps and the first
`lime:entry` relation. Workflow status is not assigned to a `lime:Lexicon`.
Existing lexica receive only the new `lime:entry` relation.

Every entry receives its requested RDF type, creator, created/modified
timestamps, language-tagged `rdfs:label`, and `lexo:status "working"`. Optional
`pos` is stored with `lexinfo:partOfSpeech`. `lemma=true` creates an
`ontolex:Form`, links it with `ontolex:canonicalForm`, and writes the label as
the language-tagged `ontolex:writtenRep`. Accepted entry metadata is written on
the entry itself in the same language-specific named graph.

Each item in `senses` creates an `ontolex:LexicalSense` linked through
`ontolex:sense`, with creator and created/modified timestamps. Both `properties`
and `metadata` are lists of `{property, values}` groups, and each group has a
non-empty, multivalued `values` list. Both preserve multiple IRI, plain literal,
language-tagged literal, and typed literal values. Service-managed type,
creator, timestamp, and status predicates cannot be supplied through
`properties`. In addition, `metadata` excludes
`rdf:type`, `rdf:value`, creator and timestamps, `skos:definition`,
`ontolex:reference`, and `ontolex:isLexicalizedSenseOf`; these structural or
semantic relations belong to the service-managed triples or `properties`.

Validation is completed before adding statements, and every failure rolls back
the transaction. Stable error prefixes include `MISSING_ENTRY`, `MISSING_LABEL`,
`MISSING_TYPE`, `MISSING_LANGUAGE`, `INVALID_LANGUAGE`, `INVALID_ENTRY_TYPE`,
`INVALID_PART_OF_SPEECH`, `INVALID_RDF_VALUE_TYPE`,
`RESERVED_ENTRY_METADATA_PROPERTY`, `MISSING_ENTRY_METADATA_VALUES`, and
`RESERVED_SENSE_METADATA_PROPERTY`.

Success returns HTTP `201` with the entry IRI in `Location`. The JSON response
contains `lexicon`, `lexiconCreated`, `entry`, optional `canonicalForm`, the
created `senses`, normalized `language`, `status`, and `created` timestamp.

## GET `/lexica/{language}/entries`

This endpoint returns the complete list of lexical entries linked through
`lime:entry` from a `lime:Lexicon` whose `lime:language` or `dcterms:language`
matches the required ISO 639 path parameter, in that language's named graph. It
accepts optional `key`, `searchMode`, `case`, `type`, `pos`, `author`, `status`,
and `senseNumber` query parameters. Missing, empty, or whitespace-only filters
are ignored; all populated filters are combined with logical `AND`.

When `key` is present, its candidate text is selected with an exclusive
fallback. The service first uses `rdfs:label`; it considers canonical-form
`ontolex:writtenRep` values only if the entry has no `rdfs:label`; it considers
other-form written representations only if the entry has neither a label nor
an `ontolex:canonicalForm` relation. An existing canonical form without a
written representation therefore prevents fallback to `ontolex:otherForm`.
`searchMode` accepts `startsWith`, `contains`, or `endsWith` and defaults to
`startsWith`. `case` accepts `sensitive` or `insensitive` and defaults to
`sensitive`.

`type` must be an absolute IRI that exists as a resource in the selected
language graph or schema graph; matching uses the entry's exact `rdf:type`.
`pos` must be an absolute IRI typed as `lexinfo:PartOfSpeech` or a transitive
subclass in those graphs. `author` performs an exact literal match. `status`
accepts `working`, `completed`, or `revised`. `senseNumber` is the exact count
of distinct `ontolex:sense` objects and must be an integer greater than or equal
to zero.

The response is a JSON array ordered by the effective label and then by entry
IRI. Each compact item exposes `entry`, `label`, `type`, `pos`, `author`,
`status`, and `senseNumber`. Entries without any effective label remain visible
when `key` is absent. The endpoint does not apply implicit pagination.

Stable error prefixes include `INVALID_LANGUAGE`, `INVALID_SEARCH_MODE`,
`INVALID_CASE`, `INVALID_TYPE_IRI`, `TYPE_NOT_FOUND`,
`INVALID_PART_OF_SPEECH_IRI`, `INVALID_PART_OF_SPEECH`, `INVALID_STATUS`, and
`INVALID_SENSE_NUMBER`. A missing type returns HTTP `404`; an invalid
part-of-speech resource returns HTTP `422`; malformed filter values return
HTTP `400`.

## PATCH `/lexica/entries/status`

This endpoint atomically changes the workflow status of one or more lexical
entries in one language-specific named graph. Its JSON body contains the
required `language` and a non-empty `entries` list. Every list item contains
the absolute entry IRI, the expected `fromStatus`, and the requested
`toStatus`. `author` is an optional query parameter and follows the shared
authenticated-user and `anonymous` fallback rule.

The only status values are `working`, `completed`, and `revised`. Legal
transitions are `working` to `completed`, `completed` to `working`, `completed`
to `revised`, and `revised` to `completed`. Repeating the current state and
jumping directly between `working` and `revised` are rejected. `fromStatus`
must match the stored value, preventing a client with stale state from
overwriting a newer transition.

Every target must exist in the selected graph and be typed as
`ontolex:LexicalEntry` or a transitive subclass declared in the language or
schema graph. It must have exactly one supported literal `lexo:status`. The
complete batch is validated before writing; any invalid item rolls back the
whole transaction. A successful transition replaces `lexo:status`, updates
`dcterms:modified`, and records the resolved account in
`lexo:statusChangedBy`. No status triple is written on the containing
`lime:Lexicon`.

Stable error prefixes include `MISSING_STATUS_CHANGE`,
`MISSING_STATUS_ENTRIES`, `INVALID_STATUS`, `DUPLICATE_STATUS_ENTRY`,
`ENTRY_NOT_FOUND`, `UNSUPPORTED_STATUS_RESOURCE_TYPE`, `STATUS_MISMATCH`,
`STATUS_TRANSITION_NOT_ALLOWED`, `INVALID_STATUS_CARDINALITY`, and
`INVALID_CURRENT_STATUS`. Conflicts return HTTP `409`; a resource absent from
the selected graph returns `404` and an unsupported RDF type returns `422`.

Success returns HTTP `200`. The JSON response contains normalized `language`,
resolved `author`, the shared `modified` timestamp, and one result item per
entry with its IRI, previous status, and new status.

## POST `/lexica/lexicalConcept`

This endpoint atomically creates one lexical concept in `RepositoryTarget.LEXICON`
and exclusively in the fixed named graph
`https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept`. `author` is an optional
query parameter and follows the shared authenticated-user and `anonymous`
fallback rule.

The JSON body requires a non-empty `label` list. `label`, `alternativeLabel`,
`hiddenLabel`, and `definition` contain `{label, language}` pairs; optional
lists may be omitted or empty. Text values must be non-blank. Languages are
validated against the first four columns of the bundled ISO 639 list,
case-insensitively, and normalized to lowercase for RDF language tags.

Optional `senseId` is a list of absolute IRIs, while `parent` and `conceptSetId`
are single absolute IRIs. Every supplied resource must already exist as a
subject in the fixed lexical-concept graph. Senses must have exact type
`ontolex:LexicalSense`, the parent must have exact type
`ontolex:LexicalConcept`, and the concept set must have exact type
`ontolex:ConceptSet`. Validation does not consult a language-specific graph or
the default graph.

The created resource receives type `ontolex:LexicalConcept`, the resolved
`dcterms:creator`, and one shared `xsd:dateTime` value for `dcterms:created` and
`dcterms:modified`. The service writes every preferred label with
`skos:prefLabel`, alternative label with the requested
`skos:alternativeLabel`, hidden label with `skos:hiddenLabel`, and definition
with `skos:definition`. It writes each sense through
`ontolex:isLexicalizedSenseOf`, the optional parent through `skos:broader`, and
the optional concept set through `skos:inScheme`.

The complete request is validated before any statement is added, and every
failure rolls back the transaction. Stable error prefixes include
`MISSING_LEXICAL_CONCEPT`, `MISSING_LABEL`, `INVALID_LABEL`,
`MISSING_LABEL_VALUE`, `INVALID_LABEL_LANGUAGE`, `INVALID_SENSE_IRI`,
`INVALID_PARENT_IRI`, `INVALID_CONCEPT_SET_IRI`, `SENSE_NOT_FOUND`,
`INVALID_SENSE_TYPE`, `PARENT_NOT_FOUND`, `INVALID_PARENT_TYPE`,
`CONCEPT_SET_NOT_FOUND`, and `INVALID_CONCEPT_SET_TYPE`. Shape and IRI errors
return HTTP `400`, absent linked resources return `404`, and wrong RDF types
return `422`.

Success returns HTTP `201` with the created lexical concept IRI in `Location`.
The JSON response contains `lexicalConcept`, `author`, `created`, `senseId`,
the optional `parent` and `conceptSetId`, and canonical `metadata`.

The optional `metadata` input uses the common list of `{property, values}`
groups shared by lexical entries, attestations, and future entities. Each value
is an IRI or literal with an optional language or datatype. Empty value lists
are rejected during creation. The global RDF metadata protection policy is
applied without lexical-concept-specific exceptions.

## Common metadata CRUD

`GET`, `PATCH`, and `DELETE /metadata` expose the common RDF metadata model for
`lexicalEntry`, `lexicalConcept`, and `attestation`. Graph selection is derived
from the entity kind plus validated `language` or `fileId`; callers cannot pass
an arbitrary graph. Resource existence and RDF type are checked in the resolved
graph before every read or mutation.

`PATCH` performs atomic property-wise replacement and treats `values: []` as
deletion. `DELETE` removes an explicit property list. Both update
`dcterms:modified`. Input and output preserve multiple IRIs, plain literals,
language-tagged literals, and typed literals in the same `{property, values}`
shape. The former attestation-specific metadata mutation endpoint has been
removed; attestation metadata now uses this common API exclusively.
