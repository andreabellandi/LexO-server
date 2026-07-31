# Changelog

All notable changes to this project will be documented in this file.

The repository currently has no Git tags or published releases, so the changelog
starts from the ongoing `Unreleased` work.

## [Unreleased]

### Added

- `POST /lexica/entry` now atomically creates a lexical entry, an optional
  canonical form, and RDF-valued senses in the ISO-language-specific
  `LexOLexica` named graph, reusing or creating its `lime:Lexicon` and returning
  every created resource IRI.
- `PATCH /lexica/entries/status` now atomically changes the workflow status of
  one or more lexical entries in a language-specific named graph, validates
  the expected current status and legal transitions, and records the resolved
  author and modification timestamp.
- The non-public `OntoApi:1.0` Maven artifact is now bundled in the repository,
  allowing clean checkouts to resolve the dependency without a manual local
  Maven installation.
- A `POST /attestations` service now creates FRAC attestations and validated NIF
  loci for local or external textual evidence.
- A `POST /attestations/by-locus` batch service now creates one FRAC attestation
  per observable while sharing one validated NIF locus and transaction.
- A paginated `POST /attestations/{fileId}` service now retrieves one text's
  attestations with optional observable-type and creator filters and includes
  the corresponding NIF locus data.
- A paginated `POST /attestations/by-observable` service now retrieves one
  observable's attestations across all configured per-text attestation graphs.
- A `PATCH /attestations/{fileId}/metadata` service now atomically replaces or
  removes selected RDF metadata properties on multiple attestations, preserving
  IRI values, language-tagged literals, typed literals, and multiple values.
- A `PATCH /attestations/{fileId}/locus` service now moves one unshared,
  LexO-generated NIF locus to new Unicode code-point offsets and recalculates
  the attested value from the canonical text, with optional gloss preservation.
- A `PATCH /attestations/{fileId}/observable` service now atomically replaces
  the observable of one or more attestations in one per-document graph.
- `DELETE /attestations/{fileId}/by-observable` and
  `DELETE /attestations/{fileId}/by-locus` services now atomically remove an
  explicit attestation list or all matching attestations in one text graph.
- Plain-text TXT import and NIF conversion for documents without CommonMark
  headings.
- Front matter support for `id`, `title`, `author`, `date`, `description`,
  `format`, and `corpus`, including multi-valued metadata and URI
  handling.
- Corpus creation, corpus membership, and corpus-aware text conversion.
- Dedicated GraphDB Free bootstrap for `LexOLexica` and `LexOTexts`, including
  schema import and lexical index creation at startup.
- Text services test coverage, including workflow-oriented integration tests.
- Repository administration statistics for lexical and text repositories.
- Text catalog/listing endpoints for browsing stored texts and their metadata.
- Bulk TXT/CommonMark upload and asynchronous conversion with one shared
  language, aggregate status polling, per-document rollback, and partial
  completion results. Bulk requests containing CoNLL-U are rejected before
  any conversion starts.
- `PUT /texts/{fileId}/total` and `PUT /texts/corpora/{corpusId}/total` now
  create or replace FRAC totals for texts and corpora using the supported token,
  type, lemma, and sentence units in their respective LexOTexts named graphs.

### Changed

- Newly created `lime:Lexicon` resources no longer receive a workflow status;
  `lexo:status` is assigned only to lexical entries.

- New lexical CRUD resource IRIs now normalize timestamp colons and decimal
  separators with `_` instead of `*`, producing identifiers that are safer to
  use in HTTP paths and downstream tools.
- Lexical sense `metadata` in `POST /lexica/entry` now uses the same
  `{property, values}` list structure as `properties`, with multiple RDF values
  supported for every metadata property.
- Attestation creation, deletion, and observable replacement now maintain one
  `frac:Frequency` per observable and specific text in the document attestation
  graph. Creation and retrieval items expose `frequency`; deletion and
  observable-update responses expose the resulting values by observable.
- Lexical concept creation now accepts optional `label` and ISO 639 `language`
  parameters, stores the value as a language-tagged `skos:prefLabel`, and
  returns the preferred label in the response's `label` field.
- Lexical concept list items now include the `attestations` count, computed from
  their `frac:attestation` links in the per-document attestation named graphs.
- Paginated attestation results now include an `observableLabel` resolved from
  OntoLex and SKOS labels, canonical written representations, and sense
  definitions according to the observable RDF type, preserving language tags
  when present.
- Paginated attestation results now expose custom attestation metadata grouped
  by property IRI, excluding protected structural FRAC, RDF, and Dublin Core
  properties.
- Both paginated attestation services now accept bounded, nested `AND`/`OR`
  filters for exact attestation creators, typed text metadata, and multiple
  observable types; type matching includes `rdfs:subClassOf` hierarchies.
- Both paginated attestation retrieval services now default to 50 results per
  page when `limit` is omitted.
- TXT and CommonMark uploads now require an ISO 639-1, ISO 639-2, or ISO 639-3
  language code. The validated code is written as `dcterms:language` in the NIF;
  `language` values in file front matter are ignored.
- Attestation creation now accepts a JSON list of textual occurrences and
  creates all corresponding FRAC attestations and NIF loci as one validated
  batch. NIF anchors, FRAC glosses, and RDF values now inherit the text's
  language metadata when available.
- NIF loci created by attestation services are now marked with
  `prov:wasGeneratedBy lexo:AttestationService`; deletion removes such a locus
  only after its final attestation is removed, while reused loci are preserved.
- The default LexO application namespace is now `https://lexo.ilc.cnr.it#`.
- Text persistence now uses a dedicated GraphDB repository instead of the
  lexical repository.
- Repository access utilities now route reads and writes to the correct
  repository at runtime.
- Lexical evidence, attestations, and annotations now use named graphs instead
  of the default graph.
- LexO-server now runs under the `/LexO-server` context path with fixed local
  GraphDB repositories.
- Bootstrap schema and repository configuration now come from versioned
  resources under `src/main/resources/bootstrap`.
- `description` metadata is now mapped into `dcterms:description` in text
  conversion and catalog responses.
- SKOS API documentation now treats authorization parameters as optional.

### Fixed

- Attestation creation now reuses an existing NIF word, sentence, or structural
  locus when its anchor, offsets, and reference context match, instead of
  reporting a false `LOCUS_CONFLICT` because its RDF types differ.
- Exact lexical entry and dictionary entry lookups now work even when labels are
  missing.
- Text import and conversion now clean up partial files and directories when an
  error occurs.
- Front matter IRIs are preserved correctly, including bracketed IRI forms.
- Repository statistics and text metadata handling now support mixed literal/IRI
  values and multiple values per metadata key.

### Removed

- Attestation descriptions were removed from both `POST /attestations` and the
  paginated `POST /attestations/{fileId}` contract; new attestations no longer
  persist `dcterms:description`.
- Legacy database and repository services backed by MySQL/Hibernate were
  removed from the application.

No Git tags were found yet, so there are no release comparison links to list at
the end of this changelog.
