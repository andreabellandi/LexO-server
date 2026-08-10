# Test dei servizi del testo

Questa suite verifica il comportamento introdotto per TXT, CommonMark controllato,
CoNLL-U, metadati NIF e corpus. È divisa in test unitari, sempre eseguibili, e test
end-to-end contro un LexO-server realmente avviato con GraphDB Free.

## Struttura della suite

| Classe | Livello | Cosa verifica |
|---|---|---|
| `ControlledCommonMarkParserTest` | Unitario | Distinzione TXT/CommonMark, struttura, codici di errore, front matter e corpus metadata-only |
| `Iso639LanguageValidatorTest` | Unitario | Validazione del campo upload nelle prime quattro colonne ISO 639 e codici di errore stabili |
| `NifModelWriterTest` | Unitario RDF | Mapping dcterms, letterali/IRI, liste miste, corpus senza testo, appartenenza e offset Unicode |
| `ConlluSegmenterTest` | Unitario | Segmentazione CoNLL-U, offset obbligatori e corrispondenza tra FORM e testo canonico |
| `TextBulkImportValidatorTest` | Unitario | Ammissione di TXT/CommonMark, limite numerico e rifiuto stabile di CoNLL-U nel bulk |
| `TextBulkJobManagerTest` | Unitario | Stati aggregati pending, running, completi, parziali, falliti e cancellati |
| `TextCatalogManagerTest` | Unitario repository | Elenco testi, filtro corpus, dimensione canonica, metadati e conteggio attestazioni FRAC |
| `TextTotalManagerTest` | Unitario repository | Creazione e sovrascrittura dei totali FRAC di testi/corpora, unità ammesse e named graph |
| `TextServicesIT` | End-to-end | Upload singolo e bulk, risultato parziale, rifiuto CoNLL-U, job asincrono, download, GraphDB, corpus, eliminazione e rollback |
| `TextServiceUseCasesIT` | Workflow end-to-end | Casi d'uso multi-chiamata verificati via REST, SPARQL sul repository testi e filesystem |

Tutte le classi di questa suite riguardano soltanto il dominio **testi**. Non
chiamano servizi lessicali, non eseguono query sul repository del lessico e non
creano o modificano indici lessicali.

La persistenza è intenzionalmente ibrida: NIF, record operativi, metadati e
relazioni di appartenenza sono letti e scritti nel repository `LexOTexts`;
il filesystem conserva i file originali caricati, gli eventuali CoNLL-U e i
descrittori originali dei corpus. Il testo canonico viene ricavato dal NIF e non
viene duplicato in un file `canonical.txt`; analogamente non vengono creati file
`metadata.json` locali. I record operativi sono isolati nel named graph interno
`https://lexo.ilc.cnr.it/graphs/nif/records`, quindi non contaminano il Turtle
NIF scaricato per un documento o un corpus.

L'upload di ogni TXT/CommonMark richiede il campo multipart `language`. Il
valore è confrontato senza distinzione tra maiuscole e minuscole con le prime
quattro colonne di
`src/main/resources/iso639/lista_ufficiale_isocode_ISO_639.csv` e viene
normalizzato in minuscolo. Un valore assente produce `MISSING_LANGUAGE`; un
valore non presente produce `INVALID_LANGUAGE`. Il codice validato viene scritto
come `dcterms:language` nel NIF e usato come language tag del testo e dei suoi
segmenti.

Il bulk usa un solo campo `language` per tutti i file e accetta esclusivamente
parti `file` con estensione `.txt`, `.md` o `.markdown`. La presenza di una parte
`conllu` o di un'estensione CoNLL-U rifiuta l'intera richiesta prima della
conversione. Dopo l'ammissione ciascun documento conserva invece job, `fileId`,
persistenza e rollback indipendenti; lo stato aggregato può quindi essere
`PARTIALLY_COMPLETED`.

I test RDF non confrontano Turtle come una stringa. Caricano il risultato in un
`Model` RDF4J e verificano soggetto, predicato e tipo dell'oggetto. In questo modo
ordine delle triple, prefissi e formattazione non causano falsi fallimenti.

## Esecuzione dei test unitari

Prerequisiti:

- JDK compatibile con il progetto;
- Maven 3.8 o successivo;
- dipendenze Maven del progetto disponibili.

Dalla directory principale del repository:

```bash
mvn test
```

Per eseguire una sola classe:

```bash
mvn -Dtest=ControlledCommonMarkParserTest test
mvn -Dtest=NifModelWriterTest test
mvn -Dtest=ConlluSegmenterTest test
mvn -Dtest=TextBulkImportValidatorTest,TextBulkJobManagerTest test
```

Per un singolo caso:

```bash
mvn -Dtest=NifModelWriterTest#mapsMixedLiteralAndUriLists test
```

I report XML e testuali vengono scritti in `target/surefire-reports`.

## Esecuzione end-to-end

Questi test sono esclusi da `mvn test` perché modificano una vera installazione.
Prima di eseguirli:

1. avviare GraphDB Free;
2. configurare LexO-server con un repository testi dedicato ai test;
3. avviare LexO-server e attendere il completamento del bootstrap;
4. ottenere un valore valido per l'header HTTP `Authorization`;
5. non usare repository di sviluppo o produzione.

La `baseUrl` deve terminare alla radice dei servizi Jersey, senza slash finale. Con
il WAR standard è normalmente simile a:

```text
http://localhost:8080/LexO-server/service
```

Esecuzione:

```bash
mvn verify \
  -Dlexo.test.baseUrl=http://localhost:8080/LexO-server/service \
  -Dlexo.test.authorization='VALORE_COMPLETO_DELL_HEADER' \
  -Dlexo.test.storageDir=/percorso/assoluto/data/texts
```

`lexo.test.authorization` deve contenere esattamente il valore che il client invia
dopo `Authorization:`. Se l'installazione usa il prefisso `Bearer`, includerlo:

```bash
-Dlexo.test.authorization='Bearer eyJ...'
```

`lexo.test.storageDir` è facoltativo e va specificato soltanto quando i test girano
sullo stesso host di LexO-server. Se presente, gli scenari di errore ed eliminazione
verificano direttamente che non rimangano directory in `uploads`, `documents` e
`work`. Verificano inoltre che, dopo una conversione riuscita, originali ed
eventuali CoNLL-U siano presenti in `documents`, mentre record e testo canonico
siano recuperabili da `LexOTexts`. Per un server remoto omettere la proprietà.

I report end-to-end vengono scritti in `target/failsafe-reports`.

Se `mvn verify` viene eseguito senza `lexo.test.baseUrl` o senza autorizzazione, i
test end-to-end risultano *skipped* anziché tentare accidentalmente una connessione.

## Esecuzione dei workflow completi

I workflow in `TextServiceUseCasesIT` richiedono anche l'accesso SPARQL diretto al
repository GraphDB dei testi e l'accesso locale alla directory di persistenza. Se
una di queste configurazioni manca, i quattro workflow vengono saltati: non vengono
eseguiti con controlli parziali.

```bash
mvn verify \
  -Dlexo.test.baseUrl=http://localhost:8080/LexO-server/service \
  -Dlexo.test.authorization='Bearer TOKEN_LEXO' \
  -Dlexo.test.graphdbUrl=http://localhost:7200 \
  -Dlexo.test.textRepository=LexOTexts \
  -Dlexo.test.namedGraphBase=https://lexo.ilc.cnr.it/graphs/nif/ \
  -Dlexo.test.storageDir=/percorso/assoluto/data/texts
```

Se GraphDB richiede autenticazione, aggiungere il valore completo del relativo
header:

```bash
-Dlexo.test.graphdbAuthorization='Basic BASE64_USER_PASSWORD'
```

Proprietà dei workflow:

| Proprietà | Obbligatoria | Significato |
|---|---:|---|
| `lexo.test.baseUrl` | sì | Radice Jersey di LexO-server |
| `lexo.test.authorization` | sì | Header Authorization per LexO-server |
| `lexo.test.graphdbUrl` | sì | URL della sola installazione GraphDB usata dai testi |
| `lexo.test.textRepository` | sì | ID del repository GraphDB dei NIF testuali |
| `lexo.test.namedGraphBase` | no | Base dei named graph NIF; usa il default LexO se omessa |
| `lexo.test.storageDir` | sì | Directory configurata come `lexo.text.storage.dir` |
| `lexo.test.graphdbAuthorization` | no | Header Authorization per GraphDB |

### Casi d'uso implementati

- **UC-01 — documento autonomo:** upload, controllo area temporanea, conversione,
  polling, download NIF, ASK SPARQL, controllo degli artefatti finali, eliminazione
  e verifica della pulizia completa;
- **UC-02 — corpus con due documenti:** creazione corpus, due conversioni,
  verifica bidirezionale `hasPart`/`isPartOf`, eliminazione del corpus, controllo
  che i documenti sopravvivano scollegati e cleanup finale;
- **UC-03 — TXT, CoNLL-U e metadati misti:** upload multipart, conversione,
  download CoNLL-U e verifica SPARQL di lemma, autore letterale e autore URI;
- **UC-04 — rollback:** CoNLL-U non allineato, stato `FAILED`, assenza del record,
  assenza del named graph e rimozione di upload, work directory e artefatti finali.

## Dati creati dai test end-to-end

Ogni scenario usa nomi univoci. Nei blocchi `finally` elimina documenti e corpus
creati, anche quando un'asserzione fallisce. La suite controlla inoltre che una
conversione non valida non esponga né il record del testo né il relativo NIF.

È comunque opportuno dedicare ai test:

- un repository GraphDB per i testi;
- una directory `lexo.text.storage.dir` separata.

Questo garantisce isolamento anche in caso di arresto forzato della JVM prima del
cleanup del test.

## Scenari coperti

### TXT e CommonMark

- TXT senza `#` accettato e segmentato;
- paragrafi delimitati da righe vuote;
- CommonMark controllato valido;
- `TEXT_OUTSIDE_HEADING`, `INVALID_HEADING` e `MISSING_HEADING`;
- front matter non chiuso e caratteri NUL;
- chiave `language` nel front matter ignorata perché la lingua proviene
  obbligatoriamente dal campo multipart dell'upload;
- descriptor di corpus con soli metadati;
- rifiuto del testo nel descriptor di corpus.

### Metadati RDF

- mapping di `id`, `title`, `author`, `date`, `description`, `format` e `corpus`;
- mapping del campo upload validato in `dcterms:language`;
- chiavi sconosciute e relative liste ignorate;
- valori multipli;
- liste miste di stringhe e URI;
- URI semplice, `<URI>`, `[URI](URI)` e `<[URI](URI)>`;
- `id` URI rappresentato come IRI;
- `dcterms:isPartOf` e `dcterms:hasPart`;
- corpus privo di `nif:isString`.

### CoNLL-U

- sostituzione della segmentazione automatica;
- conservazione di lemma e identificativo frase;
- obbligatorietà di `TokenRange` o `start_char`/`end_char`;
- errore quando `FORM` non coincide con la sottostringa indicata dagli offset.

### Ciclo REST e rollback

- upload e conversione asincrona di TXT semplice;
- upload bulk di TXT/CommonMark con una lingua comune, polling aggregato e
  rollback indipendente che conserva i documenti riusciti;
- rifiuto atomico del bulk quando è presente una parte CoNLL-U;
- rifiuto dell'upload senza lingua o con un codice assente dalla lista ISO 639;
- polling fino a `COMPLETED`, `FAILED` o `CANCELLED` con timeout;
- download di originale, canonicale e NIF;
- creazione di corpus e aggiunta di un documento;
- creazione batch tramite `POST /attestations/by-locus`, verificando una
  attestazione e una frequenza FRAC per osservabile, un solo locus NIF e rollback
  completo in caso di osservabile o metadato non valido;
- verifica RDF in entrambi i NIF;
- aggiornamento del corpus dopo la cancellazione del documento;
- cancellazione del corpus;
- assenza di record e NIF dopo una conversione fallita.

### Metadata delle attestazioni

I test repository complementari in `AttestationManagerTest` verificano
l'aggiornamento batch atomico dei metadata, con sostituzione e cancellazione per
proprietà, valori multipli, IRI e letterali tipizzati o con lingua, isolamento
nel named graph del documento, assenza di scritture nel default graph, rollback
del batch non valido e rifiuto dei predicati strutturali. Non costituiscono un
test end-to-end del routing HTTP della PATCH.

La creazione `POST /attestations/by-locus` riusa la stessa struttura comune e la
stessa policy: i test verificano che i metadati di ciascun elemento siano scritti
soltanto sull'IRI della relativa attestazione, preservando IRI, lingua e datatype,
e che un predicato protetto annulli l'intero batch senza scritture parziali.

La stessa classe verifica l'aggiornamento del locus di una singola attestazione:
gli offset sono interpretati come code point Unicode, il nuovo valore viene
estratto da `nif:isString`, l'attestazione viene ricollegata all'IRI RFC5147 di
destinazione e `rdf:value`/`frac:gloss` vengono aggiornati nel graph delle
attestazioni. I test coprono `updateGloss=false`, la creazione di una
destinazione assente, il riuso di destinazioni compatibili sia di sistema sia
LexO, la conservazione dei loci precedenti di sistema o ancora condivisi e la
cancellazione del solo locus LexO rimasto orfano. L'aggiornamento observable è
verificato sia come bulk riuscito sia come batch interamente respinto quando una
delle attestazioni non appartiene al graph selezionato.

### Ricerca filtrata delle attestazioni

I test repository in `AttestationManagerTest` verificano anche la ricerca
paginata delle attestazioni di un observable attraverso più named graph e il
filtro condiviso con la consultazione per testo. La copertura comprende gruppi
`AND`/`OR` annidati, più autori in alternativa, metadati del testo confrontati
come valori RDF esatti, più tipi OntoLex in `OR` e sottoclassi transitive di
`ontolex:LexicalEntry`. Sono verificati inoltre `totalHits`, `limit`, `offset`,
ordinamento deterministico ed esclusione dei graph che non sono membri validi
della famiglia configurata delle attestazioni. La risoluzione degli observable
accetta entry, form e sense soltanto nei graph lessicali ISO per lingua e i
lexical concept soltanto nel graph fisso di categoria; graph legacy, default e
abbinamenti categoria/graph errati sono respinti. I test verificano inoltre che
la creazione esponga tipo e label del senso dal medesimo named graph, usando la
label della entry collegata come fallback quando manca `skos:definition`.
Non è ancora presente un test
end-to-end del nuovo routing HTTP.

### Cancellazione delle attestazioni

I test repository in `AttestationManagerTest` verificano inoltre che i servizi
di cancellazione per observable e per locus:

- accettino sia una lista esplicita di IRI sia la selezione completa `all`;
- validino atomicamente appartenenza al named graph, observable e locus prima di
  rimuovere qualsiasi tripla;
- cancellino risorsa FRAC e collegamento entrante `frac:attestation` soltanto
  dal graph selezionato da `fileId`;
- conservino un locus ancora referenziato;
- eliminino un locus orfano soltanto in presenza di
  `prov:wasGeneratedBy lexo:AttestationService`;
- non aggiungano il marcatore e non eliminino loci NIF compatibili preesistenti;
- non scrivano o cancellino dati nel default graph.

Gli stessi test verificano che creazione singola e batch mantengano un solo
oggetto `frac:Frequency` per coppia observable/testo, con `rdf:value` tipizzato
`xsd:int` e `frac:observedIn` riferito al contesto NIF specifico anche quando la
richiesta usa un corpus. Cancellazione per observable o locus e sostituzione
dell'observable riallineano tutti i conteggi coinvolti e rimuovono l'oggetto
quando il valore residuo è zero. Le triple restano nel named graph documento e
il default graph rimane vuoto.

Questi test non costituiscono ancora un test end-to-end del routing HTTP dei due
endpoint `DELETE`.

### Catalogo dei testi

- elenco di tutti i documenti presenti nei named graph di `LexOTexts`;
- filtro opzionale per `corpusId` e rifiuto di corpus inesistenti;
- nome originale e dimensione UTF-8 del valore canonico `nif:isString`;
- conteggio di frasi e token quando le relative risorse NIF sono disponibili;
- metadati singoli e multivalore;
- conteggio delle sole `frac:Attestation` presenti nel named graph specifico
  `.../attestations/documents/{fileId}`;
- conteggio delle sole `oa:Annotation` presenti nel named graph specifico
  `.../annotations/documents/{fileId}`;
- esclusione di attestazioni e annotazioni presenti nel default graph o nei
  named graph appartenenti ad altri testi;
- cancellazione con il testo dei due named graph associati e verifica che i
  grafi appartenenti agli altri testi rimangano invariati.

### Totali FRAC di testi e corpora

I test repository verificano i due servizi di sostituzione dei totali usando un
repository RDF4J in-memory dedicato. Sono coperti `lexo:tokens`, `lexo:types`,
`lexo:lemmas` e `lexo:sentences`, la normalizzazione da nome locale, prefisso
compatto o IRI completo, il rifiuto di valori negativi e unità sconosciute, e il
risultato `null` del manager per risorse inesistenti. La sovrascrittura elimina
eventuali duplicati della stessa unità senza toccare i totali delle altre unità.
Le asserzioni controllano `frac:Frequency`, `rdf:value` come literal `xsd:int`,
`frac:unit` come IRI, graph documento/corpus corretto e default graph vuoto.

## Aggiungere un caso

Un nuovo test dovrebbe seguire la forma **given / when / then** visibile nei nomi e
nei commenti:

1. costruire un input minimo che rappresenti il caso;
2. eseguire una sola operazione principale;
3. verificare risultato positivo e assenza di effetti collaterali;
4. per RDF, verificare anche se l'oggetto è un `IRI` o un `Literal`;
5. per errori, verificare il codice macchina (`ValidationIssue.code`), non soltanto
   il messaggio italiano;
6. per test remoti, eliminare sempre le risorse in un blocco `finally`.
