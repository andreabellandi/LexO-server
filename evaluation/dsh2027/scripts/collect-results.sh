#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
EVALUATION_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
REPOSITORY_ROOT=$(cd "$EVALUATION_DIR/../.." && pwd -P)
RUN_ID=
OUTPUT=

while [ "$#" -gt 0 ]; do
  case "$1" in
    --run-id) RUN_ID=$2; shift 2 ;;
    --output) OUTPUT=$2; shift 2 ;;
    --help|-h)
      echo "Usage: $0 --run-id ID --output PATH"
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$RUN_ID" ] || { echo "--run-id is required" >&2; exit 2; }
[ -d "$OUTPUT" ] || { echo "Result directory does not exist: $OUTPUT" >&2; exit 2; }
case "$RUN_ID" in
  *[!A-Za-z0-9._-]*) echo "Unsafe run id" >&2; exit 2 ;;
esac

for required in summary.json tests.csv comparison.csv cq-results.csv \
    paper-table3.csv report.md paper-results-draft.md manifest.json \
    baseline-vs-corrected.md clean-state.json; do
  [ -f "$OUTPUT/$required" ] || {
    echo "Cannot package incomplete results: missing $required" >&2
    exit 1
  }
done

ARCHIVE="$EVALUATION_DIR/results/dsh2027-lexint-evaluation-$RUN_ID.zip"
mkdir -p "$EVALUATION_DIR/results"
STAGING=$(mktemp -d "${TMPDIR:-/tmp}/dsh2027-package.XXXXXX")
cleanup() { rm -rf "$STAGING"; }
trap cleanup EXIT INT TERM
PACKAGE="$STAGING/dsh2027-lexint-evaluation-$RUN_ID"
mkdir -p "$PACKAGE/evaluation" "$PACKAGE/results/$RUN_ID" \
  "$PACKAGE/source" "$PACKAGE/docs"

for item in README.md known-limitations.md implementation-gap.md manifest.yaml \
    baseline-reference.json namespace-contract-audit.md OPEN_ISSUES.md \
    compose.yaml fixtures oracle \
    cases queries scripts; do
  cp -R "$EVALUATION_DIR/$item" "$PACKAGE/evaluation/"
done
cp -R "$OUTPUT/". "$PACKAGE/results/$RUN_ID/"
cp "$REPOSITORY_ROOT/src/main/java/it/cnr/ilc/lexo/manager/lexint/PublicationFaultInjector.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/main/java/it/cnr/ilc/lexo/manager/lexint/LexIntVocabulary.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/main/java/it/cnr/ilc/lexo/manager/LexIntPublicationManager.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/test/java/it/cnr/ilc/lexo/bootstrap/Dsh2027L0EvidenceTest.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/test/java/it/cnr/ilc/lexo/bootstrap/LexIntWorkflowTest.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/test/java/it/cnr/ilc/lexo/service/Dsh2027EvaluationIT.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/test/java/it/cnr/ilc/lexo/service/Dsh2027BindingComparator.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/test/java/it/cnr/ilc/lexo/service/Dsh2027BindingComparatorTest.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/src/test/java/it/cnr/ilc/lexo/service/Dsh2027NamespaceContractTest.java" "$PACKAGE/source/"
cp "$REPOSITORY_ROOT/docs/evaluation-test-mapping.md" "$PACKAGE/docs/"
cp "$REPOSITORY_ROOT/docs/lexint-services.md" "$PACKAGE/docs/"
cp "$REPOSITORY_ROOT/CHANGELOG.md" "$PACKAGE/docs/"
cp "$REPOSITORY_ROOT/HANDOFF.md" "$PACKAGE/docs/"
git -C "$REPOSITORY_ROOT" diff --binary >"$PACKAGE/source/software.patch"
git -C "$REPOSITORY_ROOT" rev-parse HEAD >"$PACKAGE/source/base-commit.txt"
git -C "$REPOSITORY_ROOT" show --binary --format=fuller HEAD \
  >"$PACKAGE/source/tested-commit.patch"
git -C "$REPOSITORY_ROOT" status --porcelain --untracked-files=all \
  >"$PACKAGE/source/source-status.txt"

(
  cd "$PACKAGE"
  find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort |
    while IFS= read -r file; do
      shasum -a 256 "$file"
    done >SHA256SUMS
)
find "$PACKAGE" -exec touch -t 200001010000 {} +
rm -f "$ARCHIVE"
(
  cd "$STAGING"
  find "$(basename "$PACKAGE")" -type f -print | LC_ALL=C sort | zip -X -q "$ARCHIVE" -@
)
echo "$ARCHIVE"
