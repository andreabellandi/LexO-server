# LexO-server — handoff per attività Codex

Aggiornato al 27 luglio 2026, dopo il merge della pull request #6 in `master`
(`95cd052`). Questo documento descrive lo stato osservato del repository; prima di
iniziare nuovo lavoro verificare sempre `git status`, il branch remoto e la
configurazione effettivamente usata dall'installazione.

## Scopo del progetto

LexO-server è un backend REST per creare, consultare, aggiornare, convertire ed
esportare risorse linguistiche Linked Data. Gestisce in particolare lessici
OntoLex-Lemon, dizionari Lexicog, risorse SKOS, ECD, testi e corpora rappresentati
in NIF, attestazioni FRAC e Web Annotation.

Il progetto produce un WAR da distribuire su Tomcat. L'interfaccia Swagger
incorporata documenta i servizi e costituisce il punto di ingresso per client e
future interfacce web.

## Architettura e tecnologie

- Java con compatibilità bytecode configurata a Java 8 (`source` e `target` 1.8).
- Maven, packaging `war`, nome finale `LexO-server.war`.
- Jersey/JAX-RS 2 (`javax.*`) per i servizi REST e multipart.
- Apache Tomcat 9; context path `/LexO-server`, API sotto `/service/*`.
- Swagger 1.6 e Swagger UI inclusa nel WAR.
- Eclipse RDF4J 3.7.6 come client remoto di GraphDB Free.
- Jackson per JSON; JUnit 5 e AssertJ per i test.
- GraphDB Free su `http://localhost:7200`, senza GraphDB embedded.
- Nessuna persistenza MySQL/Hibernate/Spring: Maven ne vieta il reinserimento
  tramite `maven-enforcer-plugin`.

La struttura applicativa segue principalmente questo flusso:

1. le classi in `service` espongono gli endpoint Jersey;
2. i manager applicano regole di dominio e orchestrano conversione/persistenza;
3. `GraphDbUtil` e `RDFQueryUtil` selezionano a runtime il repository tramite
   `RepositoryTarget.LEXICON` o `RepositoryTarget.TEXT`;
4. le classi `sparql` contengono query e template SPARQL;
5. i DTO di input/output sono sotto `service/data`.

## Persistenza e named graph

LexO-server usa sempre due repository GraphDB configurati in
`src/main/resources/lexo-server.properties`:

- `LexOLexica`: dati lessicali, schema, attestazioni e annotazioni;
- `LexOTexts`: NIF di testi e corpora e record operativi testuali.

Named graph principali di `LexOLexica`:

- `https://lexo.ilc.cnr.it/graphs/lexical/lexica` per i dati lessicali;
- `https://lexo.ilc.cnr.it/graphs/lexical/attestations/documents/{fileId}` per
  le attestazioni di un testo;
- `https://lexo.ilc.cnr.it/graphs/lexical/annotations/documents/{fileId}` per
  le annotazioni di un testo;
- `https://lexo.ilc.cnr.it/graphs/lexical/schema` per ontologie e vocabolari;
- `https://lexo.ilc.cnr.it/graphs/bootstrap` per checksum e stato bootstrap.

`LexOTexts` usa un graph per documento, un graph per corpus e il graph interno
`https://lexo.ilc.cnr.it/graphs/nif/records`. Gli originali caricati e gli
eventuali CoNLL-U restano nel filesystem sotto `data/texts` (o sotto il path
indicato da `-Dlexo.text.storage.dir=...`); testo canonico, NIF, metadati e
appartenenza ai corpora sono persistiti in GraphDB.

## Struttura principale delle cartelle

```text
.
├── docs/                         documentazione operativa e test
├── src/main/java/it/cnr/ilc/lexo/
│   ├── bootstrap/                bootstrap idempotente GraphDB Free
│   ├── manager/                  logica di dominio e conversione
│   │   └── text/                 import, NIF, corpus e catalogo testi
│   ├── service/                  endpoint REST Jersey e DTO in service/data
│   ├── sparql/                   query e template SPARQL
│   ├── servlet/                  inizializzazione Swagger
│   └── util/                     utility RDF, named graph e query
├── src/main/resources/
│   ├── bootstrap/repositories/   template Turtle dei repository GraphDB
│   ├── bootstrap/schema/         RDF/OWL e manifest di import
│   ├── bootstrap/indexes/        query e manifest degli indici Lucene
│   └── lexo-server.properties    configurazione runtime principale
├── src/main/webapp/              Swagger UI, web.xml e context.xml Tomcat
├── src/test/java/                test unitari, repository ed end-to-end
├── data/texts/                   dati runtime locali, non da versionare
├── logs/                         log runtime, non da versionare
└── target/                       output Maven, non da versionare
```

## Funzionalità completate

- Servizi CRUD e di consultazione per lessici OntoLex-Lemon, dizionari Lexicog,
  forme, sensi, concetti SKOS, relazioni, ECD, statistiche ed export RDF.
- Configurazione senza profili Maven: GraphDB locale, repository fissi
  `LexOLexica` e `LexOTexts`.
- Bootstrap idempotente all'avvio: creazione dei due repository GraphDB Free,
  import di schema/ontologie e creazione degli indici lessicali da risorse
  versionate nel classpath.
- Separazione parametrica dei repository in `GraphDbUtil`/`RDFQueryUtil`.
- Scritture lessicali nel graph `lexica`, senza uso del default graph.
- Graph separati per attestazioni e annotazioni di ciascun testo, con cleanup
  coordinato alla cancellazione del testo.
- Endpoint `administration/repositories` con statistiche per repository e per i
  graph lessicali, attestazioni, annotazioni e schema.
- Import testuale di TXT semplice, CommonMark controllato ed eventuale CoNLL-U.
- Front matter con valori singoli o multipli per `id`, `title`, `author`, `date`,
  `description`, `language`, `format` e `corpus`; valori URI gestiti secondo le
  regole documentate. `description` è sempre un letterale `dcterms:description`.
- Conversione asincrona in NIF, polling, cancellazione, download di NIF,
  originale, testo canonico e CoNLL-U.
- Creazione, consultazione, download ed eliminazione di corpora NIF senza testo;
  collegamenti bidirezionali `dcterms:hasPart`/`dcterms:isPartOf`.
- Catalogo testi filtrabile per corpus con nome, dimensione, conteggio di frasi,
  token, attestazioni, annotazioni e metadati disponibili.
- Rollback e pulizia degli artefatti in caso di errore; conservazione degli
  originali dopo conversione riuscita.
- Correzione delle ricerche esatte di lexical entry, forme, sensi e dictionary
  entry quando manca una label.
- Suite corrente: 43 test unitari/repository passati il 27 luglio 2026.

## Funzionalità ancora da completare o validare

- Eseguire regolarmente `TextServicesIT` e `TextServiceUseCasesIT` contro una
  coppia di repository e una directory filesystem dedicati ai test. Questi test
  sono esclusi da `mvn test` e non sono stati eseguiti nell'ultima validazione.
- Decidere se completare o rimuovere i metodi legacy che lanciano ancora
  `UnsupportedOperationException` in alcuni manager (ad esempio creazione e
  cancellazione lessicale, SKOS, utenti, Zotero e amministrazione).
- Risolvere i TODO residui su aggiornamento dei timestamp, validazione dei tipi
  nelle relazioni lessicali, conversione TBX interattiva e ottimizzazione CoNLL.
- Configurare e collaudare Keycloak quando l'autenticazione reale è richiesta:
  le proprietà sono commentate nella configurazione predefinita.
- Ampliare i test automatici dei servizi lessicali: la copertura più completa al
  momento riguarda il dominio testuale e i named graph.

## Decisioni tecniche importanti

- Non reintrodurre MySQL, Hibernate, Spring o GraphDB embedded.
- Non reintrodurre profili Maven per scegliere repository/ambienti. La
  configurazione runtime risiede in `lexo-server.properties`.
- Tutto il dominio testuale deve usare `RepositoryTarget.TEXT`; dati lessicali,
  attestazioni e annotazioni usano `RepositoryTarget.LEXICON`.
- Le normali update lessicali operano nel graph `lexica`. Attestazioni e
  annotazioni richiedono sempre il `fileId` per selezionare il graph del testo.
- Non scrivere dati applicativi nel default graph.
- Gli offset delle attestazioni sono riferiti al valore canonico `nif:isString`,
  con conteggio per code point Unicode, non al sorgente CommonMark renderizzato.
- I file originali e CoNLL-U sono persistiti sul filesystem; il testo canonico
  non va duplicato in `canonical.txt` e i record non vanno duplicati in
  `metadata.json`.
- I metadati multipli devono restare multipli nel modello RDF e nelle risposte.
- Le asserzioni RDF devono confrontare il modello semantico, non il testo Turtle.
- Il bootstrap è checksum-based e deve restare idempotente.
- Il progetto usa ancora API `javax.*` e target Java 8: evitare Jakarta/Tomcat 10
  o sintassi Java successiva senza una migrazione esplicita.

## Problemi noti

- `README.md` dichiara “Java 15 o successivo”, mentre il POM compila con
  `source/target 1.8`; va chiarito il requisito JDK ufficiale.
- Il `.gitignore` del repository contiene soltanto `/target/`: non esclude
  `logs/`, `data/`, `.DS_Store` o `nb-configuration.xml`. `.DS_Store` è ignorato
  solo dalla configurazione Git globale della macchina corrente e nel repository
  esistono anche vecchi log già tracciati. I nuovi file runtime non devono essere
  committati.
- Alcune classi legacy contengono stub `UnsupportedOperationException` e TODO.
- L'autenticazione Keycloak è disattivata nella configurazione predefinita.
- Il bootstrap è configurato con `Bootstrap.required=true`: se GraphDB non è
  raggiungibile o il bootstrap fallisce, l'applicazione non deve considerarsi
  correttamente avviata.
- La configurazione predefinita è volutamente legata a `localhost:7200`; un
  deployment remoto richiede modifica esplicita delle proprietà.
- In ambienti Codex senza `mvn` nel `PATH` è stato usato Maven incluso in
  NetBeans 12.2. Il sandbox può mostrare warning se non può scrivere in `~/.m2`.

## Installazione, build, avvio e test

Prerequisiti: JDK compatibile, Maven 3.8+, GraphDB Free e Tomcat 9.

```bash
# Build del WAR
mvn clean package

# Test unitari/repository (gli *IT.java sono esclusi)
mvn test

# Deploy manuale di esempio
cp target/LexO-server.war "$CATALINA_BASE/webapps/"
"$CATALINA_HOME/bin/startup.sh"
```

Con GraphDB attivo su `http://localhost:7200`, il bootstrap crea automaticamente
i repository mancanti. Swagger è disponibile su:

```text
http://localhost:8080/LexO-server/
```

Esempio minimo per gli end-to-end:

```bash
mvn verify \
  -Dlexo.test.baseUrl=http://localhost:8080/LexO-server/service \
  -Dlexo.test.authorization='Bearer TOKEN_LEXO' \
  -Dlexo.test.graphdbUrl=http://localhost:7200 \
  -Dlexo.test.textRepository=LexOTexts \
  -Dlexo.test.storageDir=/percorso/assoluto/data/texts
```

Usare esclusivamente repository e directory dedicati ai test. Per tutti i
dettagli consultare `docs/text-services-tests.md`.

Se `mvn` non è nel `PATH` nell'ambiente Codex locale:

```bash
/Applications/NetBeans/Apache\ NetBeans\ 12.2.app/Contents/Resources/NetBeans/netbeans/java/maven/bin/mvn test
```

## Stato Git

- Branch locale corrente: `codex/text-services-test-suite`.
- Il branch è stato mergiato in `master` tramite PR #6.
- Commit di merge su `master`: `95cd052`.
- HEAD del branch al momento del handoff: `9f77676`.
- Per il prossimo lavoro non continuare su questo branch già integrato: partire
  da `origin/master` aggiornato e creare un nuovo branch dedicato.
- File non tracciati osservati: log runtime e `nb-configuration.xml`; non
  includerli nei commit.

## Ultimi file modificati

L'ultimo commit (`9f77676`, supporto a `description`) ha modificato:

- `docs/text-services-tests.md`
- `src/main/java/it/cnr/ilc/lexo/manager/RepositoryStatisticsManager.java`
- `src/main/java/it/cnr/ilc/lexo/manager/text/ControlledCommonMarkParser.java`
- `src/main/java/it/cnr/ilc/lexo/manager/text/NifModelWriter.java`
- `src/main/java/it/cnr/ilc/lexo/manager/text/TextCatalogManager.java`
- `src/main/java/it/cnr/ilc/lexo/manager/text/TextNifRepository.java`
- `src/test/java/it/cnr/ilc/lexo/manager/text/ControlledCommonMarkParserTest.java`
- `src/test/java/it/cnr/ilc/lexo/manager/text/NifModelWriterTest.java`
- `src/test/java/it/cnr/ilc/lexo/manager/text/TextCatalogManagerTest.java`
- `src/test/java/it/cnr/ilc/lexo/manager/text/TextNifRepositoryTest.java`

Il commit precedente più ampio (`889be33`) ha introdotto la selezione parametrica
dei repository, il catalogo testi e i graph per-test di attestazioni/annotazioni;
queste aree sono quindi quelle con maggiore probabilità di regressioni incrociate.

## Prossimo lavoro consigliato

1. Aggiornare `origin/master` e creare un nuovo branch da `origin/master`.
2. Correggere `.gitignore` per escludere in modo esplicito artefatti runtime e IDE.
3. Preparare un ambiente E2E isolato e lanciare entrambi i workflow testuali
   completi, verificando REST, GraphDB e filesystem.
4. Allineare README e POM sul requisito Java ufficiale.
5. Inventariare gli `UnsupportedOperationException` raggiungibili dagli endpoint
   e trasformare l'inventario in test o attività di rimozione/implementazione.
