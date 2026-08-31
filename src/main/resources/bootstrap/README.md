# GraphDB Free bootstrap

LexO-server reads these classpath resources before its first GraphDB query.

The packaged defaults in `lexo-server.properties` use
`http://localhost:7200`, `LexOLexica`, and `LexOTexts`. Runtime values can be
overridden by an external configuration file, `LEXO_*` environment variables,
or JVM system properties without introducing Maven environment profiles. The
Docker topology uses the internal endpoint `http://graphdb:7200` while
preserving both repository identifiers.

`Bootstrap.startup.maxAttempts` and `Bootstrap.startup.retryDelayMs` configure
bounded startup retries. Traditional deployments default to one attempt;
Compose supplies a longer retry window while the GraphDB container becomes
ready.

- `repositories/`: GraphDB 10 Turtle templates using the
  `http://www.ontotext.com/config/graphdb#` vocabulary. Placeholders are replaced
  from `lexo-server.properties`; the lexicon uses `owl-horst-optimized`, while
  texts use `empty`.
- `schema/schema-imports.json`: ordered list of RDF/XML resources imported into
  `https://lexo.ilc.cnr.it/graphs/lexical/schema` in the lexical repository.
- `indexes/indexes.json`: ordered list of GraphDB Lucene connector definitions.

Schema resources are parsed with the absolute base IRI configured by
`Bootstrap.schema.baseIri`. RDF4J 4 requires an absolute base; it is only used
to resolve relative IRIs in resources that do not declare their own XML base.

Schema and index checksums are stored in
`https://lexo.ilc.cnr.it/graphs/bootstrap`. A resource change causes the related
bootstrap phase to run again; unchanged resources are skipped.

## Application named graphs

`LexOLexica` keeps application data separate from schema and bootstrap data:

- `https://lexo.ilc.cnr.it/graphs/lexical/lexica` is reserved for legacy lexical
  services and is never consulted by the new lexical CRUD or attestation API;
- `https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}` contains data
  created by the incremental lexical CRUD API, isolated by validated ISO 639
  language code;
- `https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept` contains lexical
  concepts and concept sets created by the incremental lexical CRUD API;
- `https://lexo.ilc.cnr.it/graphs/lexical/attestations/documents/{fileId}`
  contains the FRAC attestations of one text;
- `https://lexo.ilc.cnr.it/graphs/lexical/annotations/documents/{fileId}`
  contains the Web Annotations of one text;
- `https://lexo.ilc.cnr.it/graphs/lexical/schema` contains the imported vocabularies;
- `https://lexo.ilc.cnr.it/graphs/bootstrap` contains bootstrap checksums.

The lexical graph URIs can be changed with `GraphDb.namedGraphBase`,
`GraphDb.lexiconNamedGraph`, `GraphDb.attestationNamedGraphBase`,
`GraphDb.annotationNamedGraphBase`, and `GraphDb.schemaNamedGraph` in
`lexo-server.properties`. Existing legacy lexical SPARQL updates may still use
the legacy lexical graph. New CRUD and attestation services always select their
category named graph and never read or write application data in the default
graph. Attestation and annotation writers must pass the text `fileId` to
`RDFQueryUtil.updateAttestation` or `RDFQueryUtil.updateAnnotation`; omitting it
is rejected. Deleting a text also removes every LexOLexica statement whose
subject or object is one of that text's attestations, then clears both of its
document graphs. If the cleanup removes a cross-graph `frac:attestation` link
from another valid document graph, the corresponding observable frequency in
that graph is recalculated or removed when no typed attestations remain.

`LexOTexts` continues to create one graph per document and one graph per corpus
below `TextGraphDb.namedGraphBase`.

Text and corpus graphs may also contain `frac:total` frequency objects. Each
object has an `xsd:int` `rdf:value` and one of `lexo:tokens`, `lexo:types`,
`lexo:lemmas`, or `lexo:sentences` as its `frac:unit`; totals never use the
default graph or the internal records graph.

FRAC attestation resources use the application namespace configured by
`repository.lexicon.namespace` (by default `https://lexo.ilc.cnr.it#`). Their
NIF loci remain in the corresponding `LexOTexts` document graph, while the FRAC
triples and the `frac:attestation` link are stored in the matching per-text
attestation graph. The same graph stores one `frac:Frequency` object per
observable and resolved NIF text context, linked through `frac:frequency`, with
an `xsd:int` `rdf:value` and `frac:observedIn` pointing to that context.
Attestations supplied by the fixed-schema JSON variant of `POST /texts/bulk`
follow exactly the same graph policy; their observable is resolved only in its
ISO-language lexical graph or the fixed lexical-concept graph.
