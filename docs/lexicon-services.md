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
.replaceAll(":", "*")
.replaceAll("\\.", "*")
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
required `label`, `type`, and `language`, plus optional `pos`, `lemma`, and
`senses`. `author` is an optional query parameter and follows the shared
authenticated-user and `anonymous` fallback rule.

`type` accepts an absolute IRI or one of the exact supported compact prefixes
and must resolve to `ontolex:LexicalEntry` or a transitive subclass declared in
the language-specific graph or schema graph. `pos` follows the same IRI rules
and must be an individual whose RDF type is `lexinfo:PartOfSpeech` or a subclass.
The input language is validated and normalized before any repository write.

Within the selected language graph, the manager deterministically reuses the
first IRI typed `lime:Lexicon` whose `lime:language` or `dcterms:language`
literal equals the normalized input code. A lexicon with no language or a
different language is not reused. If no matching lexicon exists, the manager
creates one with creator, created/modified timestamps, `lexo:status "working"`,
and the first `lime:entry` relation. Existing lexica receive only the new
`lime:entry` relation.

Every entry receives its requested RDF type, creator, created/modified
timestamps, language-tagged `rdfs:label`, and `lexo:status "working"`. Optional
`pos` is stored with `lexinfo:partOfSpeech`. `lemma=true` creates an
`ontolex:Form`, links it with `ontolex:canonicalForm`, and writes the label as
the language-tagged `ontolex:writtenRep`.

Each item in `senses` creates an `ontolex:LexicalSense` linked through
`ontolex:sense`, with creator and created/modified timestamps. `properties` is
a list of property/value groups; `metadata` is an object keyed by property IRI.
Both preserve multiple IRI, plain literal, language-tagged literal, and typed
literal values. Service-managed type, creator, timestamp, and status predicates
cannot be supplied through `properties`. In addition, `metadata` excludes
`rdf:type`, `rdf:value`, creator and timestamps, `skos:definition`,
`ontolex:reference`, and `ontolex:isLexicalizedSenseOf`; these structural or
semantic relations belong to the service-managed triples or `properties`.

Validation is completed before adding statements, and every failure rolls back
the transaction. Stable error prefixes include `MISSING_ENTRY`, `MISSING_LABEL`,
`MISSING_TYPE`, `MISSING_LANGUAGE`, `INVALID_LANGUAGE`, `INVALID_ENTRY_TYPE`,
`INVALID_PART_OF_SPEECH`, `INVALID_RDF_VALUE_TYPE`, and
`RESERVED_SENSE_METADATA_PROPERTY`.

Success returns HTTP `201` with the entry IRI in `Location`. The JSON response
contains `lexicon`, `lexiconCreated`, `entry`, optional `canonicalForm`, the
created `senses`, normalized `language`, `status`, and `created` timestamp.
