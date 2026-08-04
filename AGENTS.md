# Istruzioni permanenti per Codex

Queste regole valgono per tutte le attività future nel repository LexO-server.
Leggere anche `HANDOFF.md`, `README.md`, `src/main/resources/bootstrap/README.md`
e, per il dominio testuale, `docs/text-services-tests.md`. Per ogni nuovo
servizio CRUD lessicale leggere anche `docs/lexicon-services.md`.

## Prima di modificare

- Eseguire `git status -sb`, identificare il branch e preservare ogni modifica
  preesistente dell'utente.
- Usare esclusivamente il branch principale del repository, attualmente
  `master`. Non creare branch dedicati, feature branch o branch temporanei per
  le modifiche. Se il branch remoto predefinito verrà rinominato, usare il suo
  nuovo nome al posto di `master`.
- Prima di modificare, aggiornare `origin/master` e allineare il branch locale
  con un fast-forward. Se modifiche locali o una divergenza impediscono
  l'allineamento sicuro, fermarsi e chiedere istruzioni senza scartare o
  riscrivere il lavoro esistente.
- Non aggiungere ai commit `logs/`, `data/`, `target/`, `.DS_Store`,
  `nb-configuration.xml` o altri artefatti runtime/IDE.
- Cercare prima implementazioni, test e convenzioni esistenti; non duplicare
  manager, utility RDF o meccanismi di connessione.

## Invarianti architetturali

- LexO-server è un WAR Jersey/JAX-RS per Tomcat 9, con API `javax.*` e
  compatibilità Java 8. Non migrare a Jakarta, Tomcat 10 o sintassi Java più
  recente senza una richiesta esplicita e una migrazione completa.
- Non reintrodurre MySQL, Hibernate, Spring, GraphDB embedded o profili Maven.
- Usare `GraphDbUtil`/`RDFQueryUtil` con `RepositoryTarget`:
  - `LEXICON` per lessico, schema, attestazioni e annotazioni;
  - `TEXT` per NIF, corpora e record testuali.
- Non scrivere dati applicativi nel default graph.
- Le update lessicali legacy continuano a usare il graph `lexica`; i nuovi CRUD
  usano il named graph previsto per la categoria indicata dall'utente: le
  risorse lessicali dipendenti dalla lingua usano il graph specifico della
  lingua, mentre lexical concepts e concept sets usano il graph fisso definito
  nella sezione dedicata.
- Attestazioni e annotazioni devono usare il graph per documento e richiedere il
  `fileId`; la cancellazione di un testo deve eliminare entrambi i graph.
- Conservare il bootstrap GraphDB idempotente e guidato da template/manifest in
  `src/main/resources/bootstrap`; non reinserire definizioni degli indici nel
  codice Java.

## Regole del dominio testuale

- Supportare TXT semplice, CommonMark controllato ed eventuale CoNLL-U senza
  alterare il testo canonico o gli offset NIF.
- Gli offset sono code point Unicode sul valore `nif:isString`, non indici UTF-16
  e non offset sul sorgente CommonMark renderizzato.
- Front matter ammesso: `id`, `title`, `author`, `date`, `description`, `format`,
  `corpus`; ignorare chiavi sconosciute, incluso `language`.
- Ogni upload TXT/CommonMark richiede un campo multipart `language`, validato
  contro le prime quattro colonne della lista ISO 639 versionata nelle risorse;
  usare quel codice per segmentazione, metadati e language tag NIF.
- Preservare valori multipli. `description` deve essere sempre un letterale
  `dcterms:description`; rispettare le regole IRI già implementate per gli altri
  campi.
- Gli originali e gli eventuali CoNLL-U restano nel filesystem dopo una
  conversione riuscita. Il testo canonico e i record sono in `LexOTexts`; non
  creare `canonical.txt` o `metadata.json` locali.
- In ogni errore di upload/conversione eliminare gli artefatti parziali e le
  directory create; non eliminare artefatti appartenenti ad altri testi.
- La cancellazione di un corpus non deve cancellare i testi membri: deve
  scollegarli. La cancellazione di un testo deve aggiornare il corpus.

## Nuovi servizi CRUD del lessico

- Mantenere disponibili e invariati i servizi lessicali legacy durante la
  riscrittura; collocare tutti i nuovi endpoint nella classe `Lexicon.java`,
  annotata con `@javax.ws.rs.Path("lexica")` e `@Api("Lexica")`.
- Le letture e scritture applicative dei nuovi servizi CRUD per risorse
  lessicali dipendenti dalla lingua devono operare esclusivamente nel named
  graph specifico della lingua, con IRI
  `https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}`, mai nel default
  graph. Validare il codice lingua contro le prime quattro colonne della lista
  ISO 639 versionata in `src/main/resources/iso639`, normalizzarlo in minuscolo
  e creare/selezionare un graph distinto per ogni lingua diversa tramite il
  supporto centralizzato; non limitarsi a verificarne la forma sintattica.
- Tutti i nuovi servizi CRUD della categoria lexical concepts e concept sets
  devono leggere e scrivere esclusivamente nel named graph fisso
  `https://lexo.ilc.cnr.it/graphs/lexical/lexicalConcept` di `LexOLexica`, mai
  nel default graph o nei graph lessicali specifici della lingua. Questa regola
  di categoria prevale sulla regola generale dei graph per lingua.
- Per ogni nuovo servizio applicare la categoria specificata dall'utente e le
  relative regole di persistenza, sommandole alle regole condivise dei CRUD
  lessicali senza alterare i contratti delle altre categorie.
- Generare ogni nuova IRI concatenando, nell'ordine, i valori di
  `repository.lexicon.namespace`, `repository.instance.id` e il timestamp con
  millisecondi formattato tramite `manager.operationTimestampFormat`; sul
  timestamp formattato applicare
  `.replaceAll("\\s+", "").replaceAll(":", "_").replaceAll("\\.", "_")`.
  Riutilizzare il supporto centralizzato dedicato, senza duplicare la regola.
- Usare nei nuovi servizi CRUD lessicali esclusivamente queste associazioni di
  prefisso, senza ridefinizioni locali divergenti:
  - `decomp: <http://www.w3.org/ns/lemon/decomp#>`
  - `vartrans: <http://www.w3.org/ns/lemon/vartrans#>`
  - `ontolex: <http://www.w3.org/ns/lemon/ontolex#>`
  - `synsem: <http://www.w3.org/ns/lemon/synsem#>`
  - `lexinfo: <http://www.lexinfo.net/ontology/3.0/lexinfo#>`
  - `lime: <http://www.w3.org/ns/lemon/lime#>`
  - `lexicog: <http://www.w3.org/ns/lemon/lexicog#>`
  - `skos: <http://www.w3.org/2004/02/skos/core#>`
- Ogni nuovo endpoint lessicale deve accettare il parametro opzionale `author`;
  dopo l'eventuale risoluzione dell'utente autenticato, un valore nullo, vuoto o
  composto soltanto da spazi deve diventare `anonymous`.
- Scrivere in inglese `@ApiOperation` per ogni nuovo endpoint e `@ApiParam` per
  ciascun suo parametro, seguendo lo stile delle API esistenti.

## Codice e API

- La policy dei metadati RDF è globale, vale per ogni entità presente e futura
  e deve essere applicata tramite il supporto centralizzato, senza liste
  divergenti per tipo di entità. Un metadato non può mai usare una proprietà
  appartenente ai namespace `ontolex`
  (`http://www.w3.org/ns/lemon/ontolex#`), `frac`
  (`http://www.w3.org/ns/lemon/frac#`), `lime`
  (`http://www.w3.org/ns/lemon/lime#`), `vartrans`
  (`http://www.w3.org/ns/lemon/vartrans#`), `synsem`
  (`http://www.w3.org/ns/lemon/synsem#`), `skos`
  (`http://www.w3.org/2004/02/skos/core#`) o `decomp`
  (`http://www.w3.org/ns/lemon/decomp#`). Sono inoltre sempre protetti
  `dcterms:creator`, `dcterms:created`, `dcterms:modified`, `rdf:type` e
  `rdf:value`. `skos:note` è l'unica eccezione permanente al blocco del
  namespace SKOS e non deve mai essere esclusa dai metadati. La regola si
  applica a creazione, lettura e CRUD dei metadati;
  l'aggiunta di forme, sensi, etimologie o altre entità deve riusare
  `MetadataPolicy` e non ridefinire eccezioni locali.
- Mantenere la separazione `service` → `manager` → persistenza/query e usare i
  DTO sotto `service/data`.
- Documentare nuovi endpoint e parametri nello stile esistente con
  `@ApiOperation` e `@ApiParam`.
- Mantenere compatibilità delle risposte JSON e codici macchina degli errori;
  non basare i test soltanto su messaggi localizzati.
- Preferire query parametrizzate o escaping già centralizzato. Ogni modifica a
  SPARQL di scrittura deve avere un test che controlli il named graph corretto e
  l'assenza di triple nel default graph.
- Gestire risultati SPARQL vuoti senza accessi posizionali non verificati; label
  e proprietà opzionali non devono rendere invisibili risorse esistenti.
- Chiudere o rilasciare sempre connessioni, risultati RDF4J e stream nel percorso
  positivo e in quello di errore.

## Test e verifica

- Per modifiche Java eseguire almeno `mvn test` prima del commit.
- Per modifiche a servizi testuali, persistenza, cleanup o named graph aggiungere
  test unitari/repository e, quando applicabile, aggiornare gli end-to-end.
- Gli end-to-end devono usare repository GraphDB e directory filesystem dedicati,
  mai dati di sviluppo o produzione.
- Nei test RDF verificare soggetto, predicato, tipo `IRI`/`Literal`, named graph e
  assenza di effetti collaterali; non confrontare Turtle come stringa.
- Per workflow remoti eseguire sempre cleanup in `finally`.
- Se `mvn` non è disponibile nel `PATH`, usare il Maven incluso in NetBeans 12.2
  indicato in `HANDOFF.md`.
- Riportare chiaramente test eseguiti, test saltati e warning ambientali.

## Git e pubblicazione

- Creare commit focalizzati con messaggi descrittivi; non usare `git add -A` in
  un worktree misto.
- Creare i commit direttamente sul branch principale, attualmente `master`, e
  non creare branch separati per prepararli.
- Quando l'utente chiede un commit, eseguire anche il push su `origin/master`,
  salvo impedimenti di autenticazione o rete da comunicare esplicitamente.
- Non aprire una pull request per integrare modifiche preparate localmente,
  salvo richiesta esplicita dell'utente; il flusso ordinario usa direttamente
  il branch principale.
- Non riscrivere storia pubblicata senza autorizzazione. Se un rebase autorizzato
  richiede un push forzato, usare esclusivamente `--force-with-lease`.
- Prima di ogni nuovo lavoro sul branch principale, aggiornare i riferimenti
  remoti e applicare soltanto un allineamento fast-forward sicuro.

## Documentazione e handoff

- Aggiornare la documentazione quando cambiano endpoint, proprietà, mapping RDF,
  named graph, bootstrap o procedure di test.
- Aggiornare `HANDOFF.md` dopo modifiche architetturali o prima di consegnare una
  lunga attività: branch, commit, file recenti, test, problemi noti e prossimo
  passo devono riflettere lo stato reale.
- Non dichiarare completata una funzionalità senza una verifica proporzionata al
  rischio e senza distinguere test unitari da test end-to-end.

After every substantial change:

1. Update `HANDOFF.md` with:
   - the current state of the project;
   - completed functionality;
   - remaining work;
   - known problems;
   - recommended next steps.
2. Update `README.md` if installation, configuration, usage, or project structure
   changed.
3. Run the relevant tests before completing the task.
4. Do not update `HANDOFF.md` for trivial formatting-only changes.

## Changelog maintenance

After every substantial user-visible change, update the `Unreleased` section of
`CHANGELOG.md`.

Use only the relevant categories among:

- Added
- Changed
- Deprecated
- Removed
- Fixed
- Security

Describe changes from the user's perspective. Do not add trivial internal
refactoring, formatting-only changes, or unverified information.

Do not create a new release number or release date unless explicitly requested.
When preparing a release, move the relevant entries from `Unreleased` into a
versioned section using the format:

```text
## [X.Y.Z] - YYYY-MM-DD
```
