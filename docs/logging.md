# Logging operations

LexO-server uses SLF4J 2 as its application API and Log4j 2 Core as the only
runtime backend. Production events are newline-delimited JSON in
`logs/LexO-server.json`; warnings and errors are also written to Tomcat's
standard error stream. Do not deploy Logback, Log4j 1.x, another SLF4J provider,
or `commons-logging.jar` with the WAR.

## Configuration

The defaults in `src/main/resources/log4j2.xml` can be overridden with JVM
system properties:

| Property | Default | Meaning |
| --- | --- | --- |
| `lexo.log.dir` | `logs` | Active log and archive directory |
| `lexo.log.level` | `INFO` | Root threshold |
| `lexo.log.maxFileSize` | `100MB` | Size rollover threshold |
| `lexo.log.retention` | `30d` | Maximum archive age |
| `lexo.log.totalSizeCap` | `1GB` | Maximum accumulated archive size |
| `lexo.log.console.level` | `WARN` | Minimum level also written to standard error |

For Tomcat, put overrides in `CATALINA_OPTS`, for example:

```sh
CATALINA_OPTS="$CATALINA_OPTS -Dlexo.log.dir=/var/log/lexo -Dlexo.log.level=INFO"
```

The Docker image sets `lexo.log.console.level=INFO` so normal lifecycle events
are visible through `docker compose logs`, while the structured JSON files
remain available in the `lexo-logs` volume.

The Tomcat account must be able to create the configured directory. Log files,
runtime data, and IDE files are ignored by Git.

## Event contract

Each HTTP response emits one completion event with `requestId`, `method`,
`path`, `status`, `durationMs`, and, when available, `serviceVersion` and
`actor`. A valid incoming `X-Request-ID` is propagated; otherwise the server
creates one and always returns it in the response header. Background Testo/NIF
and Conversion jobs inherit the request context and add `fileId` or `bulkId`.

Messages derived from external input are reduced to one line and capped at
4096 characters. Authorization headers, uploaded content, full SPARQL queries,
and other secrets or payloads must never be logged. Use `INFO` for normal
lifecycle and business milestones, `WARN` for rejected or recoverable
conditions, and `ERROR` with the exception for unexpected failures.

## Migration boundary

Metadata, Testo/NIF, Attestations, Lexica, and Conversion use the SLF4J API.
Services scheduled for replacement may continue to compile through the
temporary `log4j-1.2-api` adapter, which routes their events into the same
Log4j 2 backend. Do not migrate those legacy classes merely to modernize their
logging: remove the adapter as soon as the last legacy service is deleted or
rewritten. Tests prevent retained paths from reintroducing legacy logging APIs.

## Operations

- Ship or collect the JSON file from the configured directory; rotation is
  daily and also occurs at the size threshold.
- Search by `requestId` to correlate one HTTP request with its asynchronous
  work. Search by `fileId` or `bulkId` for job history.
- Alert on sustained HTTP 5xx events and job failure events, not on isolated
  client validation failures.
- Treat logs as operational data: restrict access and include their directory
  in the host backup/retention policy only when required.
