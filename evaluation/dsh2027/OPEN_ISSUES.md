# Open issues

- The proposed manuscript spelling correction in
  `namespace-contract-audit.md` requires author approval. The manuscript has
  not been edited.
- Publication serialization is a single-JVM lock, not a distributed lock. The
  evaluation proves one GraphDB-backed writer instance only.
- The application guard cannot stop independently credentialed direct GraphDB
  writes. Production deployment must reproduce the tested network boundary and
  enforce GraphDB ACLs.
- RDF 1.2 triple terms remain outside the Java 8/RDF4J 3.7 runtime profile.
- The demonstrative identity assurance is `DECLARED`; no claim of authenticated
  scholarly attribution is made.

There is no known blocker to reproducing the corrected isolated experiment.
