# LexInt Management, Publication and Audit

## Ambito e compatibilità

LexInt è implementato nello stesso WAR Jersey/JAX-RS di LexO-server, con
Java 8, API `javax.*`, RDF4J 3.7.6 e il solo repository `LexOLexica`. Non usa
microservizi, code asincrone o repository aggiuntivi. Le route indicate sotto
sono relative a `/service`.

Il namespace LexInt è
`https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#`; quello Publication
è `https://ilc.cnr.it/klab/ontology/lexint/publication#`. Su richiesta del
maintainer, la proprietà del profilo è stata uniformata al singolare
`lexint:targetAssertion`; resta distinta dalla proprietà, già singolare,
`pub:targetAssertion`. Il namespace LexInt storico non è usato.

Il runtime v1 crea soltanto reificazioni RDF 1.1 classiche nominate. Un input
RDF 1.2 `rdf:reifies` è rifiutato con `LEXINT_RDF12_UNSUPPORTED`; le ontologie
e le shape conservano comunque la semantica dichiarata per entrambi i modelli.

## Named graph

Tutti i graph appartengono a `LexOLexica`:

- schema OWL: `https://lexo.ilc.cnr.it/graphs/lexical/schema`;
- shape SHACL: `https://lexo.ilc.cnr.it/graphs/lexical/lexint/shapes`;
- claim, target e decisioni: `https://lexo.ilc.cnr.it/graphs/lexical/lexint/records`;
- versioni, agenti, pubblicazioni e verifiche:
  `https://lexo.ilc.cnr.it/graphs/lexical/lexint/audit`;
- snapshot immutabili:
  `https://lexo.ilc.cnr.it/graphs/lexical/lexint/snapshots/{language}/{versionCode}`;
- stato lessicale corrente: graph esistente
  `https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}`.

I servizi non scrivono nel default graph, nel graph lessicale legacy, in
`lexicalConcept`, nei graph CPP, WordNet, testi, attestazioni o annotazioni.
La stessa validazione centralizzata viene eseguita al bootstrap e a ogni
apertura del repository: IRI non assoluti, basi senza `/` finale, traversal e
collisioni con graph esatti o con una delle famiglie applicative vengono
rifiutati prima di qualunque operazione.

## Configurazione e rollout

I default sono conservativi:

```properties
LexInt.enabled=false
LexInt.managedPublication.enabled=false
LexInt.publication.allowUnauthenticatedPublish=false
LexInt.publication.requiredRole=lexint-publisher
```

`LexInt.enabled=true` abilita le API. Con managed publication disabilitata i
writer lessicali precedenti restano invariati. Quando entrambi i primi due flag
sono `true` e una lingua possiede una baseline `CURRENT`, il write guard nei
manager di entry, stato e metadati rifiuta le scritture dirette in
`lexica/{language}` con `409 LEXINT_MANAGED_WRITE_REQUIRED`. Le altre lingue e
gli altri graph non vengono intercettati. Anche le credenziali GraphDB devono
impedire writer esterni, perché il guard non può proteggere accessi SPARQL che
non passano dal WAR.

I limiti sono configurabili con `LexInt.write.maxBytes`,
`LexInt.write.maxOperations`, `LexInt.list.maxLimit` e
`LexInt.audit.maxDifferenceItems`. Tutte le route applicano inoltre un limite
per identità con `LexInt.rateLimit.requestsPerMinute`; la cache LRU dei bucket
è bounded da `LexInt.rateLimit.maxIdentities`. Il superamento restituisce 429
`LEXINT_RATE_LIMITED` con `retryAfterSeconds`; payload o export oltre soglia
restituiscono 413 `LEXINT_LIMIT_EXCEEDED`.

## Autenticazione e metadata

`Authorization: Bearer ...` segue `Service.checkKey`: è opzionale quando
Keycloak non è configurato e obbligatorio quando `keycloack.url` è valorizzata.
Un header assente, non Bearer o con token vuoto viene restituito come 401 prima
di creare il manager Keycloak o contattare il provider.
L'username autenticato prevale sempre su `author` query/body. In free mode un
autore vuoto diventa `anonymous` e la risposta riporta
`identityAssurance=DECLARED`; non costituisce autenticazione. Con Keycloak,
preview, baseline, riserva, publish e verify richiedono anche il ruolo
configurato. In free mode tali operazioni sono ammesse solo impostando
esplicitamente `LexInt.publication.allowUnauthenticatedPublish=true`.

Ogni individuo runtime creato dal modulo — agent, claim, target, decisione,
versione, publication, operation, verification e report — riceve esattamente
un `dcterms:creator` letterale e un `dcterms:created`/`dcterms:modified`
`xsd:dateTime`. Patch, finalizzazione e transizioni di versione sostituiscono
solo `modified`. Le risorse storiche non vengono riscritte; ogni verifica crea
nuove IRI.

## API

### Claim, target e decisioni

| Metodo | Path | Uso |
|---|---|---|
| POST, GET | `/lexint/claims` | creazione atomica e lista filtrata |
| GET, PATCH, DELETE | `/lexint/claims/{id}` | dettaglio e CRUD della bozza con ETag |
| POST | `/lexint/claims/{id}/targets` | aggiunta di una reificazione RDF 1.1 |
| GET, PATCH, DELETE | `/lexint/targets/{id}` | dettaglio e CRUD del target di bozza |
| POST, GET | `/lexint/decisions` | creazione e lista decisioni versionate |
| GET, PATCH, DELETE | `/lexint/decisions/{id}` | CRUD prima della finalizzazione |
| POST | `/lexint/decisions/{id}/finalize` | aggiunta di `prov:endedAtTime`, senza pubblicare |

PATCH di claim, target e decisione richiede `If-Match`. Claim e target diventano
immutabili quando una decisione finalizzata o un'operazione li rende storici.
Una decisione finalizzata è immutabile. Gli esiti ammessi sono `adopted`,
`rejected`, `suspended` e `replaced`; nessuno di essi materializza RDF da solo.

### Versioni

| Metodo | Path | Uso |
|---|---|---|
| POST | `/lexint/versions/baseline` | snapshot iniziale idempotente della lingua |
| POST | `/lexint/versions/drafts` | riserva il codice monotono successivo |
| GET | `/lexint/versions` | lista paginata, filtro `language` |
| GET, DELETE | `/lexint/versions/{id}` | dettaglio con audit storico, o eliminazione di una riserva inutilizzata |
| GET | `/lexint/versions/{id}/snapshot` | export bounded `text/turtle` |

Gli stati sono `RESERVED`, `CURRENT` e `PUBLISHED`. Il client non sceglie né il
codice versione né il graph snapshot.

Per le versioni create dal runtime corrente, il ledger privato conserva anche
`lexint-management:snapshotTripleCount`. Il valore non è un dato lessicale né
un'estensione delle ontologie contrattuali: serve a distinguere uno snapshot
storico non vuoto successivamente rimosso da un grafo intenzionalmente vuoto.
In verifica, la scomparsa di un input registrato produce un report append-only
`INDETERMINATE` con `LEXINT_AUDIT_INPUT_UNAVAILABLE`; una mutazione leggibile
continua invece a produrre `FAIL` con il delta puntuale.

### Pubblicazione e audit

| Metodo | Path | Uso |
|---|---|---|
| POST | `/lexint/publications/preview` | piano e delta senza alcuna scrittura |
| POST | `/lexint/publications` | commit atomico del piano esplicito |
| GET | `/lexint/publications` | lista con filtri lingua/versione/username/periodo |
| GET | `/lexint/publications/{id}` | dettaglio, operazioni e audit storico |
| GET | `/lexint/publications/{id}/audit` | tutti i report persistiti |
| POST | `/lexint/publications/{id}/verify` | nuova verifica snapshot o active corrente |

Ogni operazione seleziona un singolo `targetAssertionIri` e una singola
`decisionIri`. `INSERT` richiede decisione finalizzata `adopted`; `DELETE`
richiede `rejected` o `replaced`. Target, claim, versione e presenza/assenza
della tripla vengono riletti in transazione. Le claim parzialmente selezionate
sono segnalate dalla preview ma non inducono operazioni implicite.

Publish richiede `If-Match` uguale al `baseEtag` restituito dalla preview e un
`Idempotency-Key` sicuro di 1–128 caratteri. La chiave è scoped per l'identità
stabile dell'agente (hash di issuer+subject autenticati, oppure autore
dichiarato): stesso attore, chiave e payload restituiscono la publication
esistente con HTTP 200; un payload diverso restituisce 409. Snapshot, graph
corrente, version ledger, operazioni e primo audit PASS vengono committati
nella stessa transazione, o tutti sottoposti a rollback.

L'audit ricostruisce `G_expected` dalla snapshot sorgente e dalle operazioni
storiche. La verifica normale legge la snapshot destinazione anche se esiste
una V3 successiva; `checkActive=true` è consentito solo per la versione
`CURRENT`. Ogni report conserva conteggi completi e un JSON bounded delle
triple missing/unexpected e degli `errorCodes` in `dcterms:description`; GET lo
ricostruisce senza sovrascrivere report precedenti. La ricostruzione verifica
nuovamente metadati, tipi, target, claim, decisione finalizzata, versione ed
esito di ogni operazione storica. Input mancanti o link danneggiati producono
un nuovo report persistito `INDETERMINATE` con codice diagnostico; componenti
blank-node isomorfe sono accettate, mentre una componente non mappabile con
certezza non viene mai riportata come un falso PASS/FAIL.

## Esempio essenziale

Creazione claim:

```bash
curl -X POST http://localhost:8080/LexO-server/service/lexint/claims \
  -H 'Content-Type: application/json' \
  -d '{"description":"Interpretazione controllata","author":"editor","targets":[{"subjectIri":"https://example.org/entry/casa","predicateIri":"https://example.org/vocabulary/interpretation","object":{"kind":"LITERAL","value":"abitazione","language":"it"}}]}'
```

Preview e publish usano lo stesso body:

```json
{
  "language": "it",
  "sourceVersionIri": "https://lexo.ilc.cnr.it/id/lexint/version/UUID-V1",
  "targetVersionIri": "https://lexo.ilc.cnr.it/id/lexint/version/UUID-V2",
  "operations": [{
    "action": "INSERT",
    "targetAssertionIri": "https://lexo.ilc.cnr.it/id/lexint/target/UUID-T",
    "decisionIri": "https://lexo.ilc.cnr.it/id/lexint/decision/UUID-D"
  }],
  "author": "editor"
}
```

```bash
curl -X POST http://localhost:8080/LexO-server/service/lexint/publications \
  -H 'Content-Type: application/json' \
  -H 'If-Match: "ETAG-DA-PREVIEW"' \
  -H 'Idempotency-Key: release-it-v2' \
  --data @publication.json
```

## SHACL e limiti operativi

Le quattro risorse allegate vengono usate nei rispettivi context. RDF4J
ShaclSail esegue una revalidazione completa sull'unione controllata di schema,
records e audit, più una shape runtime per i tre metadata. RDF4J 3.7, ultimo
ramo compatibile con Java 8 adottato dal progetto, non implementa
`XoneConstraintComponent`: senza modificare il TTL, il validator applica prima
lo stesso `sh:xone` in modalità fail-closed a ogni `TargetAssertion`, poi usa
ShaclSail per `sh:or`, `sh:class`, `sh:in`, datatype, cardinalità e node kind.

La serializzazione delle publish sullo stesso processo usa un lock JVM. Il
rollout iniziale richiede quindi una sola istanza writer LexO-server; non è un
lock distribuito tra più WAR. Non è inclusa canonicalizzazione RDF generale:
le differenze in componenti blank-node non isomorfe producono
`INDETERMINATE`. JSON in ingresso e snapshot Turtle in uscita sono interrotti
durante la lettura/scrittura al superamento di `LexInt.write.maxBytes`, senza
prima accumulare un payload oltre il limite. Il rate limiter applicativo è per
JVM; per l'attivazione in produzione sono quindi necessari E2E su GraphDB
dedicato, un limite coordinato al reverse proxy per deployment multi-istanza e
permessi GraphDB che impediscano bypass esterni.

L'E2E remoto è esclusivamente opt-in e rifiuta di partire senza il flag che
attesta un deployment e un repository `LexOLexica` dedicati. Il deployment
deve avere LexInt e managed publication attivi; in free mode deve inoltre
abilitare esplicitamente la pubblicazione non autenticata:

```bash
mvn verify -Dit.test=LexIntServicesIT \
  -Dlexo.test.lexint.dedicated=true \
  -Dlexo.test.lexint.baseUrl=http://localhost:8080/LexO-lexint-test/service \
  -Dlexo.test.lexint.lexicalRepositoryUrl=http://localhost:7200/repositories/LexOLexIntTest
```

Con Keycloak aggiungere
`-Dlexo.test.lexint.authorization='Bearer TOKEN_PUBLISHER'`. La suite verifica
lifecycle REST, preview read-only, ETag, idempotenza, metadata di ogni
individuo, named graph/default graph, audit PASS/FAIL e cleanup transazionale.

## Valutazione DSH 2027

La suite riproducibile completa è descritta in
`evaluation/dsh2027/README.md`; la matrice fra T01–T12 e i controlli tecnici è
in `docs/evaluation-test-mapping.md`. Il runner avvia GraphDB 10.8.14 e una sola
istanza del WAR in volumi nuovi, non pubblica la porta GraphDB, esegue un probe
di rete fail-closed, produce evidenze RDF/HTTP per caso e rimuove container,
reti e volumi dedicati al termine. L'oracle usa una connessione RDF4J separata
e expected graph congelati. Il canale L2 non è esposto come endpoint HTTP.

```bash
./evaluation/dsh2027/scripts/run-evaluation.sh --check-environment
./evaluation/dsh2027/scripts/run-evaluation.sh \
  --mode all \
  --output evaluation/dsh2027/results/<run_id>
```

Il profilo dimostrativo usa `identityAssurance=DECLARED` e abilita il publish
senza Keycloak soltanto nello stack effimero. Non è una configurazione di
autenticazione forte. La proprietà claim/target adottata dal maintainer e da
tutta la suite è la singolare `lexint:targetAssertion`; la proprietà distinta
delle operazioni resta `pub:targetAssertion`.
