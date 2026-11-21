# Known limitations

- LexO-server serializes publication inside one JVM. The evaluation runs one
  server instance and does not establish multi-instance serializability or a
  distributed lock guarantee.
- The workflow uses RDF 1.1 classic reification. RDF 1.2 triple terms are
  intentionally rejected and are outside the evaluation.
- Managed mode guards every currently known LexO-server writer to the managed
  per-language graph, but a WAR cannot by itself stop a separately credentialed
  client from writing directly to GraphDB. The delivered deployment guarantee
  therefore combines application guards with the evaluated private-network
  boundary. Operators must reproduce equivalent GraphDB ACL/network controls.
- The test deployment uses `identityAssurance=DECLARED` and permits publisher
  actions without Keycloak only in the ephemeral profile. It is not evidence
  of strong authentication or role provisioning.
- The delivered SHACL-only comparison concerns the four contractual structural
  shapes. It does not claim that richer SHACL-SPARQL rules are incapable of
  expressing publication-integrity constraints.
- The source examples are schematic scholarly test data. Availability of the
  CDEC and Senate consultation pages and the stored bibliographic identifiers
  does not certify an etymology, a distinct lexical sense, or a critical
  edition.
- Performance values, if emitted, describe only the recorded local run and are
  not a benchmark of production capacity or UI usability.
