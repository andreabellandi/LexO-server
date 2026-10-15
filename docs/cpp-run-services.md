# Registrazione ed export dei run CPP

Il modulo registra **a posteriori la provenienza dichiarata dal client** delle
estrazioni di collocati. Non esegue ricerche, non calcola né modifica score,
non valida espressioni multiword e non crea collocazioni FrAC. Il frontend
esegue `/texts/collocations/extract`, conserva la richiesta e salva le candidate
selezionate con `/texts/collocations/persist`; solo dopo registra il run CPP.
Un run deve sempre avere almeno una FrAC già esistente.

## API

Prefisso: `/LexO-server/service/cpp` con il context path WAR standard.
Swagger espone il gruppo **CPP Runs** e i DTO delle richieste e risposte.
Autenticazione opzionale come negli altri servizi: free mode senza LexO-auth;
quando l'autenticazione è configurata, viene verificato `Authorization` e
l'identità autenticata prevale sull'autore dichiarato. Autori assenti o vuoti
sono normalizzati in `anonymous`.

| Metodo | Path | Risposta |
|---|---|---|
| POST | `/runs` | 201, dettaglio e Location; 200 per replay idempotente |
| GET | `/runs` | 200, `{total, limit, offset, items}` |
| GET | `/runs/{id}` | 200, dettaglio completo |
| PATCH | `/runs/{id}` | 200, associazioni aggiornate |
| DELETE | `/runs/{id}` | 204, nessun body; preserva tutte le FrAC |
| GET | `/runs/export` | 200, file `text/turtle; charset=UTF-8` |

Gli ID nei path sono UUID canonici minuscoli; le risposte contengono anche la
IRI RDF completa. Il path statico `/runs/export` è distinto dal dettaglio.
GET, PATCH, DELETE ed export accettano `author` query facoltativo per il
contesto di audit; nella lista `author` è invece un filtro esatto. Il POST
accetta `author` nel JSON. PATCH non cambia il creator storico del run.

## Creazione

Esempio (le IRI FrAC devono essere già presenti nei graph ammessi):

```json
{
  "search": {
    "node": "crime",
    "leftWindow": 5,
    "rightWindow": 5,
    "boundary": "SENTENCE",
    "direction": "BOTH",
    "caseSensitive": false,
    "minCooccurrenceFrequency": 3,
    "minFrequency": 2,
    "minScore": 7.0,
    "metrics": ["LOG_DICE", "PMI"],
    "sortBy": "LOG_DICE",
    "sortOrder": "DESC",
    "limit": 100,
    "excludeTerms": ["alleged"],
    "stoplist": ["the", "a"],
    "corpusIRI": "https://example.org/corpus/news-2026"
  },
  "cppOptions": {"ordered": false},
  "corpusSnapshot": {
    "memberIRIs": ["https://example.org/corpus/news-2026"]
  },
  "index": {"id": "lexo-lucene-main", "lastCommit": "2026-10-15T12:30:00Z"},
  "software": {"name": "LexO-server", "version": "1.0.0"},
  "execution": {
    "startedAtTime": "2026-10-15T12:29:00Z",
    "endedAtTime": "2026-10-15T12:29:04Z"
  },
  "author": "researcher",
  "collocations": [
    "https://example.org/collocations/commit-crime",
    "https://example.org/collocations/report-crime"
  ]
}
```

Salvare il JSON in `run.json`, quindi:

```bash
BASE=http://localhost:8080/LexO-server/service
curl -i -X POST "$BASE/cpp/runs" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: save-search-20261015-crime-02' \
  --data-binary @run.json
```

In auth mode aggiungere `-H "Authorization: Bearer $TOKEN"` a ogni richiesta.
La risposta comprende `id`, `iri`, `createdAt`, `author`, `step`, `search`
effettiva, `cppOptions`, snapshot, indice, software (con IRI), `collocations`
ordinate e `collocationCount`. I tempi `execution` sono presenti solo se
forniti. `createdAt` è il tempo UTC di registrazione, non quello di estrazione.

Obbligatori: `search.node`, `cppOptions.ordered` booleano esplicito,
`corpusSnapshot.memberIRIs` non vuoto, `index.id`, `software.name` e
`collocations` non vuoto. Non esistono run provvisori, falliti o vuoti.
Le liste di IRI non ammettono duplicati. Gli identificatori sono IRI assolute;
non vengono scaricati URL o aperti file indicati dal client.
`index.id` è un identificatore logico; non deve essere un path locale.
`lastCommit` è una dichiarazione facoltativa relativa all'indice **al tempo
originario**; il server non la sostituisce con lo stato attuale dell'indice.

I JSON sono UTF-8 rigoroso, con schema chiuso anche negli oggetti annidati.
Campi sconosciuti, proprietà duplicate, token JSON finali, coercizioni da
stringhe/numeri a booleani, interi frazionari e booleani `null` sono rifiutati.
Il body viene letto con un limite di byte prima di costruire il JSON; la
profondità massima è otto livelli. NaN e Infinity non sono ammessi.

### Parametri effettivi Lucene

`search` riusa `CollocateExtractionRequest`, compresi i selettori singoli e
plurali `contextIRI/contextIRIs`, `corpusIRI/corpusIRIs`, `graphIRI/graphIRIs`.
`CollocateExtractionParameters` è condiviso con l'estrattore: nessuna
connessione a Lucene è necessaria per registrare il run.

| Parametro | Default / vincolo |
|---|---|
| leftWindow / rightWindow | 5 / 5; 0..`lexo.lucene.maxCollocationWindow` (100) |
| boundary | DOCUMENT; DOCUMENT o SENTENCE |
| direction | BOTH; LEFT, RIGHT o BOTH |
| caseSensitive | false; null esplicito vietato |
| minCooccurrenceFrequency | 2; intero positivo |
| minFrequency | assente; intero non negativo se presente |
| minScore | assente; numero finito se presente |
| metrics | tutte le 18 se omesso, null, vuoto o contenente ALL |
| sortBy | LOG_DICE; misura riconosciuta oppure FREQUENCY |
| sortOrder | DESC; ASC o DESC |
| limit | 100; 1..`lexo.lucene.maxPageSize` (1000) |
| excludeTerms / stoplist | nessuna esclusione; insiemi di origine distinti |

Enum normalizzate in maiuscolo secondo il contratto Lucene; `ALL` segue il
comportamento del motore. `sortBy` può essere assente da `metrics`: il record
conserva tale comportamento, incluso il fallback di ordinamento del motore,
senza inventare score. L'alias `FREQUENCY` usa `fxy` e seleziona
`collscore:CooccurrenceFrequency`, lo stesso individuo di `RAW_FREQUENCY`.
I filtri vuoti non producono binding. Stringhe vuote nelle stoplist sono
ignorate come in Lucene; gli altri valori conservano il testo dichiarato.
`ordered` è indipendente da `direction`: il Lucene attuale non applica un
filtro autonomo ulteriore per questa dichiarazione metodologica.
`posPattern` non è accettato nel JSON e non produce binding; rimane nello schema.

Lo snapshot conserva una selezione dichiarata: una IRI di corpus corrente non
prova la versione storica dei suoi membri. Per riproducibilità preferire le
IRI dei contesti effettivamente selezionati. Non viene interrogato `TEXT` per
certificare a posteriori un'appartenenza storica non ricostruibile; corpus,
contesti e indice sono dichiarazioni validate sintatticamente, senza promesse
di riesecuzione o di transazione distribuita. Un eventuale collegamento a
software globale (`software.iri`) richiede tipo esplicito
`cpp:ExtractorSoftware`, label uguale al nome e versione uguale se dichiarata,
in un graph ammesso o nel graph CPP. Tale descrizione non viene modificata.

### Idempotenza

`Idempotency-Key` è facoltativo: 1..128 caratteri `[A-Za-z0-9._~:-]`.
La coppia autore effettivo/chiave e l'impronta SHA-256 del payload normalizzato
sono conservate come `dct:identifier` con convenzioni `cpp:idempotency:` e
`cpp:payload-sha256:`. Ordine delle proprietà JSON e degli insiemi normalizzati
non cambia il replay. Stessa coppia e stesso payload restituiscono il run
esistente con 200; payload diverso produce 409. Dopo PATCH il replay restituisce
la vista corrente; dopo DELETE la chiave è rimossa insieme al run.
In free mode l'autore è dichiarato dal client, non un'identità autenticata.

## Lista, dettaglio, correzione e cancellazione

```bash
curl "$BASE/cpp/runs?node=crime&measure=LOG_DICE&limit=50&offset=0"
curl "$BASE/cpp/runs/$RUN_ID"
curl -X PATCH "$BASE/cpp/runs/$RUN_ID?author=editor" \
  -H 'Content-Type: application/json' \
  --data '{"addCollocations":["https://example.org/collocations/prevent-crime"],"removeCollocations":["https://example.org/collocations/report-crime"]}'
curl -i -X DELETE "$BASE/cpp/runs/$RUN_ID"
```

La lista combina in AND i filtri esatti `node`, `collocationIRI`, `corpusIRI`
(membro dello snapshot), `measure` (enum Lucene o IRI individuo CollScore),
`author`, `from`/`to` inclusivi sul timestamp di registrazione. Le date devono
avere offset ISO 8601; sono normalizzate in UTC. Paginazione predefinita 50/0,
limite massimo configurabile 500, ordinamento stabile created DESC / IRI ASC.
Conteggio e pagina usano query SPARQL parametrizzate nel solo graph CPP.

PATCH accetta soltanto `addCollocations` e `removeCollocations`: almeno una
lista non vuota, nessuna sovrapposizione. Aggiunte già presenti e rimozioni già
assenti sono idempotenti; tutte le nuove FrAC sono verificate prima di scrivere.
Non può lasciare il run senza FrAC. Parametri storici, creator, software e tempi
rimangono immutabili. `dct:modified` registra la correzione; il log applicativo
riporta run e numero di associazioni, con attore nel contesto di logging.
Questa vista corrente non è uno storico persistente di tutte le revisioni.

DELETE elimina run, figli esclusivi e le tre triple strutturali CPP delle
osservazioni, oltre al collegamento inverso dal run. Le descrizioni software
globali, gli altri run e tutti i graph FrAC restano identici.

## Modello RDF e graph

Scritture soltanto in `RepositoryTarget.LEXICON`, graph configurato da
`GraphDb.cppNamedGraph`, default:

```text
https://lexo.ilc.cnr.it/graphs/lexical/cpp/runs
```

Il graph CPP deve essere distinto dai graph applicativi, schema e bootstrap;
la configurazione in conflitto viene rifiutata. Tutte le mutazioni usano una
singola transazione RDF4J, rollback e rilascio della connessione.
Le FrAC devono avere tipo **esplicito** `frac:Collocation` nel graph legacy
configurato da `GraphDb.lexiconNamedGraph`, in un graph ISO `lexica/{language}`
o nel graph fisso `lexicalConcept`. Default, schemi, bootstrap, attestazioni,
annotazioni e graph estranei non sono ammessi. Le letture non usano inferenze.
Non vengono mai scritte triple nei graph FrAC o nel repository `TEXT`.

`CppResourceUris` conia UUID e IRI sotto `CPP.resourceNamespace`, default
`https://lexo.ilc.cnr.it/id/cpp/`: run in `run/{uuid}`, risorse figlie distinte
sotto quel run. Non usa il namespace ontologico per le istanze.

Esempio RDF parziale (UUID illustrativo, altri binding omessi):

```turtle
@prefix cpp: <https://ilc.cnr.it/klab/ontology/cpp#> .
@prefix prov: <http://www.w3.org/ns/prov#> .
@prefix p-plan: <http://purl.org/net/p-plan#> .
@prefix collscore: <https://ilc.cnr.it/klab/ontology/cooccurrence-measures#> .
@prefix ex: <https://example.org/cpp/> .
@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

ex:run a cpp:CollocationExtractionRun ;
  p-plan:correspondsToStep cpp:extractCollocations ;
  prov:used ex:configuration, ex:node, ex:sort ;
  prov:generated <https://example.org/collocations/commit-crime> .
ex:configuration a cpp:CollocationExtractionConfiguration ;
  prov:hadMember ex:node, ex:sort .
ex:node a cpp:NodeValue, cpp:LiteralParameterValue ;
  p-plan:correspondsToVariable cpp:node ; prov:value "crime" .
ex:sort a cpp:SortByValue ; p-plan:correspondsToVariable cpp:sortBy ;
  collscore:selectsMeasure collscore:LogDice .
<https://example.org/collocations/commit-crime>
  a cpp:CollocationObservation ; prov:wasGeneratedBy ex:run ;
  p-plan:correspondsToVariable cpp:collocationObservation .
```

Binding: corpus snapshot e index; node xsd:string; windowLeft/windowRight,
minimumFrequency e minFrequency xsd:nonNegativeInteger; sentenceBoundary,
ordered e caseSensitive xsd:boolean; minScore xsd:decimal; direction e
sortOrder xsd:string; limit xsd:positiveInteger. Measures, stopList ed
excludeTerms sono rdf:Bag con proprietà rdf:_n, rispettivamente individui
CollScore e stringhe. Tutti i binding sono membri della configurazione e usati
direttamente dal run. L'indice deriva dallo snapshot, il software è associato
con PROV e la versione usa `https://schema.org/softwareVersion`.

La dichiarazione metodologica normalizzata è anche conservata come
`dct:description` con datatype `rdf:JSON` sul run, per ricostruire senza perdita
i selettori Lucene originali nel dettaglio. Non contiene l'elenco delle
associazioni: quelle hanno come unica fonte le relazioni PROV aggiornate da
PATCH. Non è un CRUD generico dei metadati; `MetadataPolicy` rimane invariata.

`CppMeasureRegistry` centralizza le 18 coppie individuo/score property CollScore:
CooccurrenceFrequency, RelativeFrequency, PMI, PMI2, PMI3, NPMI, PMILogFreq,
Dice, LogDice, MinimumSensitivity, TScore, ZScore, LogLikelihood, ChiSquared,
Support, Confidence, Lift e Conviction. I binding referenziano gli individui,
non le proprietà numeriche. Le formule restano in `CollocationStatisticsService`.

### Concorrenza e deployment

Controllo dell'unico generatore e mutazione sono nella stessa sezione critica
statica, condivisa fra tutte le istanze del manager nella JVM. Due POST/PATCH
concorrenti sulla stessa FrAC non possono assegnarla a due run.
**Configurare un solo writer applicativo CPP per repository.** Per più repliche
che scrivono allo stesso repository serve una serializzazione distribuita prima
di abilitare CPP su tutte: il lock JVM e le restrizioni OWL non garantiscono
unicità fra nodi. Anche scritture esterne al modulo possono invalidare il graph.
L'export legge entrambi i gruppi di graph nella stessa connessione lessicale,
ma non promette uno snapshot distribuito con aggiornamenti di altri moduli.

## Export Turtle

```bash
curl --get "$BASE/cpp/runs/export" \
  --data-urlencode "run=$RUN_IRI" \
  --data-urlencode 'includeCollocations=true' -o cpp-runs.ttl
curl "$BASE/cpp/runs/export" -o cpp-provenance-all.ttl
```

`run` è ripetibile e contiene IRI complete; assente seleziona tutti. Duplicati
sono deduplicati. Tutti i riferimenti sono validati prima di avviare la risposta:
IRI illecita 400, run assente 404, FrAC eliminata 422 quando si richiede la sua
descrizione. Senza run e senza filtro restituisce Turtle vuoto valido.

Default `includeCollocations=false`: run, configurazioni, tutti i binding,
snapshot, indice, software locale e triple CPP delle osservazioni. Con `true`
aggiunge le descrizioni FrAC esplicite e i nodi interni: blank node di metadati,
`collscore:hasScoreResult` (o inverso esplicito `scoreFor`) → ScoreResult e
`collscore:hasCountData` →
CooccurrenceCountData tipizzati nei graph ammessi. Conserva score CollScore e
legacy, datatype, forma lessicale dei numeri e language tag. Non espande
entrate, corpora, agenti o altre IRI esterne; non esporta inferenze o schemi.

L'export unisce e deduplica le triple, elimina i contesti, ordina i run e le
triple e serializza Turtle. **È un RDF extract, non un backup dei named graph:**
non contiene il posizionamento originario delle triple e non offre import.
Usa un modello con limiti di triple e caratteri e un file temporaneo privato,
completamente preparato prima della risposta. Il limite di byte effettivo è
verificato anche durante la serializzazione. I temporanei sono rimossi in
caso di errore, al termine dello streaming o dopo una disconnessione client.
Header: `Content-Disposition: attachment; filename="cpp-runs.ttl"` e
`X-Content-Type-Options: nosniff`.

## Configurazione e bootstrap

| Proprietà | Default |
|---|---|
| GraphDb.cppNamedGraph | https://lexo.ilc.cnr.it/graphs/lexical/cpp/runs |
| CPP.resourceNamespace | https://lexo.ilc.cnr.it/id/cpp/ |
| CPP.export.maxRuns | 1000 |
| CPP.export.maxTriples | 50000 |
| CPP.export.maxBytes | 16777216 (anche budget caratteri del modello) |
| CPP.write.maxCollocations | 100 |
| CPP.write.maxCorpusResources | 1000 |
| CPP.write.maxBytes | 1048576 |
| CPP.write.maxStringLength | 8192 |
| CPP.write.maxListItems | 1000 |
| CPP.list.maxLimit | 500 |

Le proprietà seguono gli override esistenti (file esterno, variabili `LEXO_*`,
proprietà JVM). Il superamento di un limite produce 413, senza troncamenti.
Cambiare namespace o graph dopo aver creato run richiede una migrazione
amministrativa esterna al modulo; non sposta automaticamente dati esistenti.

Il manifest importa `collocation-score.ttl` 1.2.0 e `cpp.ttl` 0.2.1, con byte
identici al pacchetto concordato. Il parser è deterministico per estensione:
`.ttl` Turtle, `.rdf`/`.owl` RDF/XML. Anche il report amministrativo degli
schemi usa questa regola. Checksum dei byte, transazione, rollback e idempotenza
rimangono attivi. `owl:imports` non avvia download remoti.
FrAC, P-Plan e PROV-O sono referenziati ma **non** installati come schemi locali
completi dal manifest corrente. Le API validano i vincoli esplicitamente;
non dipendono dal ragionamento su queste dipendenze. Per ulteriori inferenze
occorrono copie locali verificate e versionate con licenze compatibili.

Follow-up fuori dal CRUD CPP: `CollocationPersistenceManager` usa ancora 15
proprietà FrAC legacy. RAW_FREQUENCY, NPMI e Z_SCORE non hanno mapping nel suo
input di persistenza degli score. L'import CollScore non migra tali dati:
l'export conserva esclusivamente gli score realmente presenti.

## Errori e test

Errori JSON `{code, message, details}`; nessuna query, trace o credenziale al
client. Codici principali: `CPP_BAD_JSON`, `CPP_UNKNOWN_FIELD`,
`CPP_INVALID_PARAMETER`, `CPP_INVALID_IRI` (400), `CPP_UNAUTHORIZED` (401),
`CPP_RUN_NOT_FOUND`, `CPP_COLLOCATION_NOT_FOUND` (404),
`CPP_COLLOCATION_ALREADY_LINKED`, `CPP_LAST_COLLOCATION`,
`CPP_IDEMPOTENCY_CONFLICT` (409), `CPP_LIMIT_EXCEEDED` (413),
`CPP_INVALID_SEMANTICS`, `CPP_EXPORT_INCONSISTENT` (422),
`CPP_STORAGE_ERROR`, `CPP_EXPORT_ERROR` (500),
`CPP_REPOSITORY_UNAVAILABLE` (503 per acquisizione connessione fallita).

```bash
mvn test
mvn -Dtest=CppRunManagerTest,CppContractTest,CppRunsTest,GraphDbBootstrapTest test
mvn -DskipTests package
```

I test unitari/repository usano RDF4J MemoryStore e verificano valori RDF,
lingue, datatype, graph, assenza di scritture nel default graph, rollback,
concorrenza POST/PATCH, idempotenza, limiti, streaming interrotto, checksum,
bootstrap ripetuto, formati storici e Swagger. Non sono E2E GraphDB/Tomcat.

Il test remoto è esclusivamente opt-in e richiede un deployment isolato con un
repository lessicale dedicato e lo stesso graph lessicale dichiarato:

```bash
mvn verify -Dit.test=CppRunsIT \
  -Dlexo.test.cpp.dedicated=true \
  -Dlexo.test.cpp.baseUrl=http://localhost:8080/LexO-cpp-test/service \
  -Dlexo.test.cpp.lexicalRepositoryUrl=http://localhost:7200/repositories/LexOCppTest
```

Aggiungere `-Dlexo.test.cpp.authorization='Bearer TOKEN'` se necessario.
`lexo.test.cpp.lexicalGraph` sovrascrive il graph legacy predefinito del test.
Il test crea esclusivamente FrAC con IRI univoche, verifica l'intero ciclo
REST e l'export, poi elimina run e fixture in `finally`. Nessun corpus o indice
Lucene viene modificato. Senza le proprietà opt-in il test risulta skipped.
