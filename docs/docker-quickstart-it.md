# LexO-server con Docker: guida essenziale

Questa guida avvia LexO-server/Tomcat e GraphDB come due container coordinati.
Non usare **Run** sulla sola immagine LexO in Docker Desktop: senza il servizio
GraphDB e le variabili Compose, LexO non può completare l'avvio.

## Requisiti

- Docker Desktop oppure Docker Engine;
- Docker Compose v2;
- una copia di questo repository.

## Primo avvio

Dalla cartella principale del repository:

```sh
cp -n .env.example .env
docker compose up -d --build
```

Il comando crea l'immagine LexO e avvia i container `graphdb` e `lexo`.
Controlla lo stato:

```sh
docker compose ps
docker compose logs -f lexo
```

Attendi che entrambi i servizi risultino `healthy`, quindi apri:

- LexO/Swagger: <http://localhost:8080/LexO-server/>;
- health check: <http://localhost:8080/LexO-server/service/health/ready>;
- GraphDB: <http://localhost:7200/>.

Se possiedi già un'immagine versionata, per esempio `lexo-server:1.2.2`, avviala
sempre tramite Compose:

```sh
LEXO_VERSION=1.2.2 docker compose up -d
```

## Stop, riavvio e rimozione dei container

```sh
# Ferma i container conservandoli
docker compose stop

# Riavvia i container esistenti
docker compose up -d

# Rimuove i container e la rete, ma conserva tutti i dati
docker compose down
```

Non usare `docker compose down --volumes`: cancellerebbe i volumi persistenti
con repository GraphDB e file LexO.

## Aggiornamento con un nuovo WAR

Non copiare il WAR dentro un container in esecuzione. Crea una nuova immagine
con un tag univoco e aggiorna LexO:

```sh
./docker/build-war-image.sh /percorso/LexO-server.war 1.2.2
./docker/update.sh 1.2.2
```

L'aggiornamento:

1. crea automaticamente un backup;
2. sostituisce soltanto il container LexO;
3. conserva GraphDB e i volumi applicativi;
4. attende che il nuovo LexO risulti `healthy`.

Per tornare alla versione precedente, se compatibile con i dati correnti:

```sh
./docker/update.sh 1.2.1
```

I dettagli su configurazione, backup, restore e pubblicazione delle immagini
sono disponibili nella [guida Docker completa](docker.md).
