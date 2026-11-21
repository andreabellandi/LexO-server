# DSH 2027 LexInt capability and correction status

Initial survey date: 2026-11-20. Corrective update: 2026-11-21. Repository:
`andreabellandi/LexO-server`; the correction branch and exact clean tested
commit are recorded by each final run rather than hard-coded here.

Status values are `IMPLEMENTED`, `PARTIAL`, `MISSING`, and `BLOCKED`. This is a
capability survey, not an experimental result.

## Contract and terminology

| Capability | Status | Evidence / gap |
|---|---|---|
| LexInt namespace | IMPLEMENTED | Runtime and packaged TTLs use `https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#`. |
| Publication namespace | IMPLEMENTED | Runtime and packaged TTLs use `https://ilc.cnr.it/klab/ontology/lexint/publication#`. |
| Claim-to-target property | IMPLEMENTED | The maintainer's direct instruction takes precedence over the evaluation-document example: the implementation and evaluation use singular `lexint:targetAssertion`. `pub:targetAssertion` remains singular and distinct. The DSH manuscript is not modified. |
| Four contractual TTL resources | IMPLEMENTED | Schema/shapes resources are packaged and byte-identical to the maintainer copies under `Documents/Submissions/UD 2026/lexint`. SHA-256 values are recorded in `manifest.yaml`. |
| RDF 1.1 target representation | IMPLEMENTED | Runtime creates named classic reification and rejects RDF 1.2-only input. |

## LexInt services and persistence

| Capability | Status | Evidence / gap |
|---|---|---|
| Claim/target/decision CRUD and finalization | IMPLEMENTED | `/lexint/claims`, `/targets`, `/decisions` are present with immutable-history checks, ETags, server-side actors, metadata, and SHACL validation. |
| Baseline, draft, snapshot export | IMPLEMENTED | `/lexint/versions/baseline`, `/drafts`, detail, delete-reserved, and snapshot export are present. |
| Preview without writes | IMPLEMENTED | Planner is invoked through a read connection; repository tests and the dedicated GraphDB evaluation assert graph stability. |
| Atomic publish and exact per-target plan | IMPLEMENTED | Publish writes target snapshot, active graph, ledger, publication, operations, and initial verification in one RDF4J transaction. Eligibility is checked for every target/decision/version tuple. |
| Idempotency and ETag | IMPLEMENTED | Principal-scoped `Idempotency-Key`, payload fingerprint, replay, conflict, and stale `If-Match` paths exist. |
| Historical audit | IMPLEMENTED | The verifier reconstructs the historical source/destination transition and appends `PASS`, `FAIL`, or `INDETERMINATE` reports. It does not substitute a later current version. |
| Runtime metadata | IMPLEMENTED | Central metadata writer covers Agent, Claim, Target, Decision, Version, Publication, Operation, Verification, and AuditReport. |
| Named-graph and default-graph discipline | IMPLEMENTED | Records, audit, shapes, snapshots, and active graph are explicit; repository tests and the dedicated GraphDB suite assert the default graph is empty. |
| Real SHACL | IMPLEMENTED | Packaged shapes are loaded into an ephemeral RDF4J `ShaclSail`; RDF4J 3.7's unsupported `sh:xone` is checked fail-closed before revalidation. |

## Writer boundary and failure injection

| Capability | Status | Evidence / gap |
|---|---|---|
| Managed write guard in LexO-server | PARTIAL | `LexicalEntryManager`, `LexicalEntryUpdateManager`, `LexicalEntryStatusManager`, and `MetadataManager` call the shared guard for `lexica/{language}`. Lexical-concept, legacy `lexica`, CPP, attestation, annotation, text, and WordNet writers do not target the managed language graph by contract. The evaluation must enumerate and test this inventory. |
| Unconditional prevention of direct GraphDB writes | BLOCKED | The WAR cannot protect clients that bypass it. A valid deployment guarantee additionally requires GraphDB network/credential permissions. The default development Compose stack exposes an unauthenticated GraphDB port, so it is unsuitable as evidence for this part of T10. The evaluation stack must segregate the application path and the L2 administrator; otherwise T10's exclusion claim remains `BLOCKED`. |
| Single coordinated writer | PARTIAL | Publish is serialized by a JVM lock. There is no distributed lock; the evaluation stack must run exactly one LexO-server writer. |
| Pre-commit publication failpoint | IMPLEMENTED | An in-process injected hook throws after provisional active/snapshot/ledger/publication/audit writes and before commit. It has no HTTP, request or property switch. T09-GraphDB uses independent pre/post `HTTPRepository` connections and compares all affected graphs isomorphically. |

## DSH 2027 evaluation assets

| Capability | Status | Evidence / gap |
|---|---|---|
| Frozen lexical/scientific fixtures | IMPLEMENTED | Versioned V1/V2 graphs, claims, decisions, sources and independent target fixtures are checksum-locked. |
| Independent RDF oracle | IMPLEMENTED | The suite compares complete graphs and duplicate-preserving, typed full CQ rows against a frozen answer key. Runtime placeholders resolve only through service-created ID maps. |
| T1-T12 executable matrix | IMPLEMENTED | `Dsh2027EvaluationIT` executes the matrix and records each subcase without promoting blocked or skipped checks to passes. |
| CE1-CE3 comparison | IMPLEMENTED | The delivered structural shapes and historical auditor are applied to the same three well-formed discrepancy cases. |
| CQ1-CQ7 SPARQL and answer checking | IMPLEMENTED | Seven versioned queries use a single exact RDF-binding multiset comparator; four adversarial association/multiplicity tests must be rejected. |
| Machine/human reports and deterministic archive | IMPLEMENTED | The runner regenerates CSV/JSON/Markdown evidence, a content manifest and a sorted fixed-timestamp ZIP. |
| Dedicated GraphDB/Tomcat empirical run | PENDING CLEAN RERUN | The corrected final result is accepted only from a clean committed checkout; its run manifest supplies the observed status, commit and image IDs. |

## Existing tests that can be reused

- `GraphDbBootstrapTest` checks schema/shapes import, checksum behavior, and
  graph separation.
- `LexIntWorkflowTest` covers CRUD, metadata, preview, publication,
  idempotency, versioning, partial target selection, drift, historical audit,
  and rollback paths in an in-memory RDF4J repository.
- `LexIntAuthPolicyTest`, `LexIntApiContractTest`, and
  `LexIntRateLimiterTest` cover identity, publisher policy, REST registration,
  and bounded request behavior.
- `LexIntGraphGuardIntegrationTest` checks managed/non-managed behavior for
  known language-graph managers.
- `LexIntServicesIT` is an opt-in remote smoke test, not the T1-T12 evaluation
  suite and not prior empirical evidence for DSH 2027.

## Source handling

The demonstrative dataset uses the bibliography already selected in the DSH
manuscript: Contini (1959), Sabatini (1962), the CDEC consultation page for
the 1938 document, and the Italian Senate consultation page for Article 3.
The web pages are recorded as consultation pages, not asserted to be primary
critical editions. The tests verify stored identifiers only and never infer
historical or lexical truth from their availability.
