# Docker deployment and upgrades

The Docker distribution runs LexO-server/Tomcat and GraphDB as separate
services managed by one Compose project. This preserves independent lifecycle,
health checks, memory management, and persistent storage while keeping the user
workflow to one command.

## Requirements

- Docker Engine or Docker Desktop with Docker Compose v2;
- enough Docker memory for GraphDB and the LexO JVM; 4 GiB is a practical
  minimum for a small local installation and larger datasets require sizing
  based on their RDF statement count;
- permission to use the selected GraphDB edition and image.

The default is the official multi-architecture `ontotext/graphdb:10.8.14`
image, selected because the repository templates are currently verified for
GraphDB 10. Do not replace it with `latest`. GraphDB 11 must be qualified
separately and requires the license procedure defined by Ontotext.

## First start

From the repository root:

```sh
cp .env.example .env
docker compose up -d --build
docker compose ps
```

The first build compiles and tests the WAR, downloads the runtime images, and
may take several minutes. LexO waits for GraphDB, creates `LexOLexica` and
`LexOTexts`, and applies the idempotent schema and index bootstrap.

Open:

- `http://localhost:8080/LexO-server/` for Swagger and the REST API;
- `http://localhost:7200/` for the local GraphDB Workbench;
- `http://localhost:8080/LexO-server/service/health/ready` for readiness.

Both published ports bind to `127.0.0.1` by default. GraphDB must not be
published on an external interface in a shared deployment.

Stop or restart without deleting data:

```sh
docker compose stop
docker compose up -d
```

Do not use `docker compose down --volumes` unless the explicit goal is to
delete all LexO data.

## Configuration

Packaged values in `lexo-server.properties` are defaults. Effective
configuration uses this precedence, from lowest to highest:

1. packaged classpath defaults;
2. the optional file selected by `LEXO_CONFIG_FILE` or
   `-Dlexo.config.file=/path/file.properties`;
3. `LEXO_*` environment variables;
4. JVM system properties with the original property name;
5. runtime-only application overrides.

Environment names are derived by removing an initial `lexo.`, separating
camel-case words, replacing punctuation with `_`, uppercasing, and adding
`LEXO_`. Examples:

| Property | Environment variable |
| --- | --- |
| `GraphDb.url` | `LEXO_GRAPH_DB_URL` |
| `TextGraphDb.url` | `LEXO_TEXT_GRAPH_DB_URL` |
| `lexo.text.storage.dir` | `LEXO_TEXT_STORAGE_DIR` |
| `Bootstrap.startup.maxAttempts` | `LEXO_BOOTSTRAP_STARTUP_MAX_ATTEMPTS` |

Compose sets GraphDB to the internal service name `http://graphdb:7200`, text
storage to `/var/lib/lexo/texts`, and legacy conversion storage to
`/var/lib/lexo/legacy`. JVM memory and host ports can be changed in `.env`.

## Persistent volumes

| Volume | Content |
| --- | --- |
| `lexo-graphdb-home` | GraphDB configuration, `LexOLexica`, and `LexOTexts` |
| `lexo-data` | Original texts, CoNLL-U, corpora, and conversion artifacts |
| `lexo-logs` | Structured LexO log files and archives |

Bulk job polling state remains in memory and does not survive an application
container restart; committed documents and repository data do survive.

## Backup and restore

LexO text state spans GraphDB and the filesystem, so both application volumes
must be captured while writes are stopped. The backup script records them in
one timestamped directory and restarts only services that were running:

```sh
./docker/backup.sh
./docker/backup.sh /absolute/backup/directory
```

Restore is intentionally guarded because it replaces the current GraphDB and
LexO data volumes:

```sh
LEXO_CONFIRM_RESTORE=YES ./docker/restore.sh backups/20260827T180000Z
```

Keep backups outside Docker volumes and test restore before relying on them.

## Installing a new WAR

Never overwrite the WAR inside a running container. Build an immutable image
with a new version tag, then replace only the LexO container:

```sh
./docker/build-war-image.sh /path/to/new/LexO-server.war 1.2.2
./docker/update.sh 1.2.2
```

`build-war-image.sh` creates `lexo-server:1.2.2` without changing the source
tree. `update.sh` creates a coordinated backup by default, installs the tagged
image, waits for `/health/ready`, and writes the successful `LEXO_VERSION` to
`.env`. Set `LEXO_UPDATE_BACKUP=0` only when a separate verified backup already
exists.

For images published in a registry, set the repository once in `.env`:

```dotenv
LEXO_IMAGE=ghcr.io/organization/lexo-server
```

Then the same update command pulls the requested tag when it is not already
local. To roll back, run `docker/update.sh` with the preceding version tag. The
persistent volumes are not replaced by an application rollback; if a future
release introduces a data migration, follow that release's restore or migration
notes before rolling back.

To build a tagged image directly from the current source instead of a supplied
WAR:

```sh
LEXO_VERSION=1.2.2 docker compose build --pull lexo
./docker/update.sh 1.2.2
```

## Shared or production deployment

The supplied Compose file is a local starter environment. Before exposing LexO
to other machines, add an HTTPS reverse proxy, configure and test Keycloak,
keep GraphDB on the private Docker network, store secrets outside Compose, set
resource limits, schedule backups, and execute the end-to-end suite against a
dedicated deployment.
