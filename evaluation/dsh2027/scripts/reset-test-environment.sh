#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
COMPOSE_FILE=$(cd "$SCRIPT_DIR/.." && pwd -P)/compose.yaml
PROJECT=

while [ "$#" -gt 0 ]; do
  case "$1" in
    --project)
      [ "$#" -ge 2 ] || { echo "Missing value for --project" >&2; exit 2; }
      PROJECT=$2
      shift 2
      ;;
    --help|-h)
      echo "Usage: $0 --project lexo-dsh2027-<run-id>"
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$PROJECT" in
  lexo-dsh2027-*) ;;
  *) echo "Refusing cleanup: project must start with lexo-dsh2027-" >&2; exit 2 ;;
esac

docker compose -f "$COMPOSE_FILE" -p "$PROJECT" down \
  --volumes --remove-orphans --timeout 120

# Compose one-off profile containers may leave a project-labelled cache volume
# behind on some Docker Desktop releases. Remove only volumes whose label and
# generated name both match the already validated evaluation project.
docker volume ls --quiet \
  --filter "label=com.docker.compose.project=$PROJECT" |
while IFS= read -r volume; do
  [ -n "$volume" ] || continue
  case "$volume" in
    "$PROJECT"_*) docker volume rm "$volume" ;;
    *)
      echo "Refusing cleanup of unexpected labelled volume: $volume" >&2
      exit 2
      ;;
  esac
done
