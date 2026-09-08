# REST API Specification: Export OntoLex-FrAC Attestations as Web Annotation JSON-LD

Architecture-adapted specification for LexO-server. This document replaces the supplied draft. It specifies a new read-only export; it does not change existing REST contracts or repository data.

## 1. Objective

Export FrAC attestations as W3C Web Annotation JSON-LD, with optional explicitly defined LexO extensions preserving existing metadata and provenance values. Support one, multiple, or all supported per-document attestation graphs. Do not mechanically reproduce FrAC/NIF structural properties in the result.

## 2. Existing implementation is authoritative

The reference retrieval operations are `POST /service/attestations/{fileId}` and `POST /service/attestations/by-observable`. There is no separate single-attestation retrieval endpoint in `Attestations.java`.

Use the representation of an individual `AttestationListItem`, inheriting `AttestationBase`. Its `metadata` is a map from absolute property IRI to lists of `AttestationMetadataValue` objects. Each value contains `value`, `type` (`literal` or `iri`), and optional `language` and `datatype`. Numeric and date lexical forms must retain the existing representation.

`creator`, `creationDate`, and `lastUpdate` are separate DTO fields, not entries in `metadata`.

Reuse or extract shared support from `AttestationManager.readMetadata(Model, Resource)`, its existing graph-grouped loading, `MetadataPolicy`, and `RdfMetadataCodec`. Do not create a second metadata policy, whitelist, or value conversion mechanism. The common `/service/metadata` API shares the policy and codec but has a different response shape; preserve the attestation DTO shape here.

## 3. Endpoint

```http
GET /service/attestations/export/web-annotation
```

Add the method to `it.cnr.ilc.lexo.service.Attestations`, retaining its existing `@Path("attestations")` and Swagger category. `/service` is the application's Jersey base path, relative to the WAR context.

Successful responses use `application/ld+json; charset=UTF-8` and `Content-Disposition: attachment; filename="attestations-web-annotation.jsonld"`. Reuse `checkKey` and existing authentication behavior. No new persistence endpoint or annotation graph is introduced.

## 4. Parameters

### `context`

Optional, repeatable, absolute IRI of a supported attestation document graph in `RepositoryTarget.LEXICON`. It never means `frac:observedIn`, a NIF context IRI, or a NIF graph IRI.

With no value, select all existing supported attestation document graphs. With values, select their deduplicated set. Validate the graph category and derive its `fileId` using `LexicalNamedGraphs` and the existing graph/fileId validation rules. Reject blank, malformed, relative, or unsupported graph IRIs with 400. A syntactically valid supported graph that does not exist contributes no results; do not return 404 for it.

### `includeMetadata`

Optional strict boolean, default `false`. Accept `true` and `false` case-insensitively; an explicitly empty or other value returns 400.

When false, return only the Web Annotation core. When true, additionally return the exact existing `metadata` map and a separate `lexoProvenance` object containing the existing `creator`, `creationDate`, and `lastUpdate` values. This preserves creator without incorrectly putting it inside custom metadata or interpreting it as a Web Annotation Agent.

## 5. Source repositories and graphs

| Content | Repository target | Default named graph |
|---|---|---|
| Attestation type, FrAC links, custom metadata, provenance | `LEXICON` (`LexOLexica`) | `https://lexo.ilc.cnr.it/graphs/lexical/attestations/documents/{fileId}` |
| NIF loci, reference context, canonical string | `TEXT` (`LexOTexts`) | `https://lexo.ilc.cnr.it/graphs/nif/documents/{fileId}` |
| Observable definitions | `LEXICON` | ISO-language lexical graph, or the fixed lexical-concept graph |

All graph and repository names must use existing configuration/support; the defaults above are documentation examples. Keep default, legacy, schema, bootstrap, annotation, and unrelated graphs outside attestation selection. Do not infer attestation existence from the union/default graph; select physically stored types with inferred statements disabled.

The FrAC attestation and incoming `frac:attestation` links belong to the attestation graph. `frac:locus` identifies a locus in the corresponding TEXT document graph. `frac:observedIn` may identify the NIF context, a containing corpus, or a source text; it is not necessarily the context containing `nif:isString`.

## 6. JSON-LD structure and extension context

Use an ordinary top-level `@graph`, never an `attestations` wrapper. Minimal exports use:

```json
{"@context":["http://www.w3.org/ns/anno.jsonld"],"@graph":[]}
```

Metadata-enabled exports use JSON-LD 1.1 and this additional context:

```json
{
  "@context": [
    "http://www.w3.org/ns/anno.jsonld",
    {
      "@version": 1.1,
      "metadata": {
        "@id": "https://lexo.ilc.cnr.it/vocabulary/web-annotation#metadata",
        "@type": "@json"
      },
      "lexoProvenance": {
        "@id": "https://lexo.ilc.cnr.it/vocabulary/web-annotation#provenance",
        "@type": "@json"
      }
    }
  ],
  "@graph": []
}
```

These two documented extension properties are necessary to preserve the native JSON structures without treating DTO `type` fields as RDF classes or creator strings as IRIs. They are JSON literals, not a second RDF encoding of their contents. The core remains Web Annotation; metadata-enabled output is Web Annotation with LexO extensions, rather than a document using exclusively W3C vocabulary terms. Do not redefine W3C terms. No repository schema/bootstrap change is needed for this read-only serialization.

## 7. Core mapping

Every selected attestation becomes one annotation with its existing absolute IRI as `id`, `type: "Annotation"`, `motivation: "identifying"`, one or more lexical Bodies, and one textual Target. Never generate a new attestation IRI or write the exported annotation to a repository.

## 8. Body

Collect every incoming `?observable frac:attestation ?attestation` link physically present in the selected attestation graphs. Export absolute IRIs as `{"id":"..."}`; use a single object for one Body and an IRI-sorted array for multiple Bodies. Do not copy the paginated reader's single-observable selection, which can discard additional Bodies. Do not require optional labels or metadata. A missing or non-IRI Body is a conversion error.

## 9. Target and source

Use `type: "SpecificResource"`. Derive `source` by URI fragment removal from the locus IRI. If the locus provides no usable textual base, use the resolved NIF reference-context IRI without its fragment. Only use `observedIn` as a fallback when it is verified to identify that actual text context; never use the IRI of a containing corpus as the textual source.

For standard LexO loci, validate agreement between the locus base and resolved context base. Do not hard-code `/resources/texts/`. The identified representation is the canonical plain text corresponding to `nif:isString`, not the uploaded CommonMark/JSON or TXT front matter. LexO's existing `/service/texts/{fileId}/canonical` operation provides that representation; this export does not add a new dereferencing service or change stored resource IRIs.

## 10. Selectors

Every Target has exactly these three selectors, in this serialization order: `TextPositionSelector`, `TextQuoteSelector`, `FragmentSelector`. All refer to the same canonical segment. No reduced-selector mode is introduced.

## 11. TextPositionSelector

Read `start`/`end` from a valid locus `#char=start,end` fragment and/or its `nif:beginIndex` and `nif:endIndex` in TEXT. When both are present they must agree. Multiple distinct values, incomplete offset pairs, or a malformed `char=` fragment are errors. Serialize offsets as JSON integers.

## 12. Unicode

All positions and context-window lengths count Unicode code points, not UTF-16 units or bytes. Reuse/extract the existing code-point-aware support in `AttestationManager` rather than introducing a divergent algorithm. Preserve canonical whitespace, line endings, BOM, and Unicode form. Do not normalize or render the canonical string again during export.

## 13. TextQuoteSelector

Resolve the text as specified in section 20 and compute `exact = canonical[start:end]` using code-point indexing. `nif:isString` is authoritative. Do not fall back to `rdf:value`, `frac:gloss`, `nif:anchorOf`, or metadata when the canonical text is absent. Optional consistency checks must not replace the canonical substring.

For the supplied example, `[1316,1375)` contains 59 code points and its fixture must yield `Una persona importante, cioè una persona che è determinata.`.

## 14. Prefix and suffix

Set `webAnnotation.quoteContextLength=50` through the existing `LexOProperties` configuration mechanism. Require a non-negative integer; invalid configuration is a server/configuration error, not a request error. A value of zero produces empty prefix/suffix strings.

Compute `prefix = canonical[max(0,start-window):start]` and `suffix = canonical[end:min(length,end+window)]`, all in code points and with overflow-safe arithmetic. Include empty boundary strings and never trim them.

## 15. FragmentSelector

Reconstruct the fragment from the same validated positions:

```json
{"type":"FragmentSelector","value":"char=1316,1375","conformsTo":"http://tools.ietf.org/rfc/rfc5147"}
```

## 16. Motivation

Include `motivation: "identifying"`, defined in one centralized mapper constant so it can later change without affecting extraction. Do not invent a FrAC-specific motivation.

## 17. Minimal export example

This complete synthetic example uses canonical text `Una persona.` and offsets `[4,11)`:

```json
{
  "@context": ["http://www.w3.org/ns/anno.jsonld"],
  "@graph": [{
    "id": "https://example.org/attestation/1",
    "type": "Annotation",
    "motivation": "identifying",
    "body": {"id": "https://example.org/lexicon/persona"},
    "target": {
      "type": "SpecificResource",
      "source": "https://example.org/text/1",
      "selector": [
        {"type": "TextPositionSelector", "start": 4, "end": 11},
        {"type": "TextQuoteSelector", "exact": "persona", "prefix": "Una ", "suffix": "."},
        {"type": "FragmentSelector", "value": "char=4,11", "conformsTo": "http://tools.ietf.org/rfc/rfc5147"}
      ]
    }
  }]
}
```

Neither `metadata` nor `lexoProvenance` appears in minimal exports. Do not include FrAC/NIF structural predicates or the complete canonical text as additional annotation properties.

## 18. Metadata-enabled export

Keep the same core as section 17, use the context from section 6, and append the native custom metadata map and separate provenance object. For example, the additional fields of an annotation may be:

```json
{
  "metadata": {
    "http://www.w3.org/2004/02/skos/core#note": [
      {"value": "Esempio verificato", "type": "literal", "language": "it"}
    ]
  },
  "lexoProvenance": {
    "creator": "imported",
    "creationDate": null,
    "lastUpdate": null
  }
}
```

Values above are illustrative, not defaults. Copy the actual DTO values, including nulls. An empty metadata map remains `{}`. Do not include labels, frequencies, or structural fields as custom metadata.

## 19. Metadata policy and creator preservation

Preserve the existing metadata map and each supported property's complete value lists. Apply the global `MetadataPolicy` unchanged: protected namespaces and properties remain excluded; `skos:note` remains the permanent SKOS exception. Do not add creator or timestamps to this map.

Copy the DTO's `creator`, `creationDate`, and `lastUpdate` into the separate `lexoProvenance` JSON literal, using shared provenance extraction from the current reader. Preserve creator strings such as `imported` or `Mario Rossi` exactly. Do not turn them into Agents, IRIs, generators, or software resources. Do not invent dates, normalize timestamp lexical forms, or assign `anonymous` to missing historical values. The fixed provenance fields are distinct from the extensible metadata policy.

Unknown future metadata properties must pass automatically whenever accepted by the shared metadata layer. No exporter-specific whitelist or scalar coercion is permitted.

## 20. NIF context resolution and external attestations

Resolve the selected attestation graph's `fileId` and the corresponding configured TEXT document graph. Read the locus there and prefer its `nif:referenceContext`. Only when that relation is absent may `frac:observedIn` supply the context, and only if it is a verified `nif:Context` in that same TEXT document graph.

Require the resolved context and its canonical `nif:isString` in the document graph. Do not perform a fallback search through LEXICON, the default graph, or unrelated documents. A containing corpus in `observedIn` is valid and does not have to equal the locus reference context. Contradictory directly referenced NIF contexts are errors.

Require one effective canonical RDF literal; identical repeated data may be deduplicated, but distinct strings or incompatible language/datatype values are errors. Keep context caches scoped by repository target, document graph, and context IRI so another document cannot hide a conflict.

LexO permits `external=true` attestations with locally stored loci but no canonical context. They remain valid LexO records, but are not convertible under this three-selector contract. Do not fetch external URLs or substitute stored quotes. Return the specific 422 code `WA_CANONICAL_TEXT_UNAVAILABLE` when such a record is selected. The same unavailable-text code applies to internal records missing their required canonical source.

## 21. Selection and duplicate handling

Enumerate and validate supported document graphs using centralized graph support. For each graph, select physically stored `rdf:type frac:Attestation` resources first. Incoming Body links are optional during discovery so missing Bodies reach validation instead of silently disappearing.

Conceptual query (graph binding is supplied by trusted graph selection):

```sparql
SELECT DISTINCT ?attestation ?lexicalResource
WHERE {
  GRAPH ?graph {
    ?attestation a <http://www.w3.org/ns/lemon/frac#Attestation> .
    OPTIONAL {
      ?lexicalResource <http://www.w3.org/ns/lemon/frac#attestation> ?attestation .
    }
  }
}
```

Use RDF4J bindings or existing safely serialized RDF values; never concatenate unvalidated parameters. Keep all selected graph identities during loading and validation.

Group by attestation IRI for final output. Merge distinct Body IRIs from selected graphs only. Duplicate occurrences must agree on locus, observedIn, resolved context, canonical text, offsets, provenance, and the native metadata value sets. If they disagree, return `WA_CONFLICTING_ATTESTATION` (422); do not choose a graph arbitrarily or invent a metadata merge. Perform consistency checks even when metadata output is disabled. Emit a compatible duplicate once. Never use data from unselected graphs to complete or merge an attestation.

## 22. Graph semantics and read-only boundary

`context=graphA` selects physically typed attestations in graph A, independently of their `observedIn` values. No HTTP `fileId` parameter is necessary for this aggregate export because it is derived and validated from each supported graph; existing CRUD requirements are unchanged.

Use existing `GraphDbUtil`/connection support with explicit `RepositoryTarget`. No writes, migrations, new persisted annotation resources, default-graph reads, or changes to document cleanup are part of this feature.

## 23. Validation

Require an absolute attestation IRI, at least one absolute Body IRI, one unambiguous locus IRI, one unambiguous observedIn IRI, a resolved NIF context, a canonical literal, and complete offsets. The selected type must physically exist in the selected graph. Existing optional labels or metadata must not affect visibility.

Require `start >= 0`, `end > start`, and `end <= canonical.codePointCount(...)`. Reject conflicting locus/NIF positions and conflicting source/context data. Do not silently omit invalid attestations or publish a partial successful export.

## 24. HTTP errors

Successful empty or non-empty exports return 200. A missing supported graph contributes an empty result. Return 400 for invalid request parameters, including unsupported graph categories. Preserve current service authentication behavior.

Return 422 for any selected record that cannot be safely converted. Use stable codes: `WA_CANONICAL_TEXT_UNAVAILABLE`, `WA_INVALID_OFFSETS`, `WA_MISSING_BODY`, `WA_INCOMPLETE_ATTESTATION`, `WA_CONFLICTING_ATTESTATION`, or `WA_INCONSISTENT_SOURCE`, as applicable. Include the offending attestation IRI and graph IRI in the diagnostic. These are new export-specific codes; existing endpoint codes remain unchanged.

Unexpected repository, serialization, or configuration failures return 500. Error responses use the current attestation service's `text/plain` convention, beginning with the machine code. The JSON-LD-only requirement applies to successful responses.

Validate the complete selected export before sending HTTP 200 or response bytes. Do not stream results directly while source validation remains pending.

## 25. Determinism

Sort annotations and Bodies by absolute IRI, keep the selector order fixed, and preserve canonical whitespace exactly. Reuse metadata ordering from shared support. Graph parameter order or repeated context values must not change the result. No export timestamp or newly generated resource IRI is added.

## 26. Performance and response preparation

Reuse/extract existing graph-grouped attestation loading and metadata conversion. Batch NIF reads and cache canonical texts within the request. Avoid invoking an existing REST endpoint or a complete query sequence per attestation.

Prepare a validated output artifact before publishing it. For large exports, a request-owned temporary spool file may bound serialization memory; it is an export artifact, not a persisted canonical text or metadata sidecar. Ensure cleanup on success, failure, and client disconnect. Stream only the completed artifact. Close/release connections, RDF4J results, and streams in every path.

Do not claim an atomic snapshot across the two repositories: no distributed snapshot mechanism currently exists. Validate the materialized data used for this response; do not requery while serializing and mix old and new values.

## 27. Implementation structure

Follow `service -> manager -> query/persistence support`, with export DTOs under `service/data/attestation`. Keep the REST method thin. Extend `AttestationManager` or introduce a focused manager/helper using `ManagerFactory` and the shared support; do not add an independent connection mechanism or duplicate RDF utilities.

Keep Java 8 source compatibility, Jersey/JAX-RS `javax.*`, and Tomcat 9. No Spring, Jakarta, Maven profiles, embedded GraphDB, or unrelated architecture migration. JSON-LD 1.1 validation tooling must not require a production platform migration.

## 28. Swagger

Provide English `@ApiOperation` and `@ApiParam` descriptions for every parameter, including Authorization. Document repeated `context`, supported attestation graph scope, default false, metadata/provenance extensions, empty results, and strict whole-request 422 behavior for unavailable canonical text. Document the successful JSON-LD media type separately from error bodies.

## 29. Requests

```http
GET /service/attestations/export/web-annotation
GET /service/attestations/export/web-annotation?includeMetadata=true
GET /service/attestations/export/web-annotation?context=https%3A%2F%2Flexo.ilc.cnr.it%2Fgraphs%2Flexical%2Fattestations%2Fdocuments%2Ffile-a
GET /service/attestations/export/web-annotation?context=https%3A%2F%2Flexo.ilc.cnr.it%2Fgraphs%2Flexical%2Fattestations%2Fdocuments%2Ffile-a&context=https%3A%2F%2Flexo.ilc.cnr.it%2Fgraphs%2Flexical%2Fattestations%2Fdocuments%2Ffile-b&includeMetadata=true
```

## 30. Acceptance tests

Implement unit, repository, mapping, and REST contract tests covering:

1. Supplied `[1316,1375)` fixture and exact 59-code-point quote.
2. One and multiple Bodies, including deduplication and stable ordering.
3. Fragment-free source identifying canonical text, including CommonMark-derived content.
4. Exact contiguous prefix/quote/suffix, boundaries, zero window, and default 50.
5. Supplementary Unicode before/inside the span; unchanged whitespace, line endings, and combining characters.
6. Neither extension appears when `includeMetadata=false`.
7. Metadata equality against the existing attestation retrieval DTO, including lists, IRIs, language tags, typed lexical values, and `skos:note`.
8. Creator and dates preserved separately in `lexoProvenance`, including `imported`, `Mario Rossi`, and nulls; no Agent/IRI conversion.
9. A new policy-permitted absolute metadata property is exported automatically with the existing DTO value shape; protected properties remain excluded.
10. Single and multiple selected graphs, repeated parameters, no-filter supported graph enumeration, missing graph returning an empty contribution, and rejection of unsupported graph categories.
11. Separation of LEXICON/TEXT with matching document IDs; default, legacy, schema, annotation, and unrelated document data cannot supply selected records or canonical strings.
12. `observedIn` referring to a corpus or source text while the locus points to the actual NIF context.
13. External record without canonical text returns `WA_CANONICAL_TEXT_UNAVAILABLE`, performs no network fetch, and does not yield a partial 200.
14. Invalid/out-of-range offsets, missing Body, missing context, conflicting text, conflicting offsets, and incomplete structural fields produce stable 422 codes.
15. Compatible duplicate IRI emitted once; conflicting duplicates rejected, including when metadata output is disabled.
16. JSON-LD 1.1 expansion/RDF checks prove native WA relations and exact preservation of both extension JSON literals. Use pinned local test contexts so tests do not depend on live W3C retrieval.
17. An invalid record encountered after valid ones still produces 422 before any success bytes; temporary artifacts and resources are released on all paths.
18. Invalid booleans/IRIs, correct response media types, English Swagger documentation, and authentication integration.
19. Repository data remains unchanged by successful and failed exports; named/default graph isolation is asserted on RDF models, not Turtle strings.

Run `mvn test` for implementation changes. Add applicable end-to-end coverage using two dedicated test repositories and isolated temporary storage; always clean remote test data in `finally`. Report unit/repository and end-to-end results separately. Update relevant README, HANDOFF, and Unreleased changelog entries when implementing the feature, preserving pre-existing edits.

## 31. Design outcome

The transformation is `LEXICON FrAC + TEXT canonical NIF + shared LexO metadata/provenance -> Web Annotation core + optional lossless LexO JSON extensions`.

Existing repository layout, metadata policy, canonical strings, resource identities, authentication, and legacy endpoints remain unchanged. Missing canonical evidence prevents conversion under this contract; it does not retroactively invalidate an external attestation supported by LexO.

Normative format references: [Web Annotation Data Model](https://www.w3.org/TR/annotation-model/), [Web Annotation context](https://www.w3.org/TR/annotation-vocab/#json-ld-context), and [JSON-LD 1.1 JSON literals](https://www.w3.org/TR/json-ld11/#json-literals).

## Implementation and verification

The implementation uses `AttestationManager.exportWebAnnotations` and a focused
`AttestationWebAnnotationExporter` helper. Reads are batched in groups of 256;
context caches are scoped to each TEXT document graph. The validated document
and final UTF-8 response bytes are currently materialized in memory; no temporary
export files are created. Memory usage grows with selected output and canonical
text retained for duplicate validation. No distributed repository snapshot is
claimed. Existing runtime dependencies remain unchanged.

Run from the repository root:

```sh
mvn test
python3 scripts/verify-web-annotation-jsonld.py
```

The Python conformance check requires RDFLib 6 or later. It reads the actual
export generated by `AttestationWebAnnotationExporterTest` at
`target/web-annotation-conformance.json`, injects a pinned local subset of the
W3C context, and checks WA RDF relations and both `rdf:JSON` literals, including
IRI/literal DTO kinds, language tags and null provenance values. The existing
Java JSON-LD processor supports 1.0 and is used for the minimal-core expansion
check; JSON-LD 1.1 conformance is verified separately without changing the WAR.
All contexts used in these checks are local, with no W3C network dependency.

An opt-in REST/GraphDB test is provided as `AttestationsExportIT`. Deploy the new
WAR against **two dedicated test repositories**, then run:

```sh
mvn verify -Dit.test=AttestationsExportIT \
  -Dlexo.test.export.dedicated=true \
  -Dlexo.test.export.baseUrl=http://localhost:8080/LexO-test/service \
  -Dlexo.test.export.lexicalRepositoryUrl=http://localhost:7200/repositories/LexOExportTestLexical \
  -Dlexo.test.export.textRepositoryUrl=http://localhost:7200/repositories/LexOExportTestTexts
```

The repository URLs must match the test deployment configuration. Supply
`lexo.test.export.authorization` if required, and override
`lexo.test.export.attestationGraphBase` / `lexo.test.export.textGraphBase` if the
server changes the graph bases (the latter includes `documents/`). The test
creates uniquely named document graphs and clears only those graphs in
`finally`. It does not upload or modify filesystem documents. Without explicit
opt-in and repository/deployment URLs the test is skipped. Never point this
workflow at development or production data.
