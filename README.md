# LexO-server: _REST services for Linguistic Linked Data_ 

[![Build Status](images/build-passing.png)](https://github.com/andreabellandi/LexO-backend) [![N|Solid](images/clarin.png)](https://ilc4clarin.ilc.cnr.it/) 

LexO-server is a backend software providing REST services for building and managing linguistic resources in the context of the Semantic Web, in particular:
- lexical and terminological resources are based on the [_OntoLex-Lemon_](https://www.w3.org/2016/05/ontolex/) model;
- lexicographic resources (dictionaries) are based on the [_Lexicog_](https://www.w3.org/2019/09/lexicog/) model;
- Explanatory Combinatorial Dictionaries (ECDs) are represented using a combination of these two models.

LexO-server uses the [Swagger](https://swagger.io/) open source tool. It helps one to design and to document APIs at scale, for easing and supporting the front end GUI development process

## Features

- Targeted for web apps oriented at different lexicographic-based tasks, such as editing, linking, data visualization, dictionary making, linguistic annotation
- Lexical level implemented by the [_OntoLex-Lemon_](https://www.w3.org/2016/05/ontolex/) and the [_Lexicog_](https://www.w3.org/2019/09/lexicog/) models.
- Conceptual level implemented by the [_SKOS_](https://www.w3.org/2004/02/skos/) model 
- Integrated user authentication addressed by [KeyCloak](https://www.keycloak.org/) 
- Possibility to manage bibliographical items with [Zotero](https://www.zotero.org/) 
- Integration with remote SPARQL endpoints 
- Export data as Linked Data (RDF/XML, Turtle, N3, NQuads)

## Tech

LexO-server uses the following technology to work properly:

- Java 15 or later
- Apache Tomcat 9 or later
- [GraphDB Free](https://graphdb.ontotext.com/) - Semantic Graph Database, compliant with W3C Standards.

LexO-server persists lexical and textual data in GraphDB. It does not require
MySQL or another relational database.

## Installation

1. [Install](https://graphdb.ontotext.com/documentation/free/quick-start-guide.html)
   and start GraphDB Free at `http://localhost:7200`.
2. Download the project and run `mvn clean package` without a Maven profile.
3. Deploy `target/LexO-server.war` to Tomcat.
4. At webapp startup LexO-server creates, when missing, `LexOLexica` and
   `LexOTexts`, imports the schema resources and creates the lexical indexes.
5. Open http://localhost:8080/LexO-server/ to access Swagger.

The two GraphDB repositories are fixed in `src/main/resources/lexo-server.properties`:

```properties
GraphDb.url=http://localhost:7200
GraphDb.repository=LexOLexica
TextGraphDb.url=http://localhost:7200
TextGraphDb.repository=LexOTexts
```

## Tests

Run the unit suite with `mvn test`. Tests for the text services, including the
optional end-to-end tests for a deployed LexO-server and GraphDB Free, are
documented in [docs/text-services-tests.md](docs/text-services-tests.md).

## Text upload language

`POST /service/texts/upload` requires a multipart `language` field in addition
to the TXT/CommonMark `file` and optional `conllu` file. The value must occur in
one of the first four columns of the bundled ISO 639 list (ISO 639-1, the two
ISO 639-2 forms, or ISO 639-3). Matching is case-insensitive and the stored code
is normalized to lowercase.

```bash
curl -X POST 'http://localhost:8080/LexO-server/service/texts/upload' \
  -H 'Authorization: Bearer TOKEN_LEXO' \
  -F 'language=it' \
  -F 'file=@intervista.txt;type=text/plain'
```

The language supplied by the upload is stored as `dcterms:language` during NIF
conversion and is used as the NIF literal language tag. A `language` key in the
file front matter is ignored. Supported front-matter keys are `id`, `title`,
`author`, `date`, `description`, `format`, and `corpus`.

## Attestations

`POST /service/attestations` creates multiple FRAC attestations for one OntoLex
lexical entry, form, lexical sense, or lexical concept. Required query parameters
are `observable` and `corpus`; `external` and `author` are optional. The JSON body
is a non-empty list whose items contain required `value`, `start`, and `end`
fields and an optional `description`. Offsets are Unicode code-point offsets on
the canonical `nif:isString` value.

```json
[
  {"description": "First occurrence", "value": "example", "start": 10, "end": 17},
  {"description": "Second occurrence", "value": "example", "start": 42, "end": 49}
]
```

The whole list is validated before persistence. All FRAC resources and NIF loci
are then written as one batch transaction per repository; a failed occurrence
does not leave the preceding occurrences stored. When the text context has a
`dcterms:language` metadata value, the same language tag is applied to
`nif:anchorOf`, `frac:gloss`, and `rdf:value`; otherwise these values remain
plain string literals.

For local evidence, `corpus` must identify a `dcmitype:Collection`,
`dcmitype:Dataset`, or `dcmitype:Text` in `LexOTexts`. The service validates the
selected substring and writes the NIF locus in the document graph. With
`external=true`, `corpus` must be an HTTP(S) URL and no remote content is
downloaded. FRAC data is written to the per-text attestation graph in
`LexOLexica`; application data is never written to a default graph.

`POST /service/attestations/{fileId}` returns the attestations of one text as a
paginated JSON response. `observableType` and `author` optionally filter the RDF
type of the observed lexical entity and the exact `dcterms:creator` value.
`limit` defaults to 200 and `offset` defaults to 0. Each result combines FRAC
metadata from `LexOLexica` with anchor, offsets, language, RDF types and reference
context read from the corresponding NIF locus in `LexOTexts`. The
`observableLabel` field is resolved according to the observable type: lexical
entries prefer `rdfs:label` and then their canonical form's
`ontolex:writtenRep`; forms prefer `ontolex:writtenRep` and then `rdfs:label`;
lexical senses combine their entry label or canonical written representation
with `skos:definition`; lexical concepts prefer `skos:prefLabel` and then
`rdfs:label`. Language tags are preserved in the compact `value@language`
format (for example, `casa@it`); missing values fall back to `"no label"`.

## License

MIT

**Free Software, Hell Yeah!**
