#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd -P)
EVALUATION_DIR=$(cd "$SCRIPT_DIR/.." && pwd -P)
REPOSITORY_ROOT=$(cd "$EVALUATION_DIR/../.." && pwd -P)

check() {
  expected=$1
  file=$2
  observed=$(shasum -a 256 "$REPOSITORY_ROOT/$file" | awk '{print $1}')
  if [ "$observed" != "$expected" ]; then
    echo "Checksum mismatch: $file" >&2
    echo "expected=$expected observed=$observed" >&2
    exit 1
  fi
}

require_text() {
  text=$1
  file=$2
  if ! rg --fixed-strings --quiet "$text" "$REPOSITORY_ROOT/$file"; then
    echo "Contract text missing from $file: $text" >&2
    exit 1
  fi
}

reject_text() {
  text=$1
  shift
  if rg --fixed-strings --quiet "$text" "$@"; then
    echo "Forbidden contract text detected: $text" >&2
    exit 1
  fi
}

verify_manifest_source_hashes() {
  while read -r expected file; do
    [ -n "$expected" ] || continue
    check "$expected" "evaluation/dsh2027/$file"
  done < <(awk '
    $0 == "source_hashes:" { inside = 1; next }
    inside && $0 !~ /^ / { exit }
    inside && $0 ~ /^  [^:]+: [0-9a-f]+$/ {
      line = substr($0, 3)
      separator = index(line, ": ")
      print substr(line, separator + 2), substr(line, 1, separator - 1)
    }
  ' "$EVALUATION_DIR/manifest.yaml")
}

check 9a4fb27351d12f268047969491becf584117c83b4e7a6edde859bb77d4bef73d src/main/resources/bootstrap/schema/lexint-profile.ttl
check 0b52d24e179d5709c51b31eff5132dc2b09098a804bcaeccc3651e41b8740170 src/main/resources/bootstrap/schema/lexint-publication.ttl
check 3c1e6a5e01918a01fbb8813602b87742e9bca2be951bd6eb6e1ee5df401bafbf src/main/resources/bootstrap/shapes/lexint-shapes.ttl
check c797bc223a6e8320fd613792f31bb14e1270653ae225a4428239ea63a35be2be src/main/resources/bootstrap/shapes/lexint-publication-shapes.ttl
verify_manifest_source_hashes

require_text 'lexint:targetAssertion a owl:ObjectProperty' src/main/resources/bootstrap/schema/lexint-profile.ttl
require_text 'sh:path lexint:targetAssertion' src/main/resources/bootstrap/shapes/lexint-shapes.ttl
require_text 'TARGET_ASSERTION_PROPERTY = lexint("targetAssertion")' src/main/java/it/cnr/ilc/lexo/manager/lexint/LexIntVocabulary.java
require_text 'lexint:targetAssertion ?target' evaluation/dsh2027/queries/cq05.rq
require_text 'claim_target_property: "lexint:targetAssertion"' evaluation/dsh2027/manifest.yaml
require_text 'pub:targetAssertion a owl:ObjectProperty' src/main/resources/bootstrap/schema/lexint-publication.ttl
require_text 'sh:path pub:targetAssertion' src/main/resources/bootstrap/shapes/lexint-publication-shapes.ttl
require_text 'PUB_TARGET_ASSERTION = publication("targetAssertion")' src/main/java/it/cnr/ilc/lexo/manager/lexint/LexIntVocabulary.java
require_text 'pub:targetAssertion ?target' evaluation/dsh2027/queries/cq04.rq
reject_text 'lexint:targetsAssertion' \
  "$REPOSITORY_ROOT/src/main/resources/bootstrap/schema/lexint-profile.ttl" \
  "$REPOSITORY_ROOT/src/main/resources/bootstrap/shapes/lexint-shapes.ttl" \
  "$REPOSITORY_ROOT/src/main/java/it/cnr/ilc/lexo/manager/lexint/LexIntVocabulary.java" \
  "$REPOSITORY_ROOT/evaluation/dsh2027/fixtures/claim-specs.yaml" \
  "$REPOSITORY_ROOT/evaluation/dsh2027/queries/cq05.rq"

echo "Frozen input checksums and namespace contract verified"
