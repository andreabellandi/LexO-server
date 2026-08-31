# LexO-server with Docker: essential guide

This guide runs LexO-server/Tomcat and GraphDB as two coordinated containers.
Do not click **Run** on the LexO image alone in Docker Desktop: without the
GraphDB service and the Compose environment, LexO cannot complete its startup.

## Requirements

- Docker Desktop or Docker Engine;
- Docker Compose v2;
- a copy of this repository.

## First start

From the repository root:

```sh
cp -n .env.example .env
docker compose up -d --build
```

This command builds the LexO image and starts the `graphdb` and `lexo`
containers. Check their status:

```sh
docker compose ps
docker compose logs -f lexo
```

Wait until both services are `healthy`, then open:

- LexO/Swagger: <http://localhost:8080/LexO-server/>;
- health check: <http://localhost:8080/LexO-server/service/health/ready>;
- GraphDB: <http://localhost:7200/>.

If you already have a versioned image such as `lexo-server:1.2.2`, always run
it through Compose:

```sh
LEXO_VERSION=1.2.2 docker compose up -d
```

## Stop, restart, and remove containers

```sh
# Stop the containers without removing them
docker compose stop

# Restart the existing containers
docker compose up -d

# Remove the containers and network while preserving all data
docker compose down
```

Do not use `docker compose down --volumes`: it would delete the persistent
volumes containing the GraphDB repositories and LexO files.

## Update LexO with a new WAR

Do not copy a WAR into a running container. Build a new image with a unique tag
and update LexO:

```sh
./docker/build-war-image.sh /path/to/LexO-server.war 1.2.2
./docker/update.sh 1.2.2
```

The update process:

1. creates a backup automatically;
2. replaces only the LexO container;
3. preserves GraphDB and the application volumes;
4. waits until the new LexO container is `healthy`.

To return to the previous version, provided it is compatible with the current
data:

```sh
./docker/update.sh 1.2.1
```

See the [complete Docker guide](docker.md) for configuration, backup, restore,
and image publishing details.
