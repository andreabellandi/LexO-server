# GraphDB Free bootstrap

LexO-server reads these classpath resources before its first GraphDB query.

The runtime configuration is deliberately fixed in `lexo-server.properties`:
GraphDB runs at `http://localhost:7200`, the lexical repository is `LexOLexica`
and the text repository is `LexOTexts`. No Maven environment profile is used.

- `repositories/`: GraphDB 10 Turtle templates using the
  `http://www.ontotext.com/config/graphdb#` vocabulary. Placeholders are replaced
  from `lexo-server.properties`; the lexicon uses `owl-horst-optimized`, while
  texts use `empty`.
- `schema/schema-imports.json`: ordered list of RDF/XML resources imported into
  `https://lexo.ilc.cnr.it/graphs/schema` in the lexical repository.
- `indexes/indexes.json`: ordered list of GraphDB Lucene connector definitions.

Schema resources are parsed with the absolute base IRI configured by
`Bootstrap.schema.baseIri`. RDF4J 4 requires an absolute base; it is only used
to resolve relative IRIs in resources that do not declare their own XML base.

Schema and index checksums are stored in
`https://lexo.ilc.cnr.it/graphs/bootstrap`. A resource change causes the related
bootstrap phase to run again; unchanged resources are skipped.

## Application named graphs

`LexOLexica` keeps application data separate from schema and bootstrap data:

- `https://lexo.ilc.cnr.it/graphs/lexical/lexica` contains lexical data;
- `https://lexo.ilc.cnr.it/graphs/lexical/attestations` contains FRAC
  attestations;
- `https://lexo.ilc.cnr.it/graphs/schema` contains the imported vocabularies;
- `https://lexo.ilc.cnr.it/graphs/bootstrap` contains bootstrap checksums.

The lexical graph URIs can be changed with `GraphDb.namedGraphBase`,
`GraphDb.lexiconNamedGraph`, and `GraphDb.attestationNamedGraph` in
`lexo-server.properties`. Existing SPARQL updates use the lexical graph by
default; attestation writers use `RDFQueryUtil.updateAttestation`.

`LexOTexts` continues to create one graph per document and one graph per corpus
below `TextGraphDb.namedGraphBase`.
