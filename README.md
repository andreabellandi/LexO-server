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

- Java 8 or later
- Apache Tomcat 9 (the current `javax.*` application is not compatible with
  Tomcat 10/Jakarta)
- [GraphDB Free](https://graphdb.ontotext.com/) - Semantic Graph Database, compliant with W3C Standards.

LexO-server persists lexical and textual data in GraphDB. It does not require
MySQL or another relational database.

## About LexO-server (download and install)

For general information, the linguistic models used by LexO-server, available
services, technical documentation, and download and installation instructions,
visit [https://klab.ilc.cnr.it/lexo-server/](https://klab.ilc.cnr.it/lexo-server/).
