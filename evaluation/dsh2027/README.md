# DSH 2027 LexInt evaluation

This directory contains the frozen demonstrative dataset, independent RDF
oracle, T01–T12 protocols, CQ01–CQ07 SPARQL queries, CE1–CE3 comparison, and a
reproducible Docker runner for the LexInt evaluation. T09 has separate L0 and
GraphDB evidence; CQ results use exact, duplicate-preserving typed RDF binding
rows plus adversarial comparator tests. It does not modify the DSH2027
manuscript and does not claim that the illustrative etymological or semantic
hypotheses are historically true.

The maintainer-selected contract uses singular `lexint:targetAssertion`.
Publication operations continue to use the distinct singular
`pub:targetAssertion`. The four OWL/SHACL resources are checksum-locked and are
not rewritten by the runner. The conflict with older plural manuscript prose,
the authoritative sources, and a manuscript correction proposal are recorded
in `namespace-contract-audit.md`.

## Safety and isolation

The Compose topology creates new project-scoped volumes and a single
LexO-server instance. GraphDB is attached only to an internal backend network
and has no host port. The REST server is reachable on loopback port `18080` by
default. A frontend-only probe must fail to resolve/reach `graphdb`; the Java
L2 oracle is the intentionally segregated administrative channel. Cleanup
accepts only Compose project names beginning with `lexo-dsh2027-`.

Never point the Java harness at a persistent or production repository. It
fails closed unless both URLs use the dedicated container host names and the
explicit `dedicated=true` opt-in is present.

## Prerequisites

- Docker Engine with Compose v2;
- approximately 8 GB free disk space for images, Maven cache and volumes;
- `shasum`, `zip`, `git`, and `rg` on the host;
- network access on the first run to pull GraphDB, Maven and Tomcat images and
  Maven dependencies.

## Commands

From the LexO-server repository root:

```sh
./evaluation/dsh2027/scripts/run-evaluation.sh --help
./evaluation/dsh2027/scripts/run-evaluation.sh --check-environment
./evaluation/dsh2027/scripts/run-evaluation.sh --dry-run --mode all
./evaluation/dsh2027/scripts/run-evaluation.sh \
  --mode all \
  --output evaluation/dsh2027/results/<run_id>
```

`--mode core` executes T01–T04, T11 and CQ01–CQ06. It deliberately marks the
remaining rows `NOT_RUN`; it is not the complete experiment. `--mode all`
executes T01–T12, both T08/T10/T12 subcases, CQ01–CQ07 and CE1–CE3. Any failed
or critically blocked case makes the command exit non-zero while retaining the
observed artifacts.

A complete corrective run must originate from a clean commit. Execution fails
closed when tracked or untracked source files are present. Developers may set
`DSH2027_ALLOW_DIRTY=true` only for explicitly non-final diagnostic runs; such
runs retain `worktree_dirty=true` and are not acceptable as final evidence.

To remove a named interrupted environment, use only the project printed by the
runner:

```sh
./evaluation/dsh2027/scripts/reset-test-environment.sh \
  --project lexo-dsh2027-<run-id>
```

## Outputs

Each run writes `summary.json`, `tests.csv`, `comparison.csv`,
`cq-results.csv`, `paper-table3.csv`, `report.md`,
`paper-results-draft.md`, `baseline-vs-corrected.md`, a SHA-256 `manifest.json`,
rendered SPARQL, complete expected/observed/missing/unexpected binding rows,
comparator metatests, and per-case HTTP/RDF evidence. T09 records backend and
connection classes, graph inventories, before/after TriG, hook evidence and the
ordinary reset success path. Requests are redacted; no Bearer token or
password is stored. The declared demonstration username is explicitly reported
with `identityAssurance=DECLARED`.

On a complete run, `collect-results.sh` creates
`results/dsh2027-lexint-evaluation-<run_id>.zip`. Files in the archive have a
fixed timestamp and sorted order; the result manifest provides content
integrity. The archive contains the fixtures, oracle, queries, scripts,
results, relevant source files and the current software patch.
For a clean run it also contains the tested commit patch and clean-state proof.
The top-level `SHA256SUMS` covers every packaged file, including raw stack logs
and source evidence added after the in-run JSON manifest was written.

## Interpreting results

Test status (`PASSED`, `FAILED`, `BLOCKED`, `NOT_RUN`), structural SHACL status
(`CONFORMS`, `VIOLATES`, `UNAVAILABLE`), and publication audit outcome
(`PASS`, `FAIL`, `INDETERMINATE`) are separate dimensions. In particular, T11
passes only when a deliberately corrupted snapshot yields an appended audit
`FAIL`; T12 passes only when missing historical evidence yields
`INDETERMINATE` rather than a false `PASS`.
