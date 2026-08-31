#!/bin/sh
set -eu

usage() {
    echo "Usage: $0 /path/to/LexO-server.war VERSION" >&2
    exit 2
}

[ "$#" -eq 2 ] || usage

war_file=$1
version=$2
case "$version" in
    ""|*[!A-Za-z0-9._-]*)
        echo "Invalid image version: $version" >&2
        exit 2
        ;;
esac

[ -f "$war_file" ] || {
    echo "WAR file not found: $war_file" >&2
    exit 2
}

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repository_root=$(CDPATH= cd -- "$script_dir/.." && pwd)
configured_image=""
if [ -f "$repository_root/.env" ]; then
    configured_image=$(awk -F= '
        /^LEXO_IMAGE=/ { value = substr($0, index($0, "=") + 1) }
        END { print value }
    ' "$repository_root/.env")
fi
image_repository=${LEXO_IMAGE:-${configured_image:-lexo-server}}
image_tag="${image_repository}:${version}"
build_context=$(mktemp -d "${TMPDIR:-/tmp}/lexo-war-image.XXXXXX")

cleanup() {
    rm -rf -- "$build_context"
}
trap cleanup EXIT HUP INT TERM

cp "$war_file" "$build_context/LexO-server.war"
cp "$script_dir/Dockerfile.war" "$build_context/Dockerfile"

docker build --pull \
    --label "org.opencontainers.image.title=LexO-server" \
    --label "org.opencontainers.image.version=$version" \
    --tag "$image_tag" \
    "$build_context"

echo "Built $image_tag"
echo "Install it with: ./docker/update.sh $version"
