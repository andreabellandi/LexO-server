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
- [MySql](https://www.mysql.com/) - Open-source relational database management system (RDBMS)

## Installation

1. [Install](https://graphdb.ontotext.com/documentation/free/quick-start-guide.html)
   and start GraphDB Free at `http://localhost:7200`.
2. Start MySQL locally and create the `lexo_server` database. The default JDBC
   connection is declared in `pom.xml` (`root` / `root`).
3. Download the project and run `mvn clean package` without a Maven profile.
4. Deploy `target/LexO-server.war` to Tomcat.
5. At webapp startup LexO-server creates, when missing, `LexOLexica` and
   `LexOTexts`, imports the schema resources and creates the lexical indexes.
6. Open http://localhost:8080/LexO-server/ to access Swagger.

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

## License

MIT

**Free Software, Hell Yeah!**
