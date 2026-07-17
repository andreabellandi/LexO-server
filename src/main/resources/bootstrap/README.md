# GraphDB Free bootstrap

LexO-server reads these classpath resources before its first GraphDB query.

The runtime configuration is deliberately fixed in `lexo-server.properties`:
GraphDB runs at `http://localhost:7200`, the lexical repository is `LexOLexica`
and the text repository is `LexOTexts`. No Maven environment profile is used.

- `repositories/`: GraphDB Free Turtle templates. Placeholders are replaced from
  `lexo-server.properties`; the lexicon uses `owl-horst-optimized`, while texts
  use `empty`.
- `schema/schema-imports.json`: ordered list of RDF/XML resources imported into
  `https://lexo.ilc.cnr.it/graphs/schema` in the lexical repository.
- `indexes/indexes.json`: ordered list of GraphDB Lucene connector definitions.

Schema and index checksums are stored in
`https://lexo.ilc.cnr.it/graphs/bootstrap`. A resource change causes the related
bootstrap phase to run again; unchanged resources are skipped.
