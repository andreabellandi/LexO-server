#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
destination=${1:-"$repository_root/backups/$timestamp"}

case "$destination" in
    /*) ;;
    *) destination="$PWD/$destination" ;;
esac

docker volume inspect lexo-graphdb-home >/dev/null
docker volume inspect lexo-data >/dev/null
mkdir -p "$destination"

running_services=$(cd "$repository_root" && docker compose ps --status running --services)
graphdb_was_running=false
lexo_was_running=false
for service in $running_services; do
    [ "$service" = "graphdb" ] && graphdb_was_running=true
    [ "$service" = "lexo" ] && lexo_was_running=true
done

restart_services() {
    if [ "$graphdb_was_running" = true ]; then
        (cd "$repository_root" && docker compose up -d graphdb)
    fi
    if [ "$lexo_was_running" = true ]; then
        (cd "$repository_root" && docker compose up -d lexo)
    fi
}
trap restart_services EXIT HUP INT TERM

if [ "$lexo_was_running" = true ]; then
    (cd "$repository_root" && docker compose stop lexo)
fi
if [ "$graphdb_was_running" = true ]; then
    (cd "$repository_root" && docker compose stop graphdb)
fi

docker run --rm \
    --volume lexo-graphdb-home:/source:ro \
    --volume "$destination:/backup" \
    alpine:3.22 tar -C /source -czf /backup/graphdb-home.tar.gz .

docker run --rm \
    --volume lexo-data:/source:ro \
    --volume "$destination:/backup" \
    alpine:3.22 tar -C /source -czf /backup/lexo-data.tar.gz .

(cd "$repository_root" && docker compose config --images) \
    > "$destination/images.txt"
date -u +%Y-%m-%dT%H:%M:%SZ > "$destination/created-at.txt"

restart_services
trap - EXIT HUP INT TERM
echo "Backup completed: $destination"
