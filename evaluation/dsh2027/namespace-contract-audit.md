# Namespace and predicate contract audit

Status: **RESOLVED for the implementation and evaluation; manuscript edit
awaits author approval.** No ontology, shape, or manuscript byte was changed by
the corrective work.

The authoritative runtime choice is the singular LexInt claim property
`https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#targetAssertion`.
This follows both the maintainer's explicit instruction of 20 November 2026
(`usa lexint:targetAssertion`) and the frozen profile 0.2.0 plus its SHACL
shape. The earlier evaluation specification and current manuscript use the
plural spelling in some prose; those occurrences describe a contract that no
longer matches the approved ontology.

| Layer | Role | Concrete IRI/token | Source | SHA-256/version |
|---|---|---|---|---|
| LexInt ontology | claim → one or more candidate resources | `lexint:targetAssertion` | `bootstrap/schema/lexint-profile.ttl` | `9a4fb27351d12f268047969491becf584117c83b4e7a6edde859bb77d4bef73d`, ontology 0.2.0 |
| LexInt shapes | minimum one candidate per claim | `sh:path lexint:targetAssertion` | `bootstrap/shapes/lexint-shapes.ttl` | `3c1e6a5e01918a01fbb8813602b87742e9bca2be951bd6eb6e1ee5df401bafbf` |
| Runtime vocabulary/CRUD | claim serialization and lookup | full LexInt IRI ending `#targetAssertion` | `LexIntVocabulary.TARGET_ASSERTION_PROPERTY`, `LexIntManager` | committed source revision recorded by each run |
| Evaluation fixture/query | claim candidate recovery | full LexInt IRI / `lexint:targetAssertion` | `fixtures/claim-specs.yaml`, `queries/cq05.rq` | checksummed by `manifest.yaml` and run manifest |
| Publication ontology | operation → exactly one candidate | `pub:targetAssertion` | `bootstrap/schema/lexint-publication.ttl` | `0b52d24e179d5709c51b31eff5132dc2b09098a804bcaeccc3651e41b8740170`, ontology 0.1.0 |
| Publication shapes/runtime/query | operation constraint and audit record | Publication IRI ending `#targetAssertion` | publication shape, `PUB_TARGET_ASSERTION`, `queries/cq04.rq` | shape SHA-256 `c797bc223a6e8320fd613792f31bb14e1270653ae225a4428239ea63a35be2be` |

`pub:targetAssertion` is not an alias of the LexInt property. A claim may have
several links to several `lexint:TargetAssertion` individuals; each publication
operation links to exactly one of those individuals. Each individual still
reifies exactly one candidate RDF triple in the v1 RDF 1.1 representation.

The preflight script and `Dsh2027NamespaceContractTest` fail closed if the
ontology, shapes, Java vocabulary, fixtures, or CQ queries diverge or introduce
the plural predicate as an alias.

## Proposed manuscript correction (not applied)

For author approval, replace `lexint:targetsAssertion` with
`lexint:targetAssertion` in every DSH2027 section, diagram, table, Turtle/SPARQL
example, caption, and appendix that describes the relation from an
`lexint:InterpretiveClaim` to candidate `lexint:TargetAssertion` resources.
Do not change `pub:targetAssertion`. Where prose discusses multiplicity, state
that the singular property can occur multiple times on one claim; do not imply
that one target individual represents multiple RDF triples.
