#!/bin/sh
set -eu

usage() {
    echo "Usage: LEXO_CONFIRM_RESTORE=YES $0 BACKUP_DIRECTORY" >&2
    exit 2
}

[ "$#" -eq 1 ] || usage
[ "${LEXO_CONFIRM_RESTORE:-}" = "YES" ] || {
    echo "Restore replaces the current LexO volumes." >&2
    echo "Re-run with LEXO_CONFIRM_RESTORE=YES after checking the backup." >&2
    exit 2
}

backup_directory=$1
case "$backup_directory" in
    /*) ;;
    *) backup_directory="$PWD/$backup_directory" ;;
esac

graphdb_archive="$backup_directory/graphdb-home.tar.gz"
lexo_archive="$backup_directory/lexo-data.tar.gz"
[ -f "$graphdb_archive" ] || usage
[ -f "$lexo_archive" ] || usage
tar -tzf "$graphdb_archive" >/dev/null
tar -tzf "$lexo_archive" >/dev/null

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)

(cd "$repository_root" && docker compose down)

if docker volume inspect lexo-graphdb-home >/dev/null 2>&1; then
    docker volume rm lexo-graphdb-home
fi
if docker volume inspect lexo-data >/dev/null 2>&1; then
    docker volume rm lexo-data
fi
docker volume create lexo-graphdb-home >/dev/null
docker volume create lexo-data >/dev/null

docker run --rm \
    --volume lexo-graphdb-home:/target \
    --volume "$backup_directory:/backup:ro" \
    alpine:3.22 tar -C /target -xzf /backup/graphdb-home.tar.gz

docker run --rm \
    --volume lexo-data:/target \
    --volume "$backup_directory:/backup:ro" \
    alpine:3.22 tar -C /target -xzf /backup/lexo-data.tar.gz

(cd "$repository_root" && docker compose up -d)
echo "Restore completed from: $backup_directory"
