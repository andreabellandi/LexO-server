# Changelog

All notable changes to this project will be documented in this file.

The repository currently has no Git tags or published releases, so the changelog
starts from the ongoing `Unreleased` work.

## [Unreleased]

### Added

- A `POST /attestations` service now creates FRAC attestations and validated NIF
  loci for local or external textual evidence.
- A paginated `POST /attestations/{fileId}` service now retrieves one text's
  attestations with optional observable-type and creator filters and includes
  the corresponding NIF locus data.
- Plain-text TXT import and NIF conversion for documents without CommonMark
  headings.
- Front matter support for `id`, `title`, `author`, `date`, `description`,
  `language`, `format`, and `corpus`, including multi-valued metadata and URI
  handling.
- Corpus creation, corpus membership, and corpus-aware text conversion.
- Dedicated GraphDB Free bootstrap for `LexOLexica` and `LexOTexts`, including
  schema import and lexical index creation at startup.
- Text services test coverage, including workflow-oriented integration tests.
- Repository administration statistics for lexical and text repositories.
- Text catalog/listing endpoints for browsing stored texts and their metadata.

### Changed

- Attestation creation now accepts a JSON list of textual occurrences and
  creates all corresponding FRAC attestations and NIF loci as one validated
  batch.
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

- Exact lexical entry and dictionary entry lookups now work even when labels are
  missing.
- Text import and conversion now clean up partial files and directories when an
  error occurs.
- Front matter IRIs are preserved correctly, including bracketed IRI forms.
- Repository statistics and text metadata handling now support mixed literal/IRI
  values and multiple values per metadata key.

### Removed

- Legacy database and repository services backed by MySQL/Hibernate were
  removed from the application.

No Git tags were found yet, so there are no release comparison links to list at
the end of this changelog.
