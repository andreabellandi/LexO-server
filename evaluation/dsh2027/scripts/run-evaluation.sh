#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
EVALUATION_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
REPOSITORY_ROOT=$(cd "$EVALUATION_DIR/../.." && pwd -P)
COMPOSE_FILE="$EVALUATION_DIR/compose.yaml"
MODE=all
OUTPUT=
DRY_RUN=false
CHECK_ONLY=false

usage() {
  cat <<'EOF'
Usage: evaluation/dsh2027/scripts/run-evaluation.sh [options]

Options:
  --mode all|core       Run the complete suite or the documented core subset.
  --output PATH         Output directory below this LexO-server checkout.
  --dry-run             Validate arguments and print the execution plan only.
  --check-environment   Check tools, checksums and safety guards; do not start containers.
  --help                Show this help text.

GraphDB is created in a private Docker volume and has no published host port.
The evaluation refuses non-dedicated URLs in the Java harness.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode)
      [ "$#" -ge 2 ] || { echo "Missing value for --mode" >&2; exit 2; }
      MODE=$2
      shift 2
      ;;
    --output)
      [ "$#" -ge 2 ] || { echo "Missing value for --output" >&2; exit 2; }
      OUTPUT=$2
      shift 2
      ;;
    --dry-run) DRY_RUN=true; shift ;;
    --check-environment) CHECK_ONLY=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$MODE" in
  all|core) ;;
  *) echo "--mode must be 'all' or 'core'" >&2; exit 2 ;;
esac

RUN_ID=${DSH2027_RUN_ID:-dsh2027-$(date -u +%Y%m%dT%H%M%SZ)-$$}
if [ -z "$OUTPUT" ]; then
  OUTPUT="$EVALUATION_DIR/results/$RUN_ID"
elif [ "${OUTPUT#/}" = "$OUTPUT" ]; then
  OUTPUT="$REPOSITORY_ROOT/$OUTPUT"
fi
OUTPUT_PARENT=$(dirname "$OUTPUT")
mkdir_for_validation=$OUTPUT_PARENT
while [ ! -d "$mkdir_for_validation" ] && [ "$mkdir_for_validation" != "/" ]; do
  mkdir_for_validation=$(dirname "$mkdir_for_validation")
done
OUTPUT_PREFIX=$(cd "$mkdir_for_validation" && pwd -P)
OUTPUT_SUFFIX=${OUTPUT#"$mkdir_for_validation"}
OUTPUT_ABS="$OUTPUT_PREFIX$OUTPUT_SUFFIX"
case "$OUTPUT_ABS/" in
  "$REPOSITORY_ROOT"/*) ;;
  *) echo "Output must remain below $REPOSITORY_ROOT" >&2; exit 2 ;;
esac

PROJECT_TOKEN=$(printf '%s' "$RUN_ID" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9' '-')
PROJECT="lexo-dsh2027-${PROJECT_TOKEN}"
PROJECT=${PROJECT%-}
RELATIVE_OUTPUT=${OUTPUT_ABS#"$REPOSITORY_ROOT/"}
L0_EVIDENCE="/workspace/$RELATIVE_OUTPUT/l0-evidence.json"

compose() {
  docker compose -f "$COMPOSE_FILE" -p "$PROJECT" "$@"
}

check_environment() {
  command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; return 1; }
  docker info >/dev/null
  docker compose version >/dev/null
  command -v shasum >/dev/null 2>&1 || { echo "shasum is required" >&2; return 1; }
  command -v zip >/dev/null 2>&1 || { echo "zip is required" >&2; return 1; }
  [ -f "$REPOSITORY_ROOT/pom.xml" ] || { echo "LexO-server pom.xml not found" >&2; return 1; }
  [ -f "$EVALUATION_DIR/manifest.yaml" ] || { echo "Evaluation manifest is missing" >&2; return 1; }
  compose config --quiet
  "$SCRIPT_DIR/verify-inputs.sh"
  if rg -n --glob '!**/run-evaluation.sh' \
      'https://ilc\.cnr\.it/klab/ontology/lexint#' \
      "$EVALUATION_DIR" "$REPOSITORY_ROOT/src/main/java" \
      "$REPOSITORY_ROOT/src/test/java" >/dev/null; then
    echo "Forbidden legacy LexInt namespace detected" >&2
    return 1
  fi
  echo "Environment checks passed for dedicated project $PROJECT"
}

check_environment

if [ "$CHECK_ONLY" = true ]; then
  exit 0
fi

if [ "$DRY_RUN" = true ]; then
  cat <<EOF
Dry run only; no container, graph or result file was created.
project=$PROJECT
mode=$MODE
output=$OUTPUT_ABS
graphdb_url=http://graphdb:7200 (private backend network; no host port)
lexo_url=http://lexo:8080/LexO-server/service
planned_tests=L0, T01-T12, CQ01-CQ07, CE1-CE3 (subject to --mode)
EOF
  exit 0
fi

COMMIT_SHA=$(git -C "$REPOSITORY_ROOT" rev-parse HEAD)
GIT_BRANCH=${DSH2027_GIT_REF:-$(git -C "$REPOSITORY_ROOT" branch --show-current)}
[ -n "$GIT_BRANCH" ] || GIT_BRANCH=detached
if [ -n "$(git -C "$REPOSITORY_ROOT" status --porcelain --untracked-files=all)" ]; then
  WORKTREE_DIRTY=true
else
  WORKTREE_DIRTY=false
fi
if [ "$WORKTREE_DIRTY" = true ] && [ "${DSH2027_ALLOW_DIRTY:-false}" != true ]; then
  echo "Refusing final evaluation from a dirty Git worktree; commit the source first" >&2
  exit 2
fi

if [ -e "$OUTPUT_ABS" ] && [ "$(find "$OUTPUT_ABS" -mindepth 1 -print -quit 2>/dev/null)" ]; then
  echo "Refusing to overwrite non-empty output directory: $OUTPUT_ABS" >&2
  exit 2
fi
mkdir -p "$OUTPUT_ABS"

cleanup() {
  "$SCRIPT_DIR/reset-test-environment.sh" --project "$PROJECT" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "Starting dedicated GraphDB and LexO-server project $PROJECT"
compose up -d --build graphdb lexo

ready=false
attempt=0
while [ "$attempt" -lt 60 ]; do
  if compose exec -T lexo curl --fail --silent --show-error \
      http://127.0.0.1:8080/LexO-server/service/health/ready >/dev/null 2>&1; then
    ready=true
    break
  fi
  attempt=$((attempt + 1))
  sleep 5
done
if [ "$ready" != true ]; then
  compose logs --no-color >"$OUTPUT_ABS/stack-failure.log" 2>&1 || true
  echo "Dedicated LexO-server did not become ready" >&2
  exit 1
fi

GRAPHDB_IMAGE=${GRAPHDB_IMAGE:-ontotext/graphdb:10.8.14}
LEXO_IMAGE=${LEXO_EVALUATION_IMAGE:-lexo-dsh2027:local}
MAVEN_IMAGE=${MAVEN_IMAGE:-maven:3.9.16-eclipse-temurin-17}
GRAPHDB_IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$GRAPHDB_IMAGE")
LEXO_IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$LEXO_IMAGE")
MAVEN_IMAGE_ID=$(docker image inspect --format '{{.Id}}' "$MAVEN_IMAGE")
printf '{\n  "commit_sha": "%s",\n  "git_branch": "%s",\n  "worktree_dirty": %s,\n  "source_status_command": "git status --porcelain --untracked-files=all",\n  "generated_results_ignored": true\n}\n' \
  "$COMMIT_SHA" "$GIT_BRANCH" "$WORKTREE_DIRTY" \
  >"$OUTPUT_ABS/clean-state.json"

echo "Running L0 SHACL and transaction-failpoint evidence"
compose --profile tools run --rm --no-deps evaluator \
  -B -ntp -Dtest=Dsh2027L0EvidenceTest \
  -Dlexo.test.dsh2027.l0Evidence="$L0_EVIDENCE" test

echo "Probing the external GraphDB boundary from the frontend-only network"
if compose --profile tools run --rm --no-deps boundary-probe \
    --silent --show-error --fail --connect-timeout 3 \
    http://graphdb:7200/repositories/LexOLexica >/dev/null 2>&1; then
  echo "Boundary probe unexpectedly reached GraphDB" >&2
  exit 1
fi
printf '%s\n' '{"external_graphdb_reachable":false,"network":"frontend-only","expected":false}' \
  >"$OUTPUT_ABS/network-boundary.json"

echo "Running L1/L2 REST, RDF oracle, CQ and counterexample evaluation"
set +e
compose --profile tools run --rm --no-deps evaluator \
  -B -ntp -Dtest=Dsh2027EvaluationIT \
  -Dlexo.test.dsh2027.enabled=true \
  -Dlexo.test.dsh2027.dedicated=true \
  -Dlexo.test.dsh2027.externalGraphdbBlocked=true \
  -Dlexo.test.dsh2027.baseUrl=http://lexo:8080/LexO-server/service/ \
  -Dlexo.test.dsh2027.lexicalRepositoryUrl=http://graphdb:7200/repositories/LexOLexica \
  -Dlexo.test.dsh2027.root=/workspace/evaluation/dsh2027 \
  -Dlexo.test.dsh2027.output="/workspace/$RELATIVE_OUTPUT" \
  -Dlexo.test.dsh2027.l0Evidence="$L0_EVIDENCE" \
  -Dlexo.test.dsh2027.mode="$MODE" \
  -Dlexo.test.dsh2027.runId="$RUN_ID" \
  -Dlexo.test.dsh2027.commit="$COMMIT_SHA" \
  -Dlexo.test.dsh2027.branch="$GIT_BRANCH" \
  -Dlexo.test.dsh2027.graphdbImage="$GRAPHDB_IMAGE" \
  -Dlexo.test.dsh2027.graphdbImageId="$GRAPHDB_IMAGE_ID" \
  -Dlexo.test.dsh2027.lexoImage="$LEXO_IMAGE" \
  -Dlexo.test.dsh2027.lexoImageId="$LEXO_IMAGE_ID" \
  -Dlexo.test.dsh2027.mavenImage="$MAVEN_IMAGE" \
  -Dlexo.test.dsh2027.mavenImageId="$MAVEN_IMAGE_ID" \
  -Dlexo.test.dsh2027.worktreeDirty="$WORKTREE_DIRTY" test
EVALUATION_EXIT=$?
set -e

compose logs --no-color >"$OUTPUT_ABS/stack.log" 2>&1 || true
"$SCRIPT_DIR/collect-results.sh" --run-id "$RUN_ID" --output "$OUTPUT_ABS"

if [ "$EVALUATION_EXIT" -ne 0 ]; then
  echo "Evaluation completed with failures; evidence was retained in $OUTPUT_ABS" >&2
  exit "$EVALUATION_EXIT"
fi

echo "Evaluation completed successfully. Reports: $OUTPUT_ABS"
echo "Replication archive: $EVALUATION_DIR/results/dsh2027-lexint-evaluation-$RUN_ID.zip"
