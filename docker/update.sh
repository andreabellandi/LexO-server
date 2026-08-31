#!/bin/sh
set -eu

usage() {
    echo "Usage: $0 VERSION" >&2
    exit 2
}

[ "$#" -eq 1 ] || usage
version=$1
case "$version" in
    ""|*[!A-Za-z0-9._-]*) usage ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
environment_file="$repository_root/.env"
configured_image=""
if [ -f "$environment_file" ]; then
    configured_image=$(awk -F= '
        /^LEXO_IMAGE=/ { value = substr($0, index($0, "=") + 1) }
        END { print value }
    ' "$environment_file")
fi
image_repository=${LEXO_IMAGE:-${configured_image:-lexo-server}}
image_tag="${image_repository}:${version}"

if [ "${LEXO_UPDATE_BACKUP:-1}" = "1" ]; then
    "$script_dir/backup.sh"
fi

if ! docker image inspect "$image_tag" >/dev/null 2>&1; then
    docker pull "$image_tag"
fi

(cd "$repository_root" && LEXO_IMAGE="$image_repository" \
    LEXO_VERSION="$version" docker compose up -d --no-deps lexo)

container_id=$(cd "$repository_root" && LEXO_IMAGE="$image_repository" \
    LEXO_VERSION="$version" docker compose ps -q lexo)
[ -n "$container_id" ] || {
    echo "LexO container was not created" >&2
    exit 1
}

attempt=0
while [ "$attempt" -lt 180 ]; do
    health=$(docker inspect --format \
        '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
        "$container_id")
    case "$health" in
        healthy) break ;;
        unhealthy|exited|dead)
            echo "LexO update failed with container status: $health" >&2
            exit 1
            ;;
    esac
    attempt=$((attempt + 1))
    sleep 2
done

[ "$attempt" -lt 180 ] || {
    echo "Timed out waiting for LexO readiness" >&2
    exit 1
}

if [ ! -f "$environment_file" ]; then
    cp "$repository_root/.env.example" "$environment_file"
fi
temporary_environment=$(mktemp "${TMPDIR:-/tmp}/lexo-env.XXXXXX")
awk -v version="$version" '
    BEGIN { updated = 0 }
    /^LEXO_VERSION=/ { print "LEXO_VERSION=" version; updated = 1; next }
    { print }
    END { if (!updated) print "LEXO_VERSION=" version }
' "$environment_file" > "$temporary_environment"
mv "$temporary_environment" "$environment_file"

echo "LexO-server is healthy on $image_tag"
