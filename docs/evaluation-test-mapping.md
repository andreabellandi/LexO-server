# DSH 2027 LexInt evaluation coverage

The executable evaluation is under `evaluation/dsh2027`. It supplements the
LexInt implementation tests; it does not replace them. Expected RDF comes from
frozen fixtures and an independent exact graph/full-binding multiset oracle,
not from the publication planner, preview response, or query output.

| Paper test | Reused implementation coverage | Additional dedicated E2E evidence |
|---|---|---|
| T01 | V01, V02, P01, P02, R01, M01/M02 | Exact frozen V1/V2 graph equality, `-ratio +haraz`, active=V2, two authorized operations, initial `PASS` |
| T02 | M01, M02, P03, R01–R03 | Two source references, three RDF 1.1 targets and suspended decision remain queryable; no S1–S3 materialization |
| T03 | P03, P06 | Preview and direct publish rejected with `LEXINT_DECISION_NOT_ELIGIBLE`; graph and ledger digests unchanged |
| T04 | P03, P04 | Mixed valid/invalid plan rejected atomically; no partial etymological change |
| T05 | M05, M06 | HTTP SHACL rejection plus L0 proof that the real validator was reached; no partial resource |
| T06 | P04, P05, P06 | Existing wrong-claim target, wrong-version decision and non-final decision rejected without using 404 |
| T07 | P12 | Two independent technical candidates; explicit 1/2 selection materializes exactly one target |
| T08a | P07 | Stale `If-Match` produces 412 and no publication |
| T08b | P08 | L2 active-graph drift produces 409 and is not silently repaired |
| T09 | P11 | Separate L0 MemoryStore evidence plus test-only injected exception after provisional active/snapshot/ledger/publication/audit writes in the dedicated GraphDB repository; independent `HTTPRepository` pre/post connections and graph-isomorphic rollback proof |
| T10a | P09, P10 | Identical retry returns the same publication; changed normalized payload conflicts; one publication only |
| T10b | C01–C03 | Managed `/lexica/entry` writer blocked and frontend-only container cannot reach GraphDB |
| T11 | R02, R03, R06 | L2 V2 corruption appends `FAIL` with one unexpected triple and preserves the initial `PASS` |
| T12a | R04, R05, R06 | V1→V2 is reverified as `PASS` after a legitimate V3 without substituting V3 |
| T12b | R04, R05, R06 | Removed non-empty V1 snapshot appends `INDETERMINATE` with `LEXINT_AUDIT_INPUT_UNAVAILABLE` |

CQ01–CQ07 are named-graph-scoped SPARQL queries with a frozen, complete typed
answer-row key. A reusable comparator preserves duplicate rows and RDF term
kind, datatype, and language; adversarial row-association and multiplicity
mutations are tested separately and must be rejected.
CE1–CE3 execute the delivered structural shapes and the historical publication
auditor on the same well-formed records: the comparison is specifically with
the delivered shapes, not a claim about the theoretical expressiveness of
SHACL-SPARQL.

## Writer boundary inventory

The managed application graph is
`https://lexo.ilc.cnr.it/graphs/lexical/lexica/{language}`. Known mutating
managers for that category are `LexicalEntryManager`,
`LexicalEntryUpdateManager`, `LexicalEntryStatusManager`, and the lexical-entry
path of `MetadataManager`; each calls the shared `LexIntGraphGuard`. T10 invokes
the entry creation route and the repository suite covers all four managers in
managed and non-managed modes.

Lexical-concept managers write the fixed lexical-concept graph. Legacy Lexica,
CPP, text, attestations, annotations, and WordNet use separate graph contracts
and are not alternate writers to the managed language graph. Read helpers may
resolve a language graph without mutating it. Access that bypasses the WAR is
outside a Java guard's authority; the delivered evaluation therefore also
proves a deployment boundary in which GraphDB has no host port and is absent
from the frontend-only network. Production requires equivalent GraphDB
credentials and network ACLs.

## Levels and entry points

- L0: `Dsh2027L0EvidenceTest` and the existing LexInt repository tests.
- L1/L2: `Dsh2027EvaluationIT`, opted in only with the dedicated-stack
  properties set by `run-evaluation.sh`.
- Full command: `./evaluation/dsh2027/scripts/run-evaluation.sh --mode all`.
- Core command: the same runner with `--mode core`; excluded rows are reported
  `NOT_RUN`, never promoted to passes.
