# New lexical CRUD services

This document defines the shared contract for the gradual replacement of the
legacy lexical CRUD API. The legacy endpoint classes remain available while the
new API is introduced incrementally.

## Service class and route

All new lexical CRUD endpoints belong to
`src/main/java/it/cnr/ilc/lexo/service/Lexicon.java`. The resource class uses:

```java
@javax.ws.rs.Path("lexica")
@Api("Lexica")
```

Do not move, rename, or remove the legacy lexical endpoint classes as part of an
incremental CRUD rewrite.

## Repository and named graph

New lexical CRUD services use `RepositoryTarget.LEXICON`. Every application
read and write is restricted to the named graph of the resource language:

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

No new lexical CRUD service may read application data from, or write it to, the
default graph. Every persistence test must verify both the expected statements
in the language-specific lexical named graph, isolation from the graphs of
other languages, and the absence of application statements in the default
graph.

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
- use the lexical repository and the language-specific lexical named graph;
- validate the language against the bundled ISO 639 list;
- use the required lexical prefix mappings;
- use the centralized resource IRI and author rules;
- document the endpoint and every parameter in English;
- add tests for validation, response compatibility, language graph isolation,
  and the empty default graph;
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
The service rejects `rdf:type`, `rdf:value`, `rdfs:label`, `dcterms:creator`,
`dcterms:created`, `dcterms:modified`, `ontolex:otherForm`,
`ontolex:canonicalForm`, `ontolex:sense`, `ontolex:denotes`, and
`ontolex:evokes` from this field because these predicates are structurally
managed by the top-level request or by the service.

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
