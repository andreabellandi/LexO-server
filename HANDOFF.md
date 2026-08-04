# LexO-server — handoff per attività Codex

Aggiornato al 4 agosto 2026 dopo l'arricchimento della risposta di
`GET /lexica/{language}/entries`, la correzione delle collisioni millisecondo
nella generazione delle IRI delle attestazioni e l'estensione di
`POST /attestations/by-locus` con metadati RDF opzionali per observable. La
validazione degli observable delle attestazioni riconosce ora anche i graph
lessicali specifici per lingua e il graph fisso dei lexical concept.
Il lavoro è direttamente sul branch `master`, accanto agli
altri servizi incrementali in `Lexicon.java`. Il nuovo endpoint usa il
connettore GraphDB esistente verso `LexOLexica` e isola letture e scritture nel
graph fisso `https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept`. Il POM
risolve inoltre l'artefatto Maven
non pubblico `klab.ilc.cnr.it:OntoApi:1.0` dal repository file-based versionato
sotto `vendor/maven`, senza richiedere una preventiva installazione locale.
Questo documento descrive lo stato osservato del repository; prima di iniziare
nuovo lavoro verificare sempre `git status`, il branch remoto e la
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
- `https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept` per i nuovi lexical
  concepts e concept sets;
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
│   ├── iso639/                    lista dei codici lingua ammessi negli upload
│   ├── bootstrap/schema/         RDF/OWL e manifest di import
│   ├── bootstrap/indexes/        query e manifest degli indici Lucene
│   └── lexo-server.properties    configurazione runtime principale
├── src/main/webapp/              Swagger UI, web.xml e context.xml Tomcat
├── src/test/java/                test unitari, repository ed end-to-end
├── vendor/maven/                 artefatti Maven non pubblici versionati
├── data/texts/                   dati runtime locali, non da versionare
├── logs/                         log runtime, non da versionare
└── target/                       output Maven, non da versionare
```

## Funzionalità completate

- Nuovi endpoint `GET`, `PATCH` e `DELETE /metadata`: leggono, sostituiscono e
  cancellano metadati RDF multivalore su lexical entry, lexical concept e
  attestazioni. Il resolver seleziona internamente graph linguistico, graph
  fisso dei concept o graph documentale, verifica il tipo e applica la policy
  globale dei predicati protetti.
- `MetadataPolicy` vieta per ogni entità presente e futura tutti i predicati nei
  namespace OntoLex, FRAC, LIME, VarTrans, SynSem, SKOS e Decomp, oltre a
  `dcterms:creator`, `dcterms:created`, `dcterms:modified`, `rdf:type` e
  `rdf:value`. La regola vale su creazione, CRUD e output, inclusi dati legacy.
- DTO e codec RDF comuni preservano IRI, literal semplici, language tag e
  datatype. Entry e attestazioni riusano il codec; la creazione del lexical
  concept accetta e restituisce ora `metadata` nella forma comune.
- Nuovo endpoint `POST /lexica/lexicalConcept`: crea atomicamente un
  `ontolex:LexicalConcept` con liste multilingui di label e definizioni, audit
  Dublin Core e collegamenti opzionali a sensi, parent e concept set. Tutti gli
  IRI collegati vengono verificati per esistenza e tipo nel solo graph fisso di
  categoria; la risposta `201` espone IRI, autore, timestamp e collegamenti.
- Servizi CRUD e di consultazione per lessici OntoLex-Lemon, dizionari Lexicog,
  forme, sensi, concetti SKOS, relazioni, ECD, statistiche ed export RDF.
- Nuovo endpoint `POST /lexica/entry` nella risorsa incrementale `Lexicon`: crea
  atomicamente un'entrata, l'eventuale forma canonica e più sensi RDF, riusa o
  crea il `lime:Lexicon` della lingua e restituisce tutti gli IRI creati. Tipo
  dell'entrata e parte del discorso vengono validati nei graph linguistico e di
  schema. Entrata e sensi accettano metadata RDF multivalore con IRI e letterali
  e riusano la policy globale dei predicati protetti.
- Nuovo endpoint `GET /lexica/{language}/entries`: restituisce tutte le entrate
  del lessico linguistico con filtri opzionali congiuntivi per label a fallback
  esclusivo, tipo RDF, parte del discorso, creator, stato e conteggio esatto dei
  sensi. La ricerca supporta inizio/contenimento/fine e confronto sensibile o
  insensibile al caso; le risorse di tipo e POS vengono validate nei graph
  linguistico e di schema. Ogni elemento espone inoltre IRI e cardinalità di
  sensi, forma canonica e altre forme, più i metadata RDF filtrati tramite la
  policy globale condivisa.
- Nuovo endpoint `PATCH /lexica/entries/status`: cambia atomicamente lo stato di
  una o più entrate dello stesso graph linguistico, controlla lo stato atteso e
  le sole transizioni `working`/`completed`/`revised` consentite, aggiorna
  `dcterms:modified` e registra l'account in `lexo:statusChangedBy`. Lo status
  iniziale `working` resta assegnato all'entrata creata, ma non al
  `lime:Lexicon` contenitore.
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
  `description`, `format` e `corpus`; valori URI gestiti secondo le
  regole documentate. `description` è sempre un letterale `dcterms:description`.
- Upload TXT/CommonMark con campo multipart obbligatorio `language`, validato
  contro le prime quattro colonne della lista ISO 639 inclusa nel progetto. Il
  codice normalizzato è persistito come `dcterms:language` e language tag NIF;
  la chiave `language` nel front matter viene ignorata.
- Conversione asincrona in NIF, polling, cancellazione, download di NIF,
  originale, testo canonico e CoNLL-U.
- Endpoint `POST /texts/bulk` per caricare e convertire più TXT/CommonMark con
  una sola lingua e un eventuale corpus comune. L'ammissione è atomica e vieta
  ogni CoNLL-U; dopo l'accettazione i job e i rollback sono indipendenti e
  `GET /texts/bulk/{bulkId}/status` espone anche risultati parziali.
- Creazione, consultazione, download ed eliminazione di corpora NIF senza testo;
  collegamenti bidirezionali `dcterms:hasPart`/`dcterms:isPartOf`.
- Endpoint `PUT /texts/{fileId}/total` e
  `PUT /texts/corpora/{corpusId}/total` per creare o sostituire atomicamente un
  `frac:total` nel graph documento/corpus. Sono ammesse le unità
  `lexo:tokens`, `lexo:types`, `lexo:lemmas` e `lexo:sentences`; la sostituzione
  riguarda soltanto la stessa unità e conserva gli altri totali.
- Catalogo testi filtrabile per corpus con nome, dimensione, conteggio di frasi,
  token, attestazioni, annotazioni e metadati disponibili.
- Rollback e pulizia degli artefatti in caso di errore; conservazione degli
  originali dopo conversione riuscita.
- Correzione delle ricerche esatte di lexical entry, forme, sensi e dictionary
  entry quando manca una label.
- Suite corrente: 146 test unitari/repository passati il 4 agosto 2026, inclusi
  i test del nuovo lexical concept manager e degli altri servizi lessicali, i 3
  test repository dei totali FRAC,
  i 6 test mirati del bulk testuale, i 40 test delle attestazioni, i 2 test del
  conteggio attestazioni nei lexical concept e i 4 test della creazione dei
  lexical concept con label.
- Endpoint `POST /attestations` per creare una o più attestazioni FRAC e i
  relativi loci NIF, con validazione di tipi OntoLex/DCMI, URL esterni, offset
  Unicode e isolamento nei named graph per testo.
- La validazione degli observable cerca i tipi OntoLex ammessi nel graph legacy
  `lexica`, nei graph `lexica/{language}` e nel graph fisso `lexicalConcept`,
  continuando a escludere default graph e named graph estranei.
- Endpoint `POST /attestations/by-locus` per creare atomicamente una attestazione
  per ogni IRI nella lista `observables`, condividendo lo stesso intervallo e lo
  stesso locus NIF nel corpus indicato. Ogni elemento è un oggetto con IRI
  `observable` e `metadata` opzionali nella struttura comune; le triple hanno
  come soggetto esclusivamente l'IRI della relativa attestazione.
- La creazione delle attestazioni riutilizza i loci NIF deterministici già
  presenti per parole, frasi o strutture quando `anchorOf`, `beginIndex`,
  `endIndex` e `referenceContext` coincidono, senza alterarne i tipi RDF; il
  codice `LOCUS_CONFLICT` resta riservato a differenze effettive nei dati
  identificativi del locus.
- La creazione accetta ora una lista JSON di occorrenze e valida l'intero batch
  prima di scrivere tutte le attestazioni e i loci in una transazione per
  repository, con compensazione tra `LexOLexica` e `LexOTexts`. Il metadato
  `dcterms:language` del contesto testuale viene applicato a `nif:anchorOf`,
  `frac:gloss` e `rdf:value` quando disponibile. Le occorrenze non accettano più
  `description` e le nuove attestazioni non scrivono `dcterms:description`.
- Endpoint paginato `POST /attestations/{fileId}` con filtri opzionali per tipo
  dell'osservabile e creator, arricchito con i dati del locus da `LexOTexts`.
- Endpoint paginato `POST /attestations/by-observable` per recuperare le
  attestazioni di un observable attraverso tutti i named graph per documento,
  con lo stesso output arricchito del servizio per testo.
- Entrambi gli endpoint di consultazione accettano un albero limitato di filtri
  `AND`/`OR` su creator dell'attestazione, metadati RDF esatti del testo e più
  tipi dell'observable in alternativa. Il filtro dei tipi segue anche
  `rdfs:subClassOf`; i parametri legacy `author` e `observableType` del servizio
  per testo restano compatibili e vengono combinati in `AND`. Entrambi usano 50
  risultati come dimensione predefinita della pagina.
- Endpoint `PATCH /attestations/{fileId}/locus` per spostare il locus di una
  singola attestazione a nuovi offset Unicode. Il valore viene ricalcolato dal
  `nif:isString`, l'IRI RFC5147 e le triple NIF vengono aggiornati in
  `LexOTexts`, mentre `rdf:value`, `dcterms:modified` e, per default,
  `frac:gloss` vengono aggiornati in `LexOLexica`. I loci condivisi o non
  marcati come generati da LexO sono rifiutati senza modifiche.
- Endpoint `PATCH /attestations/{fileId}/observable` per sostituire atomicamente
  il collegamento inverso `frac:attestation` di una o più attestazioni dopo la
  validazione completa del batch e del nuovo tipo OntoLex.
- I servizi di creazione mantengono un oggetto `frac:Frequency` per coppia
  observable/testo nel named graph documentale, con `rdf:value` `xsd:int` e
  `frac:observedIn` sul contesto NIF specifico. Cancellazioni per observable o
  locus e sostituzione dell'observable riallineano tutti i conteggi coinvolti e
  rimuovono la frequenza quando il valore residuo è zero. I JSON di creazione e
  consultazione espongono `frequency`; gli altri JSON di mutazione espongono la
  mappa `frequencies`.
- Endpoint `DELETE /attestations/{fileId}/by-observable` e
  `DELETE /attestations/{fileId}/by-locus` per cancellare atomicamente una lista
  esplicita di attestazioni oppure tutte quelle che corrispondono al criterio
  con `all: true`, sempre nel named graph selezionato da `fileId`.
- I loci creati dai servizi di attestazione sono marcati con
  `prov:wasGeneratedBy lexo:AttestationService`. La cancellazione elimina da
  `LexOTexts` soltanto i loci marcati rimasti senza attestazioni in tutta la
  famiglia dei graph di attestazione; i loci preesistenti o ancora referenziati
  vengono conservati.
- Le risposte di creazione e consultazione non espongono `description`.
- La consultazione paginata espone i metadata personalizzati in una mappa per
  IRI di proprietà, applicando la policy globale dei predicati protetti.
- Gli elementi restituiti da `GET /data/lexicalConcepts` e dalla ricerca
  filtrata espongono `attestations`, conteggio distinto dei collegamenti
  `frac:attestation` presenti nei named graph di attestazione per documento;
  il valore è `0` quando il concetto non ha attestazioni.
- `GET /create/lexicalConcept` accetta `label` e `language` facoltativi. Se la
  label è presente, la lingua è obbligatoria e deve appartenere alle prime
  quattro colonne della lista ISO 639 versionata; il codice normalizzato viene
  usato nel literal `skos:prefLabel` del graph `lexica`. La risposta espone la
  label nel nuovo campo JSON `label` e la lingua normalizzata in `language`.
- Ogni attestazione consultata espone `observableLabel`, risolto in base al tipo
  OntoLex usando label RDFS/SKOS, forma canonica e definizione del senso con i
  fallback documentati; sono riconosciute anche sottoclassi di `LexicalEntry` e
  i language tag sono preservati nel formato `valore@lingua`.
- Namespace applicativo predefinito aggiornato a `https://lexo.ilc.cnr.it#`.

## Funzionalità ancora da completare o validare

- Implementare progressivamente i restanti CRUD per lexical concepts e concept
  sets nella classe `Lexicon.java`. La creazione del lexical concept è
  completata; gli endpoint futuri continueranno a usare esclusivamente il graph
  fisso di categoria senza modificare i servizi legacy.
- Eseguire regolarmente `TextServicesIT` e `TextServiceUseCasesIT` contro una
  coppia di repository e una directory filesystem dedicati ai test. Questi test
  sono esclusi da `mvn test` e non sono stati eseguiti nell'ultima validazione;
  `TextServicesIT` include ora anche esito bulk parziale e rifiuto CoNLL-U.
- Decidere se completare o rimuovere i metodi legacy che lanciano ancora
  `UnsupportedOperationException` in alcuni manager (ad esempio creazione e
  cancellazione lessicale, SKOS, utenti, Zotero e amministrazione).
- Risolvere i TODO residui su aggiornamento dei timestamp, validazione dei tipi
  nelle relazioni lessicali, conversione TBX interattiva e ottimizzazione CoNLL.
- Configurare e collaudare Keycloak quando l'autenticazione reale è richiesta:
  le proprietà sono commentate nella configurazione predefinita.
- Ampliare i test automatici dei servizi lessicali: la copertura più completa al
  momento riguarda il dominio testuale e i named graph.
- Aggiungere test end-to-end REST per ricerca filtrata, aggiornamento locus e
  aggiornamento observable delle attestazioni; al momento la logica è coperta
  da test repository.
- Aggiungere test end-to-end REST per i totali FRAC di testi e corpora; la
  validazione e la persistenza sono attualmente coperte da test repository.

## Decisioni tecniche importanti

- Non reintrodurre MySQL, Hibernate, Spring o GraphDB embedded.
- Non reintrodurre profili Maven per scegliere repository/ambienti. La
  configurazione runtime risiede in `lexo-server.properties`.
- Tutto il dominio testuale deve usare `RepositoryTarget.TEXT`; dati lessicali,
  attestazioni e annotazioni usano `RepositoryTarget.LEXICON`.
- Le normali update lessicali operano nel graph `lexica`. Attestazioni e
  annotazioni richiedono sempre il `fileId` per selezionare il graph del testo.
- I nuovi CRUD lessicali selezionano il graph in base alla categoria dichiarata:
  le risorse dipendenti dalla lingua usano `lexica/{language}`, mentre lexical
  concepts e concept sets usano esclusivamente il graph fisso
  `https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept`.
- Non scrivere dati applicativi nel default graph.
- Gli offset delle attestazioni sono riferiti al valore canonico `nif:isString`,
  con conteggio per code point Unicode, non al sorgente CommonMark renderizzato.
- I file originali e CoNLL-U sono persistiti sul filesystem; il testo canonico
  non va duplicato in `canonical.txt` e i record non vanno duplicati in
  `metadata.json`.
- Il bulk testuale usa una lingua comune, accetta soltanto TXT/CommonMark e non
  introduce associazioni implicite basate sui nomi dei file. Gli errori generali
  eliminano tutto lo staging; gli errori di conversione eliminano soltanto il
  documento interessato.
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
- I record aggregati dei bulk sono mantenuti in memoria: i documenti e i job già
  avviati restano gestiti individualmente, ma dopo un riavvio non è più
  disponibile il polling tramite il precedente `bulkId`.
- `POST /attestations` richiede una lista JSON al livello principale. Con un
  oggetto JSON, Jersey/MOXy fallisce prima dell'ingresso nel metodo con
  `IllegalArgumentException: argument type mismatch` e restituisce HTTP 500
  invece di un errore applicativo 400; il client deve inviare l'array documentato.
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

- Branch locale corrente: `master`.
- I riferimenti di `origin/master` sono stati aggiornati prima del lavoro e il
  branch locale è stato verificato come fast-forward compatibile; questo
  handoff accompagna il commit dei metadati per
  `POST /attestations/by-locus` direttamente su `master`.
- Log runtime e `nb-configuration.xml` restano esclusi dal lavoro.

## Ultimi file modificati

La risposta di `GET /lexica/{language}/entries` include ora `senses`,
`canonicalFormNumber`, `canonicalForm`, `otherFormNumber`, `otherForms` e
`metadata`, oltre al precedente `senseNumber`. Liste e conteggi sono coerenti e
ordinati deterministicamente; i metadata riusano `RdfMetadataCodec` e
`MetadataPolicy`, quindi preservano il tipo RDF dei valori e omettono ogni
predicato protetto anche sui dati legacy.

Il lavoro corrente rende monotona la componente millisecondo delle IRI delle
attestazioni: se il clock non avanza o il candidato è già presente nel
repository, il manager seleziona il millisecondo successivo libero mantenendo
coerenti IRI e timestamp audit. Un test con clock bloccato riproduce e copre la
precedente collisione. Lo stesso lavoro trasforma ogni elemento di `observables`
della create
by-locus in un oggetto con `observable` obbligatorio e `metadata` opzionali nella
struttura comune `{property, values}`. `AttestationManager` usa
`RdfMetadataCodec` e `MetadataPolicy`, aggiunge ogni valore al modello con
soggetto l'IRI della specifica attestazione e persiste l'intero batch nel named
graph documentale. La risposta conserva la struttura delle attestazioni e
include i metadata creati. I 40 test mirati e la suite completa di 146 test sono
passati il 4 agosto 2026; gli end-to-end REST non sono stati eseguiti.

È stata predisposta e usata la base della riscrittura incrementale dei CRUD
lessicali, senza rimuovere o modificare gli endpoint legacy. La nuova risorsa
`service/Lexicon.java` risponde alla radice `lexica`, espone il tag Swagger
`Lexica` e contiene anche `POST /lexica/lexicalConcept`, oltre a
`POST /lexica/entry`, `GET /lexica/{language}/entries` e
`PATCH /lexica/entries/status`. `LexicalConceptManager` valida prima della
scrittura label ISO 639 e collegamenti tipizzati, quindi persiste tutte le
triple in una transazione del graph fisso di categoria.
`LexicalEntryStatusManager` valida l'intero
batch prima di scrivere, limita i target alle entrate OntoLex del graph
linguistico, applica il rollback su ogni errore e mantiene autore e timestamp
della transizione. I relativi DTO sono sotto `service/data/lexicon` e
`LexicalWorkflowStatus` centralizza valori e transizioni ammesse.
La risorsa `service/Metadata.java`, `manager/metadata/MetadataManager` e
`RdfMetadataCodec` e `MetadataPolicy` costituiscono il nuovo nucleo condiviso. I DTO sotto
`service/data/metadata` definiscono una sola rappresentazione JSON per input e
output; nuove categorie registrano soltanto tipo e graph e riusano senza
deroghe la policy globale dei predicati protetti.
`LexiconCrudSupport` centralizza anche il graph fisso dei lexical concept e il
prefisso SKOS, oltre alla selezione del named graph specifico per
lingua sotto `https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}`, la
generazione delle IRI tramite namespace, instance id e timestamp configurati
con la normalizzazione `_`, e il fallback `anonymous` per autori nulli o vuoti.
I codici lingua vengono validati contro le prime quattro colonne della lista
ISO 639 versionata nelle risorse e normalizzati in minuscolo tramite
`Iso639LanguageValidator`, così valori sintatticamente plausibili ma non
presenti nel file vengono rifiutati e lingue diverse restano in graph distinti.
Il contratto completo è in `docs/lexicon-services.md` ed è richiamato dalle
regole permanenti in `AGENTS.md`. `LexicalEntryManager` usa
`GraphDbUtil.getConnection(RepositoryTarget.LEXICON)`, completa le validazioni
prima delle scritture e persiste l'intero modello in una singola transazione del
graph linguistico. Lo stesso contratto fissa i namespace `decomp`, `vartrans`,
`ontolex`, `synsem`, `lexinfo` 3.0, `lime`, `lexicog` e `skos`.
Le collezioni `properties` e `metadata` di ogni senso condividono ora la stessa
struttura JSON: una lista di oggetti con `property` e `values`, dove `values` è
multivalore. La policy globale dei predicati protetti si applica a `metadata`.
I 57 test della selezione mirata comprendente metadati, attestazioni e servizi
e la suite completa di 143 test unitari/repository sono passati il 4 agosto
2026. Gli end-to-end `*IT` non sono stati eseguiti; Maven ha
riportato i warning ambientali già noti su SLF4J, codice legacy
deprecato/unchecked e impossibilità del sandbox di aggiornare tracking file in
`~/.m2`.

Il lavoro corrente aggiunge due endpoint `PUT` in `Texts`, i DTO
`TextTotalInput`/`TextTotalResult`, il manager condiviso `TextTotalManager` e la
scrittura transazionale in `TextNifRepository`. Il soggetto del testo è il suo
contesto NIF, mentre il soggetto del corpus è la relativa IRI. Una scrittura
rimuove tutti i nodi `frac:total` della stessa unità e ne crea uno canonico con
tipo `frac:Frequency`, valore `xsd:int` e unità IRI; le altre unità restano
invariate. `TextNifRepository` usa ora inizializzazione lazy per permettere test
in-memory senza connessioni GraphDB implicite. I 3 test dedicati e la suite
completa di 99 test sono passati il 30 luglio 2026; gli end-to-end REST restano
da eseguire in un ambiente dedicato.

Il lavoro precedente aggiunge `PATCH /attestations/{fileId}/locus` e
`PATCH /attestations/{fileId}/observable`. Il primo modifica soltanto loci
LexO non condivisi, ricava la nuova anchor dal testo canonico con offset Unicode,
sposta l'IRI `#char=start,end` e coordina le transazioni sui due repository con
compensazione. Il secondo sostituisce atomicamente l'observable per una lista
validata di attestazioni nello stesso named graph. I DTO dedicati documentano
input e risultati, inclusi `updateGloss`, locus precedente e observable
precedenti.

Lo stesso lavoro precedente aggiunge inoltre
`POST /attestations/by-observable` e il DTO ricorsivo
`AttestationFilter`, condiviso con `POST /attestations/{fileId}`. I filtri
combinano creator, metadata RDF del contesto testuale e tipi OntoLex, preservano
la semantica esatta di IRI/literal, lingua e datatype, e riconoscono le
sottoclassi dichiarate nei graph lessicale e schema. La ricerca per observable
accetta soltanto membri validi della famiglia dei graph di attestazione e
arricchisce dopo la paginazione i risultati tramite `LexOTexts`.

Il branch di base aggiunge inoltre i due endpoint `DELETE` delle attestazioni e
una sola implementazione manager condivisa per validazione, selezione esplicita
o completa, cancellazione RDF e compensazione fra repository. I nuovi loci
ricevono il marcatore
`prov:wasGeneratedBy lexo:AttestationService`; un locus viene eliminato soltanto
quando è marcato e non è più referenziato da attestazioni. I test del 30 luglio
2026 ne verificano anche la cancellazione e la conservazione dei loci condivisi.

La creazione dei lexical concept accetta ora i parametri facoltativi `label` e
`language`; quando la label è presente la lingua è obbligatoria, validata sulla
lista ISO 639 inclusa e normalizzata. La scrittura usa sempre un literal
`skos:prefLabel` language-tagged nel graph `lexica`, anche quando la
configurazione lessicale generale usa un altro modello. Il DTO di creazione
espone il nuovo campo `label`. I quattro test dedicati verificano validazione,
escaping del literal, named graph, assenza nel default graph e JSON di output.

Il servizio `GET /data/lexicalConcepts` e la ricerca filtrata dei lexical concept
usano ora il campo JSON `attestations`. Le query contano i soli oggetti distinti
di `frac:attestation` nei graph sotto la base configurata delle attestazioni,
senza includere default graph o graph estranei; il test repository dedicato
verifica anche che il conteggio non alteri il numero di figli.

Nello stesso branch l'upload testuale è stato esteso con la lingua ISO 639
obbligatoria. I file principali coinvolti sono `Texts.java`, `TextJobManager.java`,
`Iso639LanguageValidator.java`, `ControlledCommonMarkParser.java`, la lista CSV
in `src/main/resources/iso639`, i test testuali e la relativa documentazione.
La suite completa conta ora 91 test; gli end-to-end `*IT` restano esclusi da
`mvn test` e richiedono un deployment dedicato.

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

Il file `CHANGELOG.md` è stato aggiunto come registro delle modifiche verificabili;
al momento non esistono tag Git, quindi tutte le voci restano nella sezione
`Unreleased`.

## Prossimo lavoro consigliato

1. Aggiungere end-to-end REST dedicati a `POST /lexica/lexicalConcept`,
   `POST /lexica/entry` e `PATCH /lexica/entries/status` contro un repository
   `LexOLexica` isolato, inclusi validazione dei link, riuso del lessico,
   transizioni batch e rollback.
2. Aggiungere test end-to-end dei nuovi endpoint di totali FRAC, creazione,
   cancellazione delle attestazioni e della
   creazione dei lexical concept con label contro repository GraphDB dedicati.
3. Gestire esplicitamente i body non-array di `POST /attestations` con una
   risposta HTTP 400 leggibile, evitando l'errore riflessivo Jersey/MOXy.
4. Correggere `.gitignore` per escludere in modo esplicito artefatti runtime e IDE.
5. Preparare un ambiente E2E isolato e lanciare entrambi i workflow testuali
   completi, verificando REST, GraphDB e filesystem, inclusi i nuovi casi di
   lingua upload mancante/non valida e il relativo `dcterms:language` nel NIF.
6. Allineare README e POM sul requisito Java ufficiale.
7. Inventariare gli `UnsupportedOperationException` raggiungibili dagli endpoint
   e trasformare l'inventario in test o attività di rimozione/implementazione.
