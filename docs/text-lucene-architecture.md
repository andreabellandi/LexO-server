# Architettura NIF e Lucene dei servizi testuali

## Responsabilità e invarianti

`LexOTexts` resta la fonte RDF autorevole per contesti NIF, metadati, corpus,
`nif:Word` e `nif:Sentence`. Un indice Apache Lucene 8.11 standalone su
filesystem fornisce ricerca e analisi posizionale. Il sottosistema testuale non
usa il Lucene Connector di GraphDB, `regex` SPARQL o `CONTAINS` per la ricerca.
I connector GraphDB presenti nel bootstrap rimangono esclusivamente lessicali.

Ogni documento riuscito deve avere la stessa coppia di fingerprint in RDF e
Lucene:

- `contentHash`: SHA-256 del `nif:isString` canonico;
- `segmentationHash`: SHA-256 deterministico di versione, fonte, profili,
  sentence e token span.

Un import indicizza prima il documento Lucene e salva poi RDF/record in una
transazione. Un fallimento RDF elimina il documento Lucene; un fallimento
Lucene non scrive RDF. La cancellazione conserva gli snapshot necessari e
compensa l'operazione già riuscita se l'altra persistenza fallisce. Bulk import
e bulk delete mantengono la semantica esistente per-item, applicando la stessa
atomicità a ogni elemento.

## CanonicalText

TXT e `text.content` JSON sono decodificati come UTF-8 stretto prima
dell'ammissione. La canonicalizzazione:

1. rimuove un solo BOM Unicode iniziale;
2. converte CRLF e CR in LF;
3. normalizza Unicode in NFC;
4. preserva il restante whitespace;
5. rimuove l'eventuale front matter TXT prima di assegnare gli offset.

Il CommonMark controllato conserva il comportamento del parser applicativo:
front matter opzionale, heading nella forma `# [id=...] Titolo`, soli heading e
paragrafi, soft break resi come spazio. Il Markdown sorgente non viene mai
indicizzato: Lucene riceve esattamente la stringa resa salvata come
`nif:isString`.

## CanonicalSegmentation

Una sola rappresentazione interna collega testo, fonte, sentence e token. La
precedenza è:

```text
CONLLU > ANNOTATED_IMPORT > LUCENE_STANDARD
```

- `CONLLU`: i blocchi definiscono le sentence; solo gli ID interi definiscono
  posizioni. Range multiword ed empty node non aggiungono token. `TokenRange`
  o gli offset equivalenti sono obbligatori e `FORM` deve coincidere con il
  CanonicalText. `SpaceAfter=No` viene preservato dalla superficie e non crea
  posizioni extra. Non vengono eseguiti tokenizer o sentence splitter Lucene.
- `ANNOTATED_IMPORT`: lo schema JSON opzionale `segmentation` deve contenere
  array non vuoti `tokens` e `sentences`. Gli span pubblici sono code point,
  `sentence` è un indice zero-based e gli eventuali `text`, `lemma`, `pos` sono
  validati/conservati. Span ambigui, sovrapposti o non coincidenti sono
  rifiutati.
- `LUCENE_STANDARD`: `StandardTokenizer` 8.11 definisce i token; Java
  `BreakIterator` con profilo `UNICODE_SENTENCE_JAVA8` definisce le sentence
  sulla stessa stringa. Nessuna stopword, stemming o lemmatizzazione altera la
  sequenza canonica.

Per NIF legacy senza provenienza, reindex/rebuild ricostruiscono sempre gli span
persistiti senza ritokenizzare. `segmentationMethod=conllu` viene riconosciuto;
gli altri legacy sono marcati `PERSISTED_NIF`, con profili `NIF_PERSISTED`.

## NIF, offset e posizioni

Ogni token canonico genera un `nif:Word`; ogni sentence canonica genera un
`nif:Sentence`. Anchor, begin/end e relazione token-sentence provengono dalla
stessa segmentazione usata dal token stream Lucene.

Java e Lucene usano offset UTF-16. Le API e NIF usano indici di code point
Unicode. `UnicodeOffsetMapper` è l'unico punto di conversione e rifiuta offset
che spezzano una surrogate pair. Le posizioni Lucene sono invece indici token
zero-based: non sono offset carattere.

Per il positional field il token stream custom imposta `CharTermAttribute`,
`OffsetAttribute` e `PositionIncrementAttribute=1`. Questo vale anche per
CoNLL-U e JSON annotato, che non passano da `StandardTokenizer`. I campi
`content_surface` e `content_normalized` hanno positions, offsets e term vector;
il secondo applica solo lowercase. La stoplist è un filtro dei risultati di
collocazione e non cambia mai indice, `tokenCount`, posizioni o offset.

## Documento e ciclo di vita Lucene

Un documento Lucene corrisponde a un context e contiene almeno `fileId`,
`contextIRI`, graph/corpus opzionali, lingua, testo canonico memorizzato,
fingerprint, provenienza/versioni, conteggi e snapshot JSON della token map. Il
path e la directory temporanea di rebuild sono configurazione server-side e
non possono essere scelti dal client.

`IndexWriter` e `SearcherManager` gestiscono commit e refresh. Un read/write
lock impedisce che un rebuild o lo shutdown sostituisca il manager mentre una
ricerca ha un searcher acquisito. Startup degradato lascia disponibili i
servizi RDF ma gli endpoint dipendenti dall'indice rispondono 503.

Il rebuild crea un indice temporaneo, lo valida con `CheckIndex`, chiude quello
corrente, esegue uno swap atomico dove supportato e ripristina il backup se
l'apertura del nuovo indice fallisce.

## Ricerca, KWIC e co-occorrenze

`POST /texts/search/fulltext` supporta `TERM`, `PHRASE`, `BOOLEAN`, `PREFIX`,
`WILDCARD` e `FUZZY`. Lunghezza, clausole, espansioni multi-term e pagina sono
limitati. Le risposte sono occurrence, non soli document hit, e includono
anchor, code-point offsets, token/sentence index, score, KWIC e fingerprint.
Le occurrence sono estratte tramite `MatchesIterator` dalle positions Lucene;
la token map canonica converte poi le positions in offset NIF e contesto.
I filtri context/corpus/graph sono keyword query Lucene validate come IRI.
L'arricchimento opzionale usa batch SPARQL 1.1 `VALUES` su `LexOTexts`.

`POST /texts/search/kwic/resize` è stateless: verifica entrambi gli hash,
localizza la precedente occurrence tramite token o offset e ricalcola solo il
contesto. Le unità sono `TOKEN`, `CHARACTER` o `SENTENCE`, con ampiezze sinistra
e destra indipendenti.

`POST /texts/search/cooccurrences` considera tutte le coppie ordinate. Per un
node in posizione `p`, `leftWindow=5` significa `p-5..p-1` e
`rightWindow=5` significa `p+1..p+5`; `p` non conta. Non vengono deduplicate
occorrenze sovrapposte o ripetute. Le candidate positions e le frequenze sono
lette dai term vector Lucene, senza rileggere i testi da RDF. La risposta espone
distanza signed/absolute, gap e direzione. `boundary=DOCUMENT` può attraversare
sentence; `SENTENCE` richiede lo stesso canonical sentence. `direction` accetta
`LEFT`, `RIGHT` o `BOTH`.

## Frequenze, collocati e metriche

`POST /texts/search/frequency` calcola con l'indice token frequency, document
frequency, totale token, frequenza relativa e per milione. L'estrazione
collocati supporta subset, case, finestre asimmetriche, boundary, direction,
`excludeTerms`/`stoplist`, frequenze minime, score minimo, ordinamento e limite.
I tie sono deterministici: score, frequenza, termine.

`CollocationStatisticsService` espone:

```text
RAW_FREQUENCY, RELATIVE_FREQUENCY,
PMI, PMI2, PMI3, NPMI, PMI_LOG_FREQ,
DICE, LOG_DICE, MIN_SENSITIVITY,
T_SCORE, Z_SCORE, LOG_LIKELIHOOD, CHI_SQUARE,
SUPPORT, CONFIDENCE, LIFT, CONVICTION
```

Le prime misure usano `TOKEN_SPACE` con `N`, `fx`, `fy`, `fxy`.
Log-likelihood, chi-square e regole associative dichiarano `PAIR_SPACE`, con
tutte le coppie ordinate eleggibili (`pairN`, `pairFx`, `pairFy`, `pairFxy`).
Valori non definiti sono `{defined:false,value:null,reason:...}`: mai NaN o
infinito JSON.

La ricerca non scrive RDF. `POST /texts/collocations/persist` è l'azione
esplicita che crea una risorsa `frac:Collocation` nel named graph lessicale,
riusando soltanto le proprietà effettivamente presenti in `frac.ttl`; metriche
senza proprietà disponibile vengono rifiutate invece di inventare URI.

## Consistenza e migrazione

- `GET /texts/index/status`: disponibilità, versione, schema, profilo, conteggio
  e ultimo commit senza esporre path.
- `POST /texts/index/verify`: differenze RDF/Lucene per presenza e hash; con
  `deep=true` anche token/sentence count.
- `POST /texts/index/reindex/{fileId}`: ricostruisce esattamente gli span
  `nif:Word`/`nif:Sentence` di un context.
- `POST /texts/index/rebuild`: ricostruisce tutti i context con safe swap.

Dopo il deploy, i documenti RDF preesistenti risultano inizialmente
`missingInLucene`: eseguire `verify`, poi `rebuild`, infine `verify?deep=true`.
I documenti legacy non vengono modificati e non serve un connector GraphDB.

## Configurazione e sicurezza

Le proprietà `lexo.lucene.*` configurano path, directory rebuild, pagine,
contesti, finestre, query, clausole, espansioni wildcard, cache e versioni. I
limiti upload separati sono `lexo.text.maxTxtBytes`,
`lexo.text.maxMarkdownBytes`, `lexo.text.maxConlluBytes` e
`lexo.text.maxJsonImportBytes`; restano anche i limiti bulk.

Filename e `fileId` sono sanificati, i path HTTP non controllano filesystem,
gli IRI sono assoluti, i valori SPARQL sono serializzati come N-Triples e le
query Lucene patologiche sono limitate. Gli errori distinguono parametri/UTF-8
(400), context assente (404), conflitti di hash/segmentazione (409), dimensione
(413), errori semantici di allineamento nel job (422 equivalente nello stato),
failure RDF/Lucene (500) e indice non disponibile (503).

## Audit endpoint `Text Corpus NIF`

In Swagger i dieci endpoint di ricerca, collocazione e amministrazione
dell'indice sono raccolti nel gruppo `Text Search`; gli endpoint testuali
preesistenti restano nel gruppo `Text Corpus NIF`. Il raggruppamento non cambia
i path REST elencati di seguito.

| Endpoint | Stato dopo il refactoring |
|---|---|
| `GET /texts` | [OK] unchanged; catalogo RDF verificato, nessuna dipendenza da tokenizzazione |
| `POST /texts/upload` | [OK] adapted to canonical segmentation, Markdown, CoNLL-U e annotated JSON |
| `POST /texts/bulk` | [OK] adapted to canonical segmentation, annotated JSON e bulk consistency |
| `GET /texts/bulk/{bulkId}/status` | [OK] adapted to bulk consistency; espone gli esiti per-item |
| `DELETE /texts/bulk` | [OK] adapted to Lucene e bulk consistency tramite delete singolo |
| `GET /texts/deletions/{bulkId}/status` | [OK] adapted to bulk consistency |
| `POST /texts/{fileId}/convert` | [OK] adapted to Lucene e precedence della canonical segmentation |
| `POST /texts/corpora` | [OK] unchanged; crea un corpus RDF senza testo indicizzabile |
| `GET /texts/corpora/{corpusId}` | [OK] unchanged; lettura metadati/membership RDF |
| `PUT /texts/corpora/{corpusId}/total` | [OK] unchanged; totale FrAC non indicizzato |
| `GET /texts/corpora/{corpusId}/nif` | [OK] unchanged; export del graph corpus |
| `GET /texts/corpora/{corpusId}/original` | [OK] unchanged; descrittore originale |
| `DELETE /texts/corpora/{corpusId}` | [OK] adapted to Lucene; scollega i membri senza cancellarli |
| `GET /texts/{fileId}/status` | [OK] adapted to Lucene/bulk consistency nei job |
| `POST /texts/{fileId}/cancel` | [OK] adapted to rollback RDF/Lucene e cleanup |
| `GET /texts/{fileId}` | [OK] adapted to provenance, profili e fingerprint |
| `PUT /texts/{fileId}/total` | [OK] unchanged; aggiorna solo il graph RDF del testo |
| `GET /texts/{fileId}/nif` | [OK] adapted to canonical `nif:Word`/`nif:Sentence` |
| `GET /texts/{fileId}/original` | [OK] unchanged; l'originale resta distinto dal canonico |
| `GET /texts/{fileId}/canonical` | [OK] adapted to CanonicalText recuperato da NIF |
| `GET /texts/{fileId}/conllu` | [OK] adapted to CoNLL-U autorevole preservato |
| `DELETE /texts/{fileId}` | [OK] adapted to Lucene con compensazione RDF/Lucene |
| `POST /texts/search/fulltext` | [OK] nuovo, adapted to Lucene |
| `POST /texts/search/kwic/resize` | [OK] nuovo, hash-verified canonical segmentation |
| `POST /texts/search/cooccurrences` | [OK] nuovo, Lucene positions e sentence/document boundary |
| `POST /texts/search/frequency` | [OK] nuovo, frequenze Lucene |
| `POST /texts/collocations/extract` | [OK] nuovo, pair-space e metriche complete |
| `POST /texts/collocations/persist` | [OK] nuovo, persistenza FrAC esplicita e separata |
| `GET /texts/index/status` | [OK] nuovo, lifecycle Lucene |
| `POST /texts/index/verify` | [OK] nuovo, consistency RDF/Lucene |
| `POST /texts/index/reindex/{fileId}` | [OK] nuovo, ricostruzione senza ritokenizzazione |
| `POST /texts/index/rebuild` | [OK] nuovo, `CheckIndex` e safe swap |
