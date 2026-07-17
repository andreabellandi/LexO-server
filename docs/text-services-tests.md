# Test dei servizi del testo

Questa suite verifica il comportamento introdotto per TXT, CommonMark controllato,
CoNLL-U, metadati NIF e corpus. È divisa in test unitari, sempre eseguibili, e test
end-to-end contro un LexO-server realmente avviato con GraphDB Free.

## Struttura della suite

| Classe | Livello | Cosa verifica |
|---|---|---|
| `ControlledCommonMarkParserTest` | Unitario | Distinzione TXT/CommonMark, struttura, codici di errore, front matter e corpus metadata-only |
| `NifModelWriterTest` | Unitario RDF | Mapping dcterms, letterali/IRI, liste miste, corpus senza testo, appartenenza e offset Unicode |
| `ConlluSegmenterTest` | Unitario | Segmentazione CoNLL-U, offset obbligatori e corrispondenza tra FORM e testo canonico |
| `TextServicesIT` | End-to-end | Upload, job asincrono, download, GraphDB, corpus, eliminazione e rollback |
| `TextServiceUseCasesIT` | Workflow end-to-end | Casi d'uso multi-chiamata verificati via REST, SPARQL sul repository testi e filesystem |

Tutte le classi di questa suite riguardano soltanto il dominio **testi**. Non
chiamano servizi lessicali, non eseguono query sul repository del lessico e non
creano o modificano indici lessicali.

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
`work`. Per un server remoto omettere la proprietà.

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
- descriptor di corpus con soli metadati;
- rifiuto del testo nel descriptor di corpus.

### Metadati RDF

- mapping di `id`, `title`, `author`, `date`, `language`, `format` e `corpus`;
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
- polling fino a `COMPLETED`, `FAILED` o `CANCELLED` con timeout;
- download di originale, canonicale e NIF;
- creazione di corpus e aggiunta di un documento;
- verifica RDF in entrambi i NIF;
- aggiornamento del corpus dopo la cancellazione del documento;
- cancellazione del corpus;
- assenza di record e NIF dopo una conversione fallita.

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
