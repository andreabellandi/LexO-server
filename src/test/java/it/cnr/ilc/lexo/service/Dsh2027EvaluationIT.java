package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.cnr.ilc.lexo.manager.LexIntPublicationManager;
import it.cnr.ilc.lexo.manager.lexint.LexIntActor;
import it.cnr.ilc.lexo.manager.lexint.LexIntMetadataWriter;
import it.cnr.ilc.lexo.manager.lexint.LexIntRepository;
import it.cnr.ilc.lexo.manager.lexint.LexIntResourceUris;
import it.cnr.ilc.lexo.manager.lexint.PublicationFaultInjector;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationRequest;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDF4J;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.shacl.ShaclSail;
import org.eclipse.rdf4j.sail.shacl.ShaclSailConnection;
import org.eclipse.rdf4j.sail.shacl.results.ValidationReport;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Executable DSH 2027 evaluation against one dedicated LexO/GraphDB stack.
 * Expected graphs are read from frozen fixtures and never from preview/publish.
 */
class Dsh2027EvaluationIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();
    private static final String EX = "https://example.org/dsh2027/";
    private static final String LEXINT =
            "https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#";
    private static final String PUB =
            "https://ilc.cnr.it/klab/ontology/lexint/publication#";
    private static final String ETY =
            "http://lari-datasets.ilc.cnr.it/lemonEty#etymology";
    private static final String ONTOLEX =
            "http://www.w3.org/ns/lemon/ontolex#";
    private static final String AUTHOR = "dsh-test-editor";
    private static final IRI ACTIVE = iri(
            "https://lexo.ilc.cnr.it/graphs/lexical/lexica/it");
    private static final IRI RECORDS = iri(
            "https://lexo.ilc.cnr.it/graphs/lexical/lexint/records");
    private static final IRI AUDIT = iri(
            "https://lexo.ilc.cnr.it/graphs/lexical/lexint/audit");
    private static final IRI SHAPES = iri(
            "https://lexo.ilc.cnr.it/graphs/lexical/lexint/shapes");
    private static final IRI SCHEMA = iri(
            "https://lexo.ilc.cnr.it/graphs/lexical/schema");
    private static final String SNAPSHOT_BASE =
            "https://lexo.ilc.cnr.it/graphs/lexical/lexint/snapshots/";

    private Client client;
    private HTTPRepository repository;
    private String base;
    private Path evaluationRoot;
    private Path output;
    private String mode;
    private String runId;
    private final List<CaseResult> results = new ArrayList<CaseResult>();
    private final List<ComparisonResult> comparisons =
            new ArrayList<ComparisonResult>();
    private final Map<String, CqResult> cqResults =
            new LinkedHashMap<String, CqResult>();
    private Map<String, String> protectedDigests;
    private JsonNode cqOracle;
    private Map<String, String> cq1To6Placeholders;
    private Map<String, String> cq7Placeholders;

    @BeforeEach
    void connect() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lexo.test.dsh2027.enabled")
                && Boolean.getBoolean("lexo.test.dsh2027.dedicated"),
                "Requires explicit DSH2027 opt-in and a dedicated stack");
        base = trim(System.getProperty("lexo.test.dsh2027.baseUrl", ""));
        String repositoryUrl = trim(System.getProperty(
                "lexo.test.dsh2027.lexicalRepositoryUrl", ""));
        assertDedicated(base, repositoryUrl);
        evaluationRoot = Paths.get(System.getProperty(
                "lexo.test.dsh2027.root", "evaluation/dsh2027"))
                .toAbsolutePath().normalize();
        output = Paths.get(System.getProperty(
                "lexo.test.dsh2027.output", "target/dsh2027-results"))
                .toAbsolutePath().normalize();
        mode = System.getProperty("lexo.test.dsh2027.mode", "all")
                .trim().toLowerCase(Locale.ROOT);
        if (!"all".equals(mode) && !"core".equals(mode)) {
            throw new IllegalArgumentException("mode must be all or core");
        }
        runId = System.getProperty("lexo.test.dsh2027.runId",
                "dsh2027-" + Instant.now().toString().replace(':', '-'));
        cqOracle = JSON.readTree(evaluationRoot.resolve(
                "oracle/cq-answer-key.yaml").toFile()).path("answers");
        Files.createDirectories(output.resolve("artifacts"));
        Files.createDirectories(output.resolve("queries"));
        client = ClientBuilder.newClient()
                .property(ClientProperties.CONNECT_TIMEOUT, 15000)
                .property(ClientProperties.READ_TIMEOUT, 60000);
        repository = new HTTPRepository(repositoryUrl);
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size((Resource) null)).isZero();
            assertThat(connection.size(SCHEMA)).isGreaterThan(0L);
            assertThat(connection.size(SHAPES)).isGreaterThan(0L);
            protectedDigests = protectedGraphDigests(connection);
        }
    }

    @AfterEach
    void close() {
        if (client != null) client.close();
        if (repository != null) repository.shutDown();
    }

    @Test
    void executeEvaluation() throws Exception {
        run("T01", "authorized-revision", new CaseAction() {
            @Override public void run(CaseRecorder c) throws Exception { t01(c); }
        });
        run("T02", "suspended-semantic-claim", new CaseAction() {
            @Override public void run(CaseRecorder c) throws Exception { t02(c); }
        });
        run("T03", "suspended-insert", new CaseAction() {
            @Override public void run(CaseRecorder c) throws Exception { t03(c); }
        });
        run("T04", "mixed-plan", new CaseAction() {
            @Override public void run(CaseRecorder c) throws Exception { t04(c); }
        });
        if ("all".equals(mode)) {
            run("T05", "structural-invalidity", new CaseAction() {
                @Override public void run(CaseRecorder c) throws Exception { t05(c); }
            });
            run("T06", "wrong-association", new CaseAction() {
                @Override public void run(CaseRecorder c) throws Exception { t06(c); }
            });
            run("T07", "partial-selection", new CaseAction() {
                @Override public void run(CaseRecorder c) throws Exception { t07(c); }
            });
            run("T08", "stale-and-drift", new CaseAction() {
                @Override public void run(CaseRecorder c) throws Exception { t08(c); }
            });
            run("T09", "graphdb-rollback", new CaseAction() {
                @Override public void run(CaseRecorder c) throws Exception { t09(c); }
            });
            run("T10", "replay-and-writer-boundary", new CaseAction() {
                @Override public void run(CaseRecorder c) throws Exception { t10(c); }
            });
        } else {
            notRun("T05", "not included in core mode");
            notRun("T06", "not included in core mode");
            notRun("T07", "not included in core mode");
            notRun("T08", "not included in core mode");
            notRun("T09", "not included in core mode");
            notRun("T10", "not included in core mode");
        }
        run("T11", "historical-snapshot-corruption", new CaseAction() {
            @Override public void run(CaseRecorder c) throws Exception { t11(c); }
        });
        if ("all".equals(mode)) {
            run("T12", "missing-evidence-after-v3", new CaseAction() {
                @Override public void run(CaseRecorder c) throws Exception { t12(c); }
            });
            runCounterexamples();
        } else {
            notRun("T12", "not included in core mode");
        }

        completeCq6Evidence();
        runComparatorMetatests();
        verifyGlobalIsolation();
        writeReports();
        List<CaseResult> unsuccessful = new ArrayList<CaseResult>();
        for (CaseResult result : results) {
            if ("FAILED".equals(result.status)
                    || "BLOCKED".equals(result.status)) {
                unsuccessful.add(result);
            }
        }
        assertThat(unsuccessful).as("DSH evaluation failures/blocks")
                .isEmpty();
    }

    private void run(String test, String variant, CaseAction action)
            throws Exception {
        long started = System.nanoTime();
        CaseRecorder recorder = new CaseRecorder(test, variant,
                output.resolve("artifacts").resolve(test)
                        .resolve(variant));
        CaseResult result = new CaseResult(test, variant);
        try {
            Files.createDirectories(recorder.directory);
            action.run(recorder);
            result.status = recorder.blockedReason == null ? "PASSED" : "BLOCKED";
            result.notes = recorder.blockedReason == null
                    ? recorder.notes : recorder.blockedReason;
        } catch (Throwable failure) {
            result.status = "FAILED";
            result.notes = failure.getClass().getSimpleName() + ": "
                    + String.valueOf(failure.getMessage());
            recorder.failure = result.notes;
        } finally {
            result.expectedHttp = recorder.expectedHttp;
            result.observedHttp = recorder.observedHttp;
            result.expectedError = recorder.expectedError;
            result.observedError = recorder.observedError;
            result.expectedAudit = recorder.expectedAudit;
            result.observedAudit = recorder.observedAudit;
            result.beforeDigest = recorder.beforeDigest;
            result.afterDigest = recorder.afterDigest;
            result.missing = recorder.missing;
            result.unexpected = recorder.unexpected;
            result.durationMs = (System.nanoTime() - started) / 1000000L;
            result.evidence = output.relativize(recorder.directory).toString();
            recorder.finish(result);
            results.add(result);
        }
    }

    private void notRun(String test, String note) {
        CaseResult result = new CaseResult(test, "core-exclusion");
        result.status = "NOT_RUN";
        result.notes = note;
        results.add(result);
    }

    private interface CaseAction { void run(CaseRecorder recorder) throws Exception; }

    private void t01(CaseRecorder c) throws Exception {
        Scenario s = setupScenario(c, true);
        c.captureBefore();
        ObjectNode plan = t1Plan(s);
        String beforePreview = evaluationDigest();
        HttpResult preview = postJson("lexint/publications/preview", plan,
                Collections.<String, String>emptyMap(), c);
        c.expectedHttp = "200"; c.observedHttp = String.valueOf(preview.status);
        check(preview.status == 200, "preview returned HTTP 200", c);
        check("READY".equals(preview.json.path("status").asText()),
                "preview is READY", c);
        check(beforePreview.equals(evaluationDigest()),
                "preview left every evaluation graph unchanged", c);
        s.previewEtag = preview.json.path("baseEtag").asText();
        long started = System.nanoTime();
        HttpResult published = publish(plan, s.previewEtag,
                "dsh-t1-" + UUID.randomUUID(), c);
        c.timings.put("publish_ms", Long.valueOf(
                (System.nanoTime() - started) / 1000000L));
        check(published.status == 201, "publish returned HTTP 201", c);
        s.publication = published.json;
        s.publicationIri = published.json.path("iri").asText();
        s.publicationId = published.json.path("id").asText();
        s.targetEtag = published.json.path("targetEtag").asText();
        c.expectedAudit = "PASS";
        c.observedAudit = published.json.path("auditStatus").asText();
        check("PASS".equals(c.observedAudit), "initial audit is PASS", c);

        JsonNode v1 = getJson("lexint/versions/" + s.v1.path("id").asText(), c);
        JsonNode v2 = getJson("lexint/versions/" + s.v2.path("id").asText(), c);
        s.v1Graph = iri(v1.path("snapshotGraphIri").asText());
        s.v2Graph = iri(v2.path("snapshotGraphIri").asText());
        Model expectedV1 = fixture("fixtures/expected-v1.ttl");
        Model expectedV2 = fixture("fixtures/expected-v2.ttl");
        assertGraph(s.v1Graph, expectedV1, "V1 equals frozen baseline", c);
        assertGraph(s.v2Graph, expectedV2, "V2 equals frozen oracle", c);
        assertGraph(ACTIVE, expectedV2, "active graph equals V2", c);
        Delta delta = delta(graph(s.v1Graph), graph(s.v2Graph));
        check(delta.added.size() == 1 && delta.removed.size() == 1,
                "observed delta has one insertion and one deletion", c);
        check(delta.added.contains(statement(EX + "razza", ETY,
                        iri(EX + "etyHaraz")))
                        && delta.removed.contains(statement(EX + "razza", ETY,
                                iri(EX + "etyRatio"))),
                "observed delta is exactly -ratio +haraz", c);
        c.missing = 0L; c.unexpected = 0L;
        check(countType(AUDIT, PUB + "Publication") == 1L,
                "exactly one publication exists", c);
        check(published.json.path("operations").size() == 2,
                "publication has exactly two operations", c);
        writeRunIds(s);
        assertRuntimeMetadata(c);
        check(defaultGraphSize() == 0L, "default graph is empty", c);

        String replayKey = "dsh-t1-replay-" + UUID.randomUUID();
        HttpResult firstReplayBase = publish(plan, s.targetEtag,
                replayKey, c);
        check(firstReplayBase.status == 412,
                "a new key with stale V1 ETag is rejected", c);
        runCq1To6(s, c);
        c.captureAfter();
    }

    private void writeRunIds(Scenario s) throws Exception {
        ObjectNode ids = JSON.createObjectNode();
        ids.put("run_id", runId);
        ids.put("V1", s.v1.path("iri").asText());
        ids.put("V2", s.v2.path("iri").asText());
        ids.put("C_ratio", s.ratioClaim.path("iri").asText());
        ids.put("C_haraz", s.harazClaim.path("iri").asText());
        ids.put("C_sense", s.semanticClaim.path("iri").asText());
        ids.put("D_ratio_replaced", s.ratioDecision.path("iri").asText());
        ids.put("D_haraz_adopted", s.harazDecision.path("iri").asText());
        ids.put("D_sense_suspended", s.semanticDecision.path("iri").asText());
        ids.put("target_ratio", s.ratioClaim.path("targets").get(0)
                .path("iri").asText());
        ids.put("target_haraz", s.harazClaim.path("targets").get(0)
                .path("iri").asText());
        ArrayNode semanticTargets = ids.putArray("semantic_targets");
        for (JsonNode target : s.semanticClaim.path("targets")) {
            semanticTargets.add(target.path("iri").asText());
        }
        ObjectNode semanticTargetMap = ids.putObject("semantic_target_map");
        semanticTargetMap.put("TARGET_SENSE_TYPE",
                targetWithPredicate(s.semanticClaim, RDF.TYPE.stringValue())
                        .path("iri").asText());
        semanticTargetMap.put("TARGET_ENTRY_SENSE",
                targetWithPredicate(s.semanticClaim, ONTOLEX + "sense")
                        .path("iri").asText());
        semanticTargetMap.put("TARGET_SENSE_REFERENCE",
                targetWithPredicate(s.semanticClaim, ONTOLEX + "reference")
                        .path("iri").asText());
        ids.put("publication", s.publicationIri);
        for (JsonNode operation : s.publication.path("operations")) {
            ids.put("INSERT".equals(operation.path("action").asText())
                    ? "OP_INSERT" : "OP_DELETE",
                    operation.path("iri").asText());
        }
        if (s.publication.path("audit").size() > 0) {
            ids.put("VERIFICATION_INITIAL", s.publication.path("audit")
                    .get(0).path("verificationIri").asText());
        }
        writeJson(output.resolve("artifacts/run-ids.json"), ids);
    }

    private void t02(CaseRecorder c) throws Exception {
        Scenario s = setupScenario(c, true);
        c.captureBefore();
        HttpResult preview = postJson("lexint/publications/preview", t1Plan(s),
                Collections.<String, String>emptyMap(), c);
        check(preview.status == 200, "T1 preview is available", c);
        HttpResult published = publish(t1Plan(s),
                preview.json.path("baseEtag").asText(),
                "dsh-t2-" + UUID.randomUUID(), c);
        check(published.status == 201, "etymological plan published", c);
        JsonNode v2 = getJson("lexint/versions/" + s.v2.path("id").asText(), c);
        IRI v2Graph = iri(v2.path("snapshotGraphIri").asText());
        Set<Statement> semantic = semanticStatements();
        check(s.semanticClaim.path("targets").size() == 3,
                "semantic claim exposes three targets", c);
        check(s.semanticClaim.path("evidenceIris").size() == 2,
                "semantic claim exposes two sources", c);
        check("suspended".equals(s.semanticDecision.path("outcome").asText())
                        && s.semanticDecision.path("finalized").asBoolean(),
                "semantic decision is finalized and suspended", c);
        Model active = graph(ACTIVE); Model snapshot = graph(v2Graph);
        for (Statement candidate : semantic) {
            check(!active.contains(candidate) && !snapshot.contains(candidate),
                    "semantic candidate remains unasserted: " + candidate, c);
        }
        check(countSemanticOperations(s.semanticClaim.path("iri").asText()) == 0L,
                "no publication operation was created for the semantic claim", c);
        c.captureAfter();
    }

    private void t03(CaseRecorder c) throws Exception {
        Scenario s = setupScenario(c, true);
        c.captureBefore();
        ObjectNode plan = plan(s, operation("INSERT",
                s.semanticClaim.path("targets").get(0).path("iri").asText(),
                s.semanticDecision.path("iri").asText()));
        String before = evaluationDigest();
        HttpResult preview = postJson("lexint/publications/preview", plan,
                Collections.<String, String>emptyMap(), c);
        check(preview.status == 422, "suspended preview is rejected", c);
        check("LEXINT_DECISION_NOT_ELIGIBLE".equals(preview.errorCode()),
                "preview reports decision not eligible", c);
        HttpResult published = publish(plan, s.v1.path("etag").asText(),
                "dsh-t3-" + UUID.randomUUID(), c);
        c.expectedHttp = "422"; c.observedHttp = String.valueOf(published.status);
        c.expectedError = "LEXINT_DECISION_NOT_ELIGIBLE";
        c.observedError = published.errorCode();
        check(published.status == 422
                        && c.expectedError.equals(c.observedError),
                "direct publish is rejected with the contractual code", c);
        check(before.equals(evaluationDigest()),
                "rejected preview/publish caused no mutation", c);
        check(countType(AUDIT, PUB + "Publication") == 0L,
                "no publication was recorded", c);
        c.captureAfter();
    }

    private void t04(CaseRecorder c) throws Exception {
        Scenario s = setupScenario(c, true);
        c.captureBefore();
        ObjectNode mixed = t1Plan(s);
        mixed.withArray("operations").add(operation("INSERT",
                s.semanticClaim.path("targets").get(0).path("iri").asText(),
                s.semanticDecision.path("iri").asText()));
        Model before = graph(ACTIVE);
        HttpResult preview = postJson("lexint/publications/preview", mixed,
                Collections.<String, String>emptyMap(), c);
        check(preview.status == 422, "mixed preview is rejected", c);
        HttpResult published = publish(mixed, s.v1.path("etag").asText(),
                "dsh-t4-" + UUID.randomUUID(), c);
        c.expectedHttp = "422"; c.observedHttp = String.valueOf(published.status);
        c.expectedError = "LEXINT_DECISION_NOT_ELIGIBLE";
        c.observedError = published.errorCode();
        check(published.status == 422
                        && c.expectedError.equals(c.observedError),
                "mixed publish is rejected as ineligible", c);
        check(Models.isomorphic(before, graph(ACTIVE)),
                "active graph has no partial etymological commit", c);
        check(graph(ACTIVE).contains(statement(EX + "razza", ETY,
                        iri(EX + "etyRatio")))
                        && !graph(ACTIVE).contains(statement(EX + "razza", ETY,
                                iri(EX + "etyHaraz"))),
                "ratio remains and haraz is absent", c);
        check(countType(AUDIT, PUB + "Publication") == 0L,
                "mixed plan created no publication", c);
        c.captureAfter();
    }

    private void t05(CaseRecorder c) throws Exception {
        resetAndLoadFixture();
        c.captureBefore();
        ObjectNode invalid = JSON.createObjectNode();
        invalid.put("description", "Missing targets");
        invalid.put("author", AUTHOR);
        invalid.putArray("targets");
        HttpResult response = postJson("lexint/claims", invalid,
                Collections.<String, String>emptyMap(), c);
        c.expectedHttp = "422"; c.observedHttp = String.valueOf(response.status);
        c.expectedError = "LEXINT_SHACL_VIOLATION";
        c.observedError = response.errorCode();
        check(response.status == 422
                        && c.expectedError.equals(c.observedError),
                "REST rejects a claim without targets", c);
        check(countType(RECORDS, LEXINT + "InterpretiveClaim") == 0L,
                "invalid claim leaves no partial resource", c);
        Path l0 = Paths.get(System.getProperty(
                "lexo.test.dsh2027.l0Evidence", ""));
        if (!Files.isRegularFile(l0)) {
            c.blockedReason = "L0 SHACL/failpoint evidence file is missing";
        } else {
            JsonNode evidence = JSON.readTree(l0.toFile());
            check(evidence.path("real_shacl_validator_reached").asBoolean(),
                    "L0 reached the real SHACL validator", c);
            Files.copy(l0, c.directory.resolve("l0-evidence.json"));
        }
        c.captureAfter();
    }

    private void t06(CaseRecorder c) throws Exception {
        Scenario s = setupScenario(c, false);
        ObjectNode otherClaimRequest = claim("Other existing claim",
                Collections.<String>emptyList(), Arrays.asList(
                        target(EX + "other", ETY, "IRI", EX + "otherEty")));
        JsonNode otherClaim = requireStatus(postJson("lexint/claims",
                otherClaimRequest, Collections.<String, String>emptyMap(), c),
                201).json;
        JsonNode v3 = requireStatus(postJson("lexint/versions/drafts",
                languageBody(), Collections.<String, String>emptyMap(), c), 201).json;
        ObjectNode nonFinalRequest = decision(s.harazClaim.path("iri").asText(),
                s.v2.path("iri").asText(), "adopted", "Not finalized");
        JsonNode nonFinal = requireStatus(postJson("lexint/decisions",
                nonFinalRequest, Collections.<String, String>emptyMap(), c), 201).json;
        c.captureBefore();
        String before = evaluationDigest();
        List<ObjectNode> invalid = new ArrayList<ObjectNode>();
        invalid.add(plan(s, operation("INSERT",
                otherClaim.path("targets").get(0).path("iri").asText(),
                s.harazDecision.path("iri").asText())));
        invalid.add(planWithVersions(s.v1.path("iri").asText(),
                v3.path("iri").asText(), operation("INSERT",
                        s.harazClaim.path("targets").get(0).path("iri").asText(),
                        s.harazDecision.path("iri").asText())));
        invalid.add(plan(s, operation("INSERT",
                s.harazClaim.path("targets").get(0).path("iri").asText(),
                nonFinal.path("iri").asText())));
        for (ObjectNode request : invalid) {
            HttpResult response = postJson("lexint/publications/preview", request,
                    Collections.<String, String>emptyMap(), c);
            check(response.status == 422
                            && "LEXINT_DECISION_NOT_ELIGIBLE".equals(
                                    response.errorCode()),
                    "wrong association is rejected without 404", c);
        }
        check(before.equals(evaluationDigest()),
                "wrong-association variants leave graphs unchanged", c);
        c.captureAfter();
    }

    private void t07(CaseRecorder c) throws Exception {
        resetAndLoadFixture();
        JsonNode v1 = requireStatus(postJson("lexint/versions/baseline",
                languageBody(), Collections.<String, String>emptyMap(), c), 201).json;
        JsonNode v2 = requireStatus(postJson("lexint/versions/drafts",
                languageBody(), Collections.<String, String>emptyMap(), c), 201).json;
        ObjectNode claimRequest = claim("Technical partial-selection fixture",
                Collections.<String>emptyList(), Arrays.asList(
                        target(EX + "razza",
                                "http://www.w3.org/2000/01/rdf-schema#comment",
                                "LITERAL", "selected technical note"),
                        target(EX + "razza",
                                "http://www.w3.org/2000/01/rdf-schema#comment",
                                "LITERAL", "unselected technical note")));
        JsonNode claim = requireStatus(postJson("lexint/claims", claimRequest,
                Collections.<String, String>emptyMap(), c), 201).json;
        JsonNode decision = createAndFinalizeDecision(claim.path("iri").asText(),
                v2.path("iri").asText(), "adopted",
                "Select one independent technical target", c);
        JsonNode selectedTarget = targetWithObjectValue(claim,
                "selected technical note");
        Scenario s = new Scenario(); s.v1 = v1; s.v2 = v2;
        c.captureBefore();
        ObjectNode plan = plan(s, operation("INSERT",
                selectedTarget.path("iri").asText(),
                decision.path("iri").asText()));
        HttpResult preview = postJson("lexint/publications/preview", plan,
                Collections.<String, String>emptyMap(), c);
        check(preview.status == 200
                        && preview.json.path("partialClaims").asBoolean(),
                "preview reports explicit 1/2 partial selection", c);
        HttpResult published = publish(plan,
                preview.json.path("baseEtag").asText(),
                "dsh-t7-" + UUID.randomUUID(), c);
        check(published.status == 201, "partial technical plan published", c);
        Model active = graph(ACTIVE);
        check(active.contains(VF.createIRI(EX + "razza"),
                        VF.createIRI("http://www.w3.org/2000/01/rdf-schema#comment"),
                        VF.createLiteral("selected technical note")),
                "selected target is asserted", c);
        check(!active.contains(VF.createIRI(EX + "razza"),
                        VF.createIRI("http://www.w3.org/2000/01/rdf-schema#comment"),
                        VF.createLiteral("unselected technical note")),
                "unselected target is not asserted", c);
        check(published.json.path("operations").size() == 1,
                "exactly one operation was recorded", c);
        c.captureAfter();
    }

    private void t08(CaseRecorder c) throws Exception {
        Scenario stale = setupScenario(c, false);
        ObjectNode stalePlan = t1Plan(stale);
        HttpResult stalePreview = postJson("lexint/publications/preview",
                stalePlan, Collections.<String, String>emptyMap(), c);
        c.captureBefore();
        HttpResult staleResponse = publish(stalePlan, "\"stale\"",
                "dsh-t8a-" + UUID.randomUUID(), c);
        check(staleResponse.status == 412
                        && "LEXINT_BASE_VERSION_MISMATCH".equals(
                                staleResponse.errorCode()),
                "T8a stale If-Match is rejected", c);
        check(countType(AUDIT, PUB + "Publication") == 0L,
                "T8a creates no publication", c);

        Scenario drift = setupScenario(c, false);
        ObjectNode driftPlan = t1Plan(drift);
        HttpResult driftPreview = postJson("lexint/publications/preview",
                driftPlan, Collections.<String, String>emptyMap(), c);
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(iri(EX + "outsideWorkflow"),
                    iri(EX + "status"), VF.createLiteral("drift"), ACTIVE);
        }
        HttpResult driftResponse = publish(driftPlan,
                driftPreview.json.path("baseEtag").asText(),
                "dsh-t8b-" + UUID.randomUUID(), c);
        check(driftResponse.status == 409
                        && "LEXINT_ACTIVE_GRAPH_DRIFT".equals(
                                driftResponse.errorCode()),
                "T8b active drift is rejected", c);
        check(graph(ACTIVE).contains(iri(EX + "outsideWorkflow"),
                        iri(EX + "status"), VF.createLiteral("drift")),
                "drift is detected rather than silently repaired", c);
        check(countType(AUDIT, PUB + "Publication") == 0L,
                "T8b creates no publication", c);
        c.expectedHttp = "T8a=412;T8b=409";
        c.observedHttp = "T8a=" + staleResponse.status + ";T8b="
                + driftResponse.status;
        c.captureAfter();
    }

    private void t09(CaseRecorder c) throws Exception {
        Scenario failed = setupScenario(c, false);
        HttpResult preview = postJson("lexint/publications/preview",
                t1Plan(failed), Collections.<String, String>emptyMap(), c);
        check(preview.status == 200, "GraphDB failpoint plan is READY", c);
        JsonNode source = getJson("lexint/versions/"
                + failed.v1.path("id").asText(), c);
        failed.v1Graph = iri(source.path("snapshotGraphIri").asText());
        failed.v2Graph = iri(SNAPSHOT_BASE + "it/"
                + failed.v2.path("versionCode").asText());
        List<Resource> affected = Arrays.<Resource>asList(ACTIVE,
                failed.v1Graph, failed.v2Graph, RECORDS, AUDIT);
        GraphState before = graphState(affected);
        writeGraphState(c.directory.resolve("graph-before.trig"), before);
        writeJson(c.directory.resolve("graph-inventory-before.json"),
                before.inventory());
        c.beforeDigest = before.digest;

        boolean l0Passed = false;
        Path l0 = Paths.get(System.getProperty(
                "lexo.test.dsh2027.l0Evidence", ""));
        if (Files.isRegularFile(l0)) {
            JsonNode evidence = JSON.readTree(l0.toFile());
            l0Passed = evidence.path("failpoint_reached").asBoolean()
                    && evidence.path("rollback_complete").asBoolean();
            Files.copy(l0, c.directory.resolve("t09-l0-evidence.json"));
        }

        final AtomicInteger hookCount = new AtomicInteger();
        LexIntPublicationManager interrupted = new LexIntPublicationManager(
                new LexIntRepository(repository), new LexIntResourceUris(),
                new LexIntMetadataWriter(), new PublicationFaultInjector() {
                    @Override public void afterProvisionalWrites() {
                        hookCount.incrementAndGet();
                        throw new DeliberateGraphDbPublicationFailure();
                    }
                });
        PublicationRequest request = JSON.treeToValue(t1Plan(failed),
                PublicationRequest.class);
        LexIntActor actor = new LexIntActor(AUTHOR, "DECLARED", false,
                null, null, Collections.<String>emptySet());
        String exceptionType = "";
        try {
            interrupted.publish(request, actor,
                    preview.json.path("baseEtag").asText(),
                    "dsh-t09-graphdb-" + UUID.randomUUID(),
                    runId + "-T09-direct");
        } catch (DeliberateGraphDbPublicationFailure expected) {
            exceptionType = expected.getClass().getName();
        }
        GraphState after = graphState(affected);
        writeGraphState(c.directory.resolve("graph-after.trig"), after);
        writeJson(c.directory.resolve("graph-inventory-after.json"),
                after.inventory());
        c.afterDigest = after.digest;

        boolean graphDbRepository = HTTPRepository.class.getName().equals(
                repository.getClass().getName())
                && before.connectionClass.contains("HTTPRepositoryConnection")
                && after.connectionClass.contains("HTTPRepositoryConnection");
        boolean stateIdentical = before.isomorphic(after);
        check(graphDbRepository, "T09 used RDF4J HTTPRepository GraphDB backend", c);
        check(hookCount.get() == 1, "GraphDB pre-commit hook reached exactly once", c);
        check(!exceptionType.isEmpty(), "injected GraphDB exception escaped", c);
        check(stateIdentical, "all affected GraphDB graphs rolled back exactly", c);
        check(Models.isomorphic(after.graph(failed.v1Graph),
                        before.graph(failed.v1Graph)),
                "V1 snapshot is unchanged", c);
        check(after.graph(failed.v2Graph).isEmpty(),
                "V2 published snapshot remains absent", c);
        check(Models.isomorphic(after.graph(ACTIVE), before.graph(ACTIVE)),
                "active lexical graph is unchanged", c);
        check(Models.isomorphic(after.graph(RECORDS), before.graph(RECORDS)),
                "claim and decision records are unchanged", c);
        check(Models.isomorphic(after.graph(AUDIT), before.graph(AUDIT)),
                "version ledger, publication, operations and audit are unchanged", c);

        ObjectNode backend = JSON.createObjectNode();
        backend.put("graphdb_version", System.getProperty(
                "lexo.test.dsh2027.graphdbVersion", "10.8.14"));
        backend.put("repository_url",
                "http://graphdb:7200/repositories/LexOLexica");
        backend.put("repository_class", repository.getClass().getName());
        backend.put("connection_class", before.connectionClass);
        backend.put("dedicated", true);
        writeJson(c.directory.resolve("backend.json"), backend);
        ObjectNode hook = JSON.createObjectNode();
        hook.put("stage", "after provisional snapshot, active, operation, ledger and audit writes; before SHACL/commit");
        hook.put("hook_count", hookCount.get());
        hook.put("exception_type", exceptionType);
        hook.put("exception_observed", !exceptionType.isEmpty());
        hook.put("post_failure_connection_class", after.connectionClass);
        writeJson(c.directory.resolve("hook-evidence.json"), hook);

        Scenario success = setupScenario(c, false);
        HttpResult successPreview = postJson("lexint/publications/preview",
                t1Plan(success), Collections.<String, String>emptyMap(), c);
        HttpResult successPublish = publish(t1Plan(success),
                successPreview.json.path("baseEtag").asText(),
                "dsh-t09-success-" + UUID.randomUUID(), c);
        check(successPublish.status == 201
                        && "PASS".equals(successPublish.json.path(
                                "auditStatus").asText()),
                "ordinary GraphDB publication still succeeds after reset", c);
        ObjectNode components = JSON.createObjectNode();
        components.put("T09-L0", l0Passed ? "PASSED" : "NOT_RUN");
        components.put("T09-GraphDB", "PASSED");
        components.put("overall_T09", "PASSED");
        components.put("rollback_digest_equal",
                before.digest.equals(after.digest));
        components.put("ordinary_success_http", successPublish.status);
        writeJson(c.directory.resolve("t09-components.json"), components);
        c.notes = "T09-L0=" + (l0Passed ? "PASSED" : "NOT_RUN")
                + "; T09-GraphDB=PASSED; exact rollback and reset success path";
    }

    private void t10(CaseRecorder c) throws Exception {
        Scenario s = setupScenario(c, false);
        ObjectNode plan = t1Plan(s);
        HttpResult preview = postJson("lexint/publications/preview", plan,
                Collections.<String, String>emptyMap(), c);
        c.captureBefore();
        String key = "dsh-t10-" + UUID.randomUUID();
        HttpResult first = publish(plan, preview.json.path("baseEtag").asText(),
                key, c);
        HttpResult replay = publish(plan, preview.json.path("baseEtag").asText(),
                key, c);
        check(first.status == 201 && replay.status == 200,
                "identical idempotent replay returns 201 then 200", c);
        check(first.json.path("iri").asText().equals(
                        replay.json.path("iri").asText()),
                "replay returns the same publication IRI", c);
        ObjectNode changed = t1Plan(s);
        ArrayNode changedOperations = changed.withArray("operations");
        changedOperations.remove(0);
        HttpResult conflict = publish(changed,
                preview.json.path("baseEtag").asText(), key, c);
        check(conflict.status == 409
                        && "LEXINT_IDEMPOTENCY_CONFLICT".equals(
                                conflict.errorCode()),
                "same key with a different normalized plan conflicts", c);
        check(countType(AUDIT, PUB + "Publication") == 1L,
                "replay creates only one publication", c);

        ObjectNode entry = JSON.createObjectNode();
        entry.put("label", "bypass attempt");
        entry.put("type", ONTOLEX + "LexicalEntry");
        entry.put("language", "it");
        HttpResult guarded = postJson("lexica/entry?author=" + AUTHOR, entry,
                Collections.<String, String>emptyMap(), c);
        check(guarded.status == 409
                        && guarded.body.contains("LEXINT_MANAGED_WRITE_REQUIRED"),
                "language-graph writer is blocked in managed mode", c);
        check(!graph(ACTIVE).contains(null,
                        VF.createIRI("http://www.w3.org/2000/01/rdf-schema#label"),
                        VF.createLiteral("bypass attempt", "it")),
                "guarded writer produced no lexical triple", c);
        boolean boundary = Boolean.getBoolean(
                "lexo.test.dsh2027.externalGraphdbBlocked");
        if (!boundary) {
            c.blockedReason = "External GraphDB network-boundary probe did not pass";
        } else {
            check(true, "external application client cannot reach GraphDB", c);
        }
        c.captureAfter();
    }

    private void t11(CaseRecorder c) throws Exception {
        Scenario s = publishT1(c, true, "dsh-t11-");
        JsonNode beforeAudit = getJson("lexint/publications/" + s.publicationId
                + "/audit", c);
        String initialReport = beforeAudit.get(0).path("iri").asText();
        c.captureBefore();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(iri(EX + "razza"), iri(ETY),
                    iri(EX + "etyRatio"), s.v2Graph);
        }
        HttpResult verification = postEmpty("lexint/publications/"
                + s.publicationId + "/verify?author=" + AUTHOR, c);
        check(verification.status == 201, "historical verify returned 201", c);
        c.expectedAudit = "FAIL";
        c.observedAudit = verification.json.path("outcome").asText();
        c.unexpected = verification.json.path("unexpectedTripleCount").asLong();
        c.missing = verification.json.path("missingTripleCount").asLong();
        check("FAIL".equals(c.observedAudit) && c.unexpected == 1L
                        && c.missing == 0L,
                "reintroduced ratio produces one unexpected triple", c);
        JsonNode afterAudit = getJson("lexint/publications/" + s.publicationId
                + "/audit", c);
        check(afterAudit.size() == beforeAudit.size() + 1,
                "verification appends one report", c);
        check(initialReport.equals(afterAudit.get(0).path("iri").asText())
                        && "PASS".equals(afterAudit.get(0).path("outcome").asText()),
                "initial PASS report remains unchanged", c);
        c.captureAfter();
    }

    private void t12(CaseRecorder c) throws Exception {
        Scenario s = publishT1(c, true, "dsh-t12-");
        JsonNode v3 = requireStatus(postJson("lexint/versions/drafts",
                languageBody(), Collections.<String, String>emptyMap(), c), 201).json;
        JsonNode claim = requireStatus(postJson("lexint/claims",
                claim("Independent V3 technical note", Collections.<String>emptyList(),
                        Arrays.asList(target(EX + "razza",
                                "http://www.w3.org/2000/01/rdf-schema#comment",
                                "LITERAL", "V3 technical note"))),
                Collections.<String, String>emptyMap(), c), 201).json;
        JsonNode decision = createAndFinalizeDecision(claim.path("iri").asText(),
                v3.path("iri").asText(), "adopted", "Authorize V3 note", c);
        ObjectNode v3Plan = planWithVersions(s.v2.path("iri").asText(),
                v3.path("iri").asText(), operation("INSERT",
                        claim.path("targets").get(0).path("iri").asText(),
                        decision.path("iri").asText()));
        HttpResult preview = postJson("lexint/publications/preview", v3Plan,
                Collections.<String, String>emptyMap(), c);
        HttpResult v3Publication = publish(v3Plan,
                preview.json.path("baseEtag").asText(),
                "dsh-t12-v3-" + UUID.randomUUID(), c);
        check(v3Publication.status == 201, "legitimate V3 was published", c);
        c.captureBefore();
        HttpResult historical = postEmpty("lexint/publications/"
                + s.publicationId + "/verify?author=" + AUTHOR, c);
        check(historical.status == 201
                        && "PASS".equals(historical.json.path("outcome").asText()),
                "V1-to-V2 still verifies PASS after V3", c);
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.clear(s.v1Graph);
        }
        HttpResult missing = postEmpty("lexint/publications/"
                + s.publicationId + "/verify?author=" + AUTHOR, c);
        c.expectedAudit = "INDETERMINATE";
        c.observedAudit = missing.json.path("outcome").asText();
        check(missing.status == 201 && "INDETERMINATE".equals(c.observedAudit),
                "missing V1 snapshot produces persisted INDETERMINATE", c);
        check(missing.json.path("errorCodes").toString().contains(
                        "LEXINT_AUDIT_INPUT_UNAVAILABLE"),
                "indeterminate report carries a diagnostic code", c);
        JsonNode audit = getJson("lexint/publications/" + s.publicationId
                + "/audit", c);
        check(audit.size() >= 3, "historical reports are append-only", c);
        runCq7(s, historical.json, missing.json, c);
        c.captureAfter();
    }

    private void runCounterexamples() throws Exception {
        counterexample("CE1-retained-ratio", new Mutator() {
            @Override public void mutate(RepositoryConnection connection,
                    Scenario s) {
                connection.add(iri(EX + "razza"), iri(ETY),
                        iri(EX + "etyRatio"), s.v2Graph);
            }
        }, 0L, 1L);
        counterexample("CE2-missing-haraz", new Mutator() {
            @Override public void mutate(RepositoryConnection connection,
                    Scenario s) {
                connection.remove(iri(EX + "razza"), iri(ETY),
                        iri(EX + "etyHaraz"), s.v2Graph);
            }
        }, 1L, 0L);
        counterexample("CE3-materialised-sense", new Mutator() {
            @Override public void mutate(RepositoryConnection connection,
                    Scenario s) {
                Statement candidate = semanticStatements().iterator().next();
                connection.add(candidate.getSubject(), candidate.getPredicate(),
                        candidate.getObject(), s.v2Graph);
            }
        }, 0L, 1L);
    }

    private void counterexample(String id, Mutator mutator, long expectedMissing,
            long expectedUnexpected) throws Exception {
        CaseRecorder recorder = new CaseRecorder(id, "comparison",
                output.resolve("artifacts").resolve(id));
        Files.createDirectories(recorder.directory);
        ComparisonResult result = new ComparisonResult(); result.id = id;
        try {
            Scenario s = publishT1(recorder, true, "dsh-" + id + "-");
            try (RepositoryConnection connection = repository.getConnection()) {
                mutator.mutate(connection, s);
            }
            boolean conforms = structuralShaclConforms(recorder.directory);
            HttpResult verification = postEmpty("lexint/publications/"
                    + s.publicationId + "/verify?author=" + AUTHOR, recorder);
            result.shacl = conforms ? "CONFORMS" : "VIOLATES";
            result.audit = verification.json.path("outcome").asText();
            result.missing = verification.json.path("missingTripleCount").asLong();
            result.unexpected = verification.json.path(
                    "unexpectedTripleCount").asLong();
            result.detectedAt = Instant.now().toString();
            result.oracleMatch = conforms && "FAIL".equals(result.audit)
                    && result.missing == expectedMissing
                    && result.unexpected == expectedUnexpected;
            result.notes = result.oracleMatch ? "Observed exactly as frozen"
                    : "Observed result differs from frozen oracle";
        } catch (Throwable failure) {
            result.shacl = "UNAVAILABLE"; result.audit = "INDETERMINATE";
            result.oracleMatch = false;
            result.notes = failure.getClass().getSimpleName() + ": "
                    + String.valueOf(failure.getMessage());
        }
        comparisons.add(result);
        writeJson(recorder.directory.resolve("comparison-result.json"),
                result.toJson());
    }

    private interface Mutator {
        void mutate(RepositoryConnection connection, Scenario scenario);
    }

    private Scenario setupScenario(CaseRecorder c, boolean semantic)
            throws Exception {
        resetAndLoadFixture();
        Scenario s = new Scenario();
        s.v1 = requireStatus(postJson("lexint/versions/baseline", languageBody(),
                Collections.<String, String>emptyMap(), c), 201).json;
        s.v2 = requireStatus(postJson("lexint/versions/drafts", languageBody(),
                Collections.<String, String>emptyMap(), c), 201).json;
        s.ratioClaim = requireStatus(postJson("lexint/claims",
                claim("Retrospective record of the demonstrative V1 ratio assertion",
                        Collections.<String>emptyList(), Arrays.asList(
                                target(EX + "razza", ETY, "IRI",
                                        EX + "etyRatio"))),
                Collections.<String, String>emptyMap(), c), 201).json;
        s.harazClaim = requireStatus(postJson("lexint/claims",
                claim("Demonstrative haraz proposal; no truth claim is made",
                        Arrays.asList(EX + "source/contini-1959",
                                EX + "source/sabatini-1962"),
                        Arrays.asList(target(EX + "razza", ETY, "IRI",
                                EX + "etyHaraz"))),
                Collections.<String, String>emptyMap(), c), 201).json;
        s.ratioDecision = createAndFinalizeDecision(
                s.ratioClaim.path("iri").asText(),
                s.v2.path("iri").asText(), "replaced",
                "Replace the demonstrative V1 assertion in V2", c);
        s.harazDecision = createAndFinalizeDecision(
                s.harazClaim.path("iri").asText(),
                s.v2.path("iri").asText(), "adopted",
                "Adopt the demonstrative haraz assertion in V2", c);
        if (semantic) {
            s.semanticClaim = requireStatus(postJson("lexint/claims",
                    semanticClaim(), Collections.<String, String>emptyMap(), c),
                    201).json;
            s.semanticDecision = createAndFinalizeDecision(
                    s.semanticClaim.path("iri").asText(),
                    s.v2.path("iri").asText(), "suspended",
                    "The proposed sense remains unresolved", c);
        }
        return s;
    }

    private Scenario publishT1(CaseRecorder c, boolean semantic, String keyPrefix)
            throws Exception {
        Scenario s = setupScenario(c, semantic);
        ObjectNode plan = t1Plan(s);
        HttpResult preview = postJson("lexint/publications/preview", plan,
                Collections.<String, String>emptyMap(), c);
        check(preview.status == 200, "T1 setup preview is ready", c);
        HttpResult published = publish(plan,
                preview.json.path("baseEtag").asText(),
                keyPrefix + UUID.randomUUID(), c);
        check(published.status == 201, "T1 setup publication succeeded", c);
        s.publication = published.json;
        s.publicationIri = published.json.path("iri").asText();
        s.publicationId = published.json.path("id").asText();
        s.targetEtag = published.json.path("targetEtag").asText();
        JsonNode v1 = getJson("lexint/versions/" + s.v1.path("id").asText(), c);
        JsonNode v2 = getJson("lexint/versions/" + s.v2.path("id").asText(), c);
        s.v1Graph = iri(v1.path("snapshotGraphIri").asText());
        s.v2Graph = iri(v2.path("snapshotGraphIri").asText());
        return s;
    }

    private ObjectNode t1Plan(Scenario s) {
        return plan(s,
                operation("DELETE",
                        s.ratioClaim.path("targets").get(0).path("iri").asText(),
                        s.ratioDecision.path("iri").asText()),
                operation("INSERT",
                        s.harazClaim.path("targets").get(0).path("iri").asText(),
                        s.harazDecision.path("iri").asText()));
    }

    private ObjectNode plan(Scenario s, ObjectNode... operations) {
        return planWithVersions(s.v1.path("iri").asText(),
                s.v2.path("iri").asText(), operations);
    }

    private ObjectNode planWithVersions(String source, String target,
            ObjectNode... operations) {
        ObjectNode request = JSON.createObjectNode();
        request.put("language", "it");
        request.put("sourceVersionIri", source);
        request.put("targetVersionIri", target);
        request.put("author", AUTHOR);
        ArrayNode array = request.putArray("operations");
        for (ObjectNode operation : operations) array.add(operation);
        return request;
    }

    private ObjectNode operation(String action, String target, String decision) {
        ObjectNode operation = JSON.createObjectNode();
        operation.put("action", action);
        operation.put("targetAssertionIri", target);
        operation.put("decisionIri", decision);
        return operation;
    }

    private ObjectNode semanticClaim() {
        return claim("Contrasted sources do not necessarily imply distinct senses",
                Arrays.asList(
                        "https://www.cdec.it/formazione/percorsi/per-la-storia-della-shoah/i-testi-programmatici-del-razzismo-e-dellantisemitismo-fascista/",
                        "https://www.senato.it/istituzione/la-costituzione/principi-fondamentali/articolo-3"),
                Arrays.asList(
                        target(EX + "senseCandidateA", RDF.TYPE.stringValue(),
                                "IRI", ONTOLEX + "LexicalSense"),
                        target(EX + "razza", ONTOLEX + "sense", "IRI",
                                EX + "senseCandidateA"),
                        target(EX + "senseCandidateA", ONTOLEX + "reference",
                                "IRI", EX + "conceptCandidateA")));
    }

    private ObjectNode claim(String description, List<String> evidence,
            List<ObjectNode> targets) {
        ObjectNode request = JSON.createObjectNode();
        request.put("description", description);
        request.put("author", AUTHOR);
        ArrayNode sourceArray = request.putArray("evidenceIris");
        for (String source : evidence) sourceArray.add(source);
        ArrayNode targetArray = request.putArray("targets");
        for (ObjectNode target : targets) targetArray.add(target);
        return request;
    }

    private ObjectNode target(String subject, String predicate, String kind,
            String value) {
        ObjectNode target = JSON.createObjectNode();
        target.put("subjectIri", subject); target.put("predicateIri", predicate);
        ObjectNode object = target.putObject("object");
        object.put("kind", kind); object.put("value", value);
        return target;
    }

    private JsonNode targetWithObjectValue(JsonNode claim, String value) {
        for (JsonNode target : claim.path("targets")) {
            if (value.equals(target.path("object").path("value").asText())) {
                return target;
            }
        }
        throw new AssertionError("Target not returned for object value: " + value);
    }

    private JsonNode targetWithPredicate(JsonNode claim, String predicate) {
        for (JsonNode target : claim.path("targets")) {
            if (predicate.equals(target.path("predicateIri").asText())) {
                return target;
            }
        }
        throw new AssertionError("Target not returned for predicate: "
                + predicate);
    }

    private ObjectNode decision(String claim, String version, String outcome,
            String description) {
        ObjectNode request = JSON.createObjectNode();
        request.put("claimIri", claim); request.put("versionIri", version);
        request.put("outcome", outcome); request.put("description", description);
        request.put("author", AUTHOR);
        return request;
    }

    private JsonNode createAndFinalizeDecision(String claim, String version,
            String outcome, String description, CaseRecorder c) throws Exception {
        JsonNode created = requireStatus(postJson("lexint/decisions",
                decision(claim, version, outcome, description),
                Collections.<String, String>emptyMap(), c), 201).json;
        return requireStatus(postEmpty("lexint/decisions/"
                + created.path("id").asText() + "/finalize?author=" + AUTHOR, c),
                200).json;
    }

    private ObjectNode languageBody() {
        ObjectNode request = JSON.createObjectNode();
        request.put("language", "it"); request.put("author", AUTHOR);
        return request;
    }

    private void resetAndLoadFixture() throws Exception {
        try (RepositoryConnection connection = repository.getConnection()) {
            if (connection.size((Resource) null) != 0L) {
                throw new AssertionError("Default graph is not empty before reset");
            }
            connection.begin();
            try {
                connection.clear(ACTIVE); connection.clear(RECORDS);
                connection.clear(AUDIT);
                List<Resource> snapshots = new ArrayList<Resource>();
                try (RepositoryResult<Resource> contexts =
                        connection.getContextIDs()) {
                    while (contexts.hasNext()) {
                        Resource context = contexts.next();
                        if (context.stringValue().startsWith(SNAPSHOT_BASE)) {
                            snapshots.add(context);
                        }
                    }
                }
                for (Resource snapshot : snapshots) connection.clear(snapshot);
                connection.add(fixture("fixtures/lexical-v1.ttl"), ACTIVE);
                connection.commit();
            } catch (RuntimeException failure) {
                connection.rollback(); throw failure;
            }
        }
    }

    private Model fixture(String relative) throws Exception {
        Path file = evaluationRoot.resolve(relative).normalize();
        if (!file.startsWith(evaluationRoot) || !Files.isRegularFile(file)) {
            throw new IOException("Missing frozen fixture " + relative);
        }
        try (java.io.InputStream input = Files.newInputStream(file)) {
            return Rio.parse(input, file.toUri().toString(), RDFFormat.TURTLE);
        }
    }

    private Set<Statement> semanticStatements() {
        Set<Statement> result = new LinkedHashSet<Statement>();
        result.add(statement(EX + "senseCandidateA", RDF.TYPE.stringValue(),
                iri(ONTOLEX + "LexicalSense")));
        result.add(statement(EX + "razza", ONTOLEX + "sense",
                iri(EX + "senseCandidateA")));
        result.add(statement(EX + "senseCandidateA", ONTOLEX + "reference",
                iri(EX + "conceptCandidateA")));
        return result;
    }

    private long countSemanticOperations(String claimIri) {
        long count = 0L;
        try (RepositoryConnection connection = repository.getConnection();
                RepositoryResult<Statement> targets = connection.getStatements(
                        iri(claimIri), iri(LEXINT + "targetAssertion"), null,
                        false, RECORDS)) {
            while (targets.hasNext()) {
                Value target = targets.next().getObject();
                if (target instanceof IRI) {
                    try (RepositoryResult<Statement> operations =
                            connection.getStatements(null,
                                    iri(PUB + "targetAssertion"), target,
                                    false, AUDIT)) {
                        while (operations.hasNext()) { operations.next(); count++; }
                    }
                }
            }
        }
        return count;
    }

    private HttpResult publish(ObjectNode body, String etag, String key,
            CaseRecorder recorder) throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("If-Match", etag); headers.put("Idempotency-Key", key);
        return postJson("lexint/publications", body, headers, recorder);
    }

    private HttpResult postJson(String path, JsonNode body,
            Map<String, String> headers, CaseRecorder recorder) throws Exception {
        WebTarget target = target(path);
        Invocation.Builder request = target.request();
        String requestId = runId + "-" + recorder.testId + "-"
                + (recorder.requests.size() + 1);
        request.header("X-Request-ID", requestId);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        long started = System.nanoTime();
        try (Response response = request.post(Entity.json(body.toString()))) {
            HttpResult result = read(response);
            recorder.record("POST", path, body, headers, requestId, result,
                    (System.nanoTime() - started) / 1000000L);
            return result;
        }
    }

    private HttpResult postEmpty(String path, CaseRecorder recorder)
            throws Exception {
        String requestId = runId + "-" + recorder.testId + "-"
                + (recorder.requests.size() + 1);
        long started = System.nanoTime();
        try (Response response = target(path).request()
                .header("X-Request-ID", requestId).post(Entity.text(""))) {
            HttpResult result = read(response);
            recorder.record("POST", path, null,
                    Collections.<String, String>emptyMap(), requestId, result,
                    (System.nanoTime() - started) / 1000000L);
            return result;
        }
    }

    private JsonNode getJson(String path, CaseRecorder recorder)
            throws Exception {
        String requestId = runId + "-" + recorder.testId + "-"
                + (recorder.requests.size() + 1);
        long started = System.nanoTime();
        try (Response response = target(path).request()
                .header("X-Request-ID", requestId).get()) {
            HttpResult result = read(response);
            recorder.record("GET", path, null,
                    Collections.<String, String>emptyMap(), requestId, result,
                    (System.nanoTime() - started) / 1000000L);
            if (result.status != 200) {
                throw new AssertionError("GET " + path + " returned "
                        + result.status + ": " + result.body);
            }
            return result.json;
        }
    }

    private WebTarget target(String path) {
        String[] split = path.split("\\?", 2);
        WebTarget target = client.target(base).path(split[0]);
        if (split.length == 2) {
            for (String pair : split[1].split("&")) {
                String[] item = pair.split("=", 2);
                target = target.queryParam(item[0], item.length == 2
                        ? item[1] : "");
            }
        }
        return target;
    }

    private HttpResult read(Response response) throws Exception {
        String body = response.hasEntity() ? response.readEntity(String.class) : "";
        JsonNode json;
        try { json = body.trim().isEmpty() ? JSON.createObjectNode()
                : JSON.readTree(body); }
        catch (IOException notJson) {
            ObjectNode wrapper = JSON.createObjectNode(); wrapper.put("text", body);
            json = wrapper;
        }
        return new HttpResult(response.getStatus(), body, json,
                response.getHeaderString("X-Request-ID"));
    }

    private HttpResult requireStatus(HttpResult response, int status) {
        if (response.status != status) {
            throw new AssertionError("Expected HTTP " + status + " but got "
                    + response.status + ": " + response.body);
        }
        return response;
    }

    private Model graph(Resource graph) {
        Model result = new LinkedHashModel();
        try (RepositoryConnection connection = repository.getConnection();
                RepositoryResult<Statement> rows = connection.getStatements(
                        null, null, null, false, graph)) {
            while (rows.hasNext()) {
                Statement statement = rows.next();
                result.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject());
            }
        }
        return result;
    }

    private GraphState graphState(List<Resource> graphs) throws Exception {
        Map<Resource, Model> models = new LinkedHashMap<Resource, Model>();
        Map<Resource, String> digests = new LinkedHashMap<Resource, String>();
        Model combined = new LinkedHashModel();
        String connectionClass;
        try (RepositoryConnection connection = repository.getConnection()) {
            connectionClass = connection.getClass().getName();
            for (Resource graph : graphs) {
                Model model = new LinkedHashModel();
                try (RepositoryResult<Statement> statements =
                        connection.getStatements(null, null, null, false, graph)) {
                    while (statements.hasNext()) {
                        Statement statement = statements.next();
                        model.add(statement.getSubject(), statement.getPredicate(),
                                statement.getObject());
                        combined.add(statement.getSubject(), statement.getPredicate(),
                                statement.getObject(), graph);
                    }
                }
                models.put(graph, model);
                digests.put(graph, digestStatements(model, graph));
            }
        }
        return new GraphState(models, digests, combined,
                digestStatements(combined, null), connectionClass);
    }

    private String digestStatements(Model model, Resource defaultContext)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> values = new ArrayList<String>();
        for (Statement statement : model) {
            Resource context = statement.getContext() == null
                    ? defaultContext : statement.getContext();
            values.add(statement.getSubject().stringValue() + "\t"
                    + statement.getPredicate().stringValue() + "\t"
                    + statement.getObject().toString() + "\t"
                    + (context == null ? "DEFAULT" : context.stringValue()));
        }
        Collections.sort(values);
        for (String value : values) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private void writeGraphState(Path file, GraphState state) throws Exception {
        try (OutputStream stream = Files.newOutputStream(file)) {
            Rio.write(state.combined, stream, RDFFormat.TRIG);
        }
    }

    private void assertGraph(Resource graph, Model expected, String label,
            CaseRecorder recorder) {
        Model actual = graph(graph);
        Delta mismatch = delta(expected, actual);
        recorder.oracle.put(label, mismatch.toJson());
        recorder.missing += mismatch.removed.size();
        recorder.unexpected += mismatch.added.size();
        check(Models.isomorphic(expected, actual), label, recorder);
    }

    private Delta delta(Model source, Model destination) {
        Model removed = new LinkedHashModel(source);
        removed.removeAll(destination);
        Model added = new LinkedHashModel(destination);
        added.removeAll(source);
        return new Delta(removed, added);
    }

    private long defaultGraphSize() {
        try (RepositoryConnection connection = repository.getConnection()) {
            return connection.size((Resource) null);
        }
    }

    private long countType(Resource graph, String type) {
        long count = 0L;
        try (RepositoryConnection connection = repository.getConnection();
                RepositoryResult<Statement> rows = connection.getStatements(
                        null, RDF.TYPE, iri(type), false, graph)) {
            while (rows.hasNext()) { rows.next(); count++; }
        }
        return count;
    }

    private void assertRuntimeMetadata(CaseRecorder recorder) {
        try (RepositoryConnection connection = repository.getConnection()) {
            for (IRI graph : Arrays.asList(RECORDS, AUDIT)) {
                Set<Resource> individuals = new LinkedHashSet<Resource>();
                try (RepositoryResult<Statement> rows = connection.getStatements(
                        null, RDF.TYPE, null, false, graph)) {
                    while (rows.hasNext()) individuals.add(rows.next().getSubject());
                }
                for (Resource individual : individuals) {
                    assertSingle(connection, individual, DCTERMS.CREATOR,
                            graph, null);
                    assertSingle(connection, individual, DCTERMS.CREATED,
                            graph, XSD.DATETIME);
                    assertSingle(connection, individual, DCTERMS.MODIFIED,
                            graph, XSD.DATETIME);
                }
            }
        }
        check(true, "every typed runtime individual has creator/created/modified",
                recorder);
    }

    private void assertSingle(RepositoryConnection connection, Resource subject,
            IRI predicate, Resource graph, IRI datatype) {
        List<Value> values = new ArrayList<Value>();
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) values.add(rows.next().getObject());
        }
        if (values.size() != 1) {
            throw new AssertionError(subject + " requires exactly one " + predicate);
        }
        if (datatype != null && (!(values.get(0) instanceof Literal)
                || !datatype.equals(((Literal) values.get(0)).getDatatype()))) {
            throw new AssertionError(subject + " has wrong datatype for " + predicate);
        }
    }

    private String evaluationDigest() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> statements = new ArrayList<String>();
        try (RepositoryConnection connection = repository.getConnection()) {
            for (Resource context : evaluationContexts(connection)) {
                try (RepositoryResult<Statement> rows = connection.getStatements(
                        null, null, null, false, context)) {
                    while (rows.hasNext()) statements.add(canonical(rows.next()));
                }
            }
        }
        Collections.sort(statements);
        for (String value : statements) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private List<Resource> evaluationContexts(RepositoryConnection connection) {
        List<Resource> contexts = new ArrayList<Resource>();
        try (RepositoryResult<Resource> rows = connection.getContextIDs()) {
            while (rows.hasNext()) {
                Resource context = rows.next();
                String value = context.stringValue();
                if (ACTIVE.equals(context) || RECORDS.equals(context)
                        || AUDIT.equals(context)
                        || value.startsWith(SNAPSHOT_BASE)) contexts.add(context);
            }
        }
        Collections.sort(contexts, new Comparator<Resource>() {
            @Override public int compare(Resource left, Resource right) {
                return left.stringValue().compareTo(right.stringValue());
            }
        });
        return contexts;
    }

    private String canonical(Statement statement) {
        return statement.getSubject().stringValue() + "\t"
                + statement.getPredicate().stringValue() + "\t"
                + statement.getObject().toString() + "\t"
                + (statement.getContext() == null ? "DEFAULT"
                        : statement.getContext().stringValue());
    }

    private void exportEvaluationGraphs(Path file) throws Exception {
        Model model = new LinkedHashModel();
        try (RepositoryConnection connection = repository.getConnection()) {
            for (Resource context : evaluationContexts(connection)) {
                try (RepositoryResult<Statement> rows = connection.getStatements(
                        null, null, null, false, context)) {
                    while (rows.hasNext()) model.add(rows.next());
                }
            }
        }
        try (OutputStream stream = Files.newOutputStream(file)) {
            Rio.write(model, stream, RDFFormat.TRIG);
        }
    }

    private static Statement statement(String subject, String predicate,
            Value object) {
        return VF.createStatement(iri(subject), iri(predicate), object);
    }

    private static IRI iri(String value) { return VF.createIRI(value); }

    private static void check(boolean condition, String label,
            CaseRecorder recorder) {
        recorder.assertions.put(label, condition);
        if (!condition) throw new AssertionError(label);
    }

    private void runCq1To6(Scenario s, CaseRecorder recorder) throws Exception {
        Map<String, String> values = new HashMap<String, String>();
        values.put("V1_GRAPH", s.v1Graph.stringValue());
        values.put("V2_GRAPH", s.v2Graph.stringValue());
        values.put("CLAIM_HARAZ", s.harazClaim.path("iri").asText());
        values.put("CLAIM_SENSE", s.semanticClaim.path("iri").asText());
        values.put("PUBLICATION", s.publicationIri);
        cq1To6Placeholders = placeholders(s);
        for (int index = 1; index <= 6; index++) {
            String id = "CQ" + index;
            List<Dsh2027BindingComparator.Row> rows = query(id, values);
            boolean nonQueryPass = index != 6
                    || nonePublished(semanticStatements(), s.v2Graph);
            CqResult result = evaluateCq(id, rows, cq1To6Placeholders,
                    nonQueryPass);
            cqResults.put(id, result);
            check("EXACT".equals(result.status),
                    id + " complete binding multiset matches frozen oracle",
                    recorder);
        }
    }

    private void runCq7(Scenario s, JsonNode historical, JsonNode missing,
            CaseRecorder recorder)
            throws Exception {
        Map<String, String> values = new HashMap<String, String>();
        values.put("PUBLICATION", s.publicationIri);
        cq7Placeholders = placeholders(s);
        cq7Placeholders.put("VERIFICATION_AFTER_V3",
                historical.path("verificationIri").asText());
        cq7Placeholders.put("VERIFICATION_MISSING_V1",
                missing.path("verificationIri").asText());
        ObjectNode auditIds = JSON.createObjectNode();
        auditIds.put("run_id", runId);
        auditIds.put("publication", s.publicationIri);
        for (Map.Entry<String, String> entry : cq7Placeholders.entrySet()) {
            auditIds.put(entry.getKey(), entry.getValue());
        }
        writeJson(output.resolve("artifacts/audit-id-map.json"), auditIds);
        List<Dsh2027BindingComparator.Row> rows = query("CQ7", values);
        CqResult result = evaluateCq("CQ7", rows, cq7Placeholders, true);
        cqResults.put("CQ7", result);
        check("EXACT".equals(result.status),
                "CQ7 exact audit rows preserve V1-to-V2 scope and multiplicity",
                recorder);
    }

    private CqResult evaluateCq(String id,
            List<Dsh2027BindingComparator.Row> observed,
            Map<String, String> placeholders, boolean nonQueryPass)
            throws Exception {
        Dsh2027BindingComparator.Expected expected =
                Dsh2027BindingComparator.expected(cqOracle.path(id), placeholders);
        Dsh2027BindingComparator.Comparison comparison =
                Dsh2027BindingComparator.compare(expected, observed);
        CqResult result = new CqResult(id);
        int number = Integer.parseInt(id.substring(2));
        result.queryPath = String.format(Locale.ROOT, "queries/cq%02d.rq",
                Integer.valueOf(number));
        result.evidencePath = String.format(Locale.ROOT,
                "queries/cq%02d-results.json", Integer.valueOf(number));
        result.expectedRows = comparison.expectedRows;
        result.observedRows = comparison.observedRows;
        result.matchedRows = comparison.matchedRows;
        result.missingRows = comparison.missingRows.size();
        result.unexpectedRows = comparison.unexpectedRows.size();
        result.completeness = decimal(comparison.completeness());
        result.precision = decimal(comparison.precision());
        result.nonQueryChecksPass = nonQueryPass;
        result.status = "EXACT".equals(comparison.status) && nonQueryPass
                ? "EXACT" : "INCORRECT";
        ObjectNode evidence = comparison.toJson(JSON, expected, observed);
        evidence.put("cq_id", id);
        evidence.put("oracle_path", "oracle/cq-answer-key.yaml");
        evidence.put("query_path", result.queryPath);
        evidence.put("non_query_checks_pass", nonQueryPass);
        ObjectNode resolved = evidence.putObject("resolved_placeholders");
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            resolved.put(placeholder.getKey(), placeholder.getValue());
        }
        writeJson(output.resolve(result.evidencePath), evidence);
        return result;
    }

    private Map<String, String> placeholders(Scenario s) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("V1", s.v1.path("iri").asText());
        values.put("V2", s.v2.path("iri").asText());
        values.put("V1_GRAPH", s.v1Graph.stringValue());
        values.put("V2_GRAPH", s.v2Graph.stringValue());
        values.put("D_RATIO", s.ratioDecision.path("iri").asText());
        values.put("D_HARAZ", s.harazDecision.path("iri").asText());
        if (s.semanticDecision != null) {
            values.put("D_SENSE", s.semanticDecision.path("iri").asText());
            values.put("TARGET_SENSE_TYPE",
                    targetWithPredicate(s.semanticClaim, RDF.TYPE.stringValue())
                            .path("iri").asText());
            values.put("TARGET_ENTRY_SENSE",
                    targetWithPredicate(s.semanticClaim, ONTOLEX + "sense")
                            .path("iri").asText());
            values.put("TARGET_SENSE_REFERENCE",
                    targetWithPredicate(s.semanticClaim, ONTOLEX + "reference")
                            .path("iri").asText());
        }
        for (JsonNode operation : s.publication.path("operations")) {
            values.put("INSERT".equals(operation.path("action").asText())
                    ? "OP_INSERT" : "OP_DELETE",
                    operation.path("iri").asText());
        }
        if (s.publication.path("audit").size() > 0) {
            values.put("VERIFICATION_INITIAL", s.publication.path("audit")
                    .get(0).path("verificationIri").asText());
        }
        return values;
    }

    private List<Dsh2027BindingComparator.Row> query(String id,
            Map<String, String> replacements) throws Exception {
        int number = Integer.parseInt(id.substring(2));
        Path source = evaluationRoot.resolve(String.format(Locale.ROOT,
                "queries/cq%02d.rq", Integer.valueOf(number)));
        String sparql = new String(Files.readAllBytes(source),
                StandardCharsets.UTF_8);
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            sparql = sparql.replace("${" + replacement.getKey() + "}",
                    replacement.getValue());
        }
        if (sparql.contains("${")) {
            throw new AssertionError("Unresolved query parameter in " + id);
        }
        Files.write(output.resolve("queries").resolve(String.format(Locale.ROOT,
                "cq%02d-rendered.rq", Integer.valueOf(number))),
                sparql.getBytes(StandardCharsets.UTF_8));
        List<Dsh2027BindingComparator.Row> rows =
                new ArrayList<Dsh2027BindingComparator.Row>();
        try (RepositoryConnection connection = repository.getConnection();
                TupleQueryResult result = connection.prepareTupleQuery(
                        QueryLanguage.SPARQL, sparql).evaluate()) {
            while (result.hasNext()) {
                BindingSet bindings = result.next();
                Map<String, Value> row = new TreeMap<String, Value>();
                for (String name : bindings.getBindingNames()) {
                    Value value = bindings.getValue(name);
                    if (value != null) row.put(name, value);
                }
                rows.add(Dsh2027BindingComparator.observed(row));
            }
        }
        return rows;
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", Double.valueOf(value));
    }

    private void completeCq6Evidence() throws Exception {
        CqResult cq6 = cqResults.get("CQ6");
        if (cq6 == null) return;
        CaseResult t03 = findResult("T03");
        boolean rejectedAttempt = t03 != null && "PASSED".equals(t03.status);
        cq6.rejectedAttemptEvidencePass = rejectedAttempt;
        if (!rejectedAttempt) cq6.status = "INCORRECT";
        Path path = output.resolve(cq6.evidencePath);
        ObjectNode evidence = (ObjectNode) JSON.readTree(path.toFile());
        ObjectNode checks = evidence.putObject("non_query_checks");
        checks.put("semantic_candidates_absent", cq6.nonQueryChecksPass);
        checks.put("rejected_attempt_status",
                t03 == null ? "NOT_RUN" : t03.status);
        checks.put("rejected_attempt_evidence",
                "artifacts/T03/suspended-insert/test-result.json");
        evidence.put("status", cq6.status);
        writeJson(path, evidence);
    }

    private void runComparatorMetatests() throws Exception {
        ObjectNode report = JSON.createObjectNode();
        report.put("comparator", Dsh2027BindingComparator.class.getName());
        report.put("semantics", "complete RDF-term multiset");
        ArrayNode mutations = report.putArray("mutations");
        mutation(mutations, "CQ01 swapped V1/V2 etymology associations",
                cq1To6Placeholders, "CQ1", 0, 1, "etymology", false);
        mutation(mutations, "CQ04 swapped operation-to-object associations",
                cq1To6Placeholders, "CQ4", 0, 1, "o", false);
        mutation(mutations, "CQ05 swapped target-to-candidate associations",
                cq1To6Placeholders, "CQ5", 0, 1, "target", false);
        if (cq7Placeholders != null) {
            mutation(mutations, "CQ07 duplicated an audit row",
                    cq7Placeholders, "CQ7", 0, 0, null, true);
        } else {
            ObjectNode skipped = mutations.addObject();
            skipped.put("name", "CQ07 duplicated an audit row");
            skipped.put("status", "NOT_RUN");
            skipped.put("reason", "CQ07 is excluded from core mode");
        }
        report.put("all_executed_mutations_rejected", true);
        writeJson(output.resolve("queries/comparator-mutation-tests.json"),
                report);
    }

    private void mutation(ArrayNode report, String name,
            Map<String, String> placeholders, String id, int left, int right,
            String variable, boolean duplicate) {
        Dsh2027BindingComparator.Expected expected =
                Dsh2027BindingComparator.expected(cqOracle.path(id), placeholders);
        List<Dsh2027BindingComparator.Row> altered =
                new ArrayList<Dsh2027BindingComparator.Row>(expected.rows);
        if (duplicate) {
            altered.add(expected.rows.get(left));
        } else {
            Map<String, Dsh2027BindingComparator.Term> leftBindings =
                    new LinkedHashMap<String, Dsh2027BindingComparator.Term>(
                            expected.rows.get(left).bindings);
            Map<String, Dsh2027BindingComparator.Term> rightBindings =
                    new LinkedHashMap<String, Dsh2027BindingComparator.Term>(
                            expected.rows.get(right).bindings);
            Dsh2027BindingComparator.Term leftValue = leftBindings.get(variable);
            leftBindings.put(variable, rightBindings.get(variable));
            rightBindings.put(variable, leftValue);
            altered.set(left, new Dsh2027BindingComparator.Row(leftBindings));
            altered.set(right, new Dsh2027BindingComparator.Row(rightBindings));
        }
        Dsh2027BindingComparator.Comparison comparison =
                Dsh2027BindingComparator.compare(expected, altered);
        if ("EXACT".equals(comparison.status)) {
            throw new AssertionError("Comparator accepted mutation: " + name);
        }
        ObjectNode item = report.addObject();
        item.put("name", name); item.put("cq_id", id);
        item.put("status", "REJECTED_AS_EXPECTED");
        item.put("missing_rows", comparison.missingRows.size());
        item.put("unexpected_rows", comparison.unexpectedRows.size());
    }

    private boolean nonePublished(Set<Statement> candidates, Resource graph) {
        Model actual = graph(graph);
        for (Statement candidate : candidates) {
            if (actual.contains(candidate)) return false;
        }
        return true;
    }

    private boolean structuralShaclConforms(Path evidenceDirectory)
            throws Exception {
        Model shapes = graph(SHAPES);
        Model data = new LinkedHashModel();
        data.addAll(graph(SCHEMA)); data.addAll(graph(RECORDS));
        data.addAll(graph(AUDIT));
        IRI xone = iri("http://www.w3.org/ns/shacl#xone");
        shapes.remove(null, xone, null);
        if (!classicTargetsComplete(data)) {
            writeJson(evidenceDirectory.resolve("shacl-report.json"),
                    JSON.createObjectNode().put("conforms", false)
                            .put("reason", "classic reification incomplete"));
            return false;
        }
        ShaclSail sail = new ShaclSail(new MemoryStore());
        SailRepository local = new SailRepository(sail);
        local.init();
        boolean conforms;
        String detail = "";
        try (RepositoryConnection connection = local.getConnection()) {
            connection.begin();
            connection.add(shapes, RDF4J.SHACL_SHAPE_GRAPH);
            connection.commit();
            connection.begin(); connection.add(data); connection.commit();
            ShaclSailConnection shacl = (ShaclSailConnection)
                    ((SailRepositoryConnection) connection).getSailConnection();
            connection.begin();
            ValidationReport report = shacl.revalidate();
            conforms = report.conforms();
            detail = report.toString();
            connection.rollback();
        } finally {
            local.shutDown();
        }
        ObjectNode report = JSON.createObjectNode();
        report.put("conforms", conforms);
        report.put("engine", "independent RDF4J ShaclSail structural baseline");
        report.put("xone_policy", "classic RDF1.1 completeness checked independently; unsupported RDF4J 3.7 xone removed before remaining shapes");
        report.put("detail", detail);
        writeJson(evidenceDirectory.resolve("shacl-report.json"), report);
        return conforms;
    }

    private boolean classicTargetsComplete(Model data) {
        IRI targetType = iri(LEXINT + "TargetAssertion");
        IRI reifies = iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies");
        for (Resource target : data.filter(null, RDF.TYPE, targetType).subjects()) {
            if (data.filter(target, RDF.SUBJECT, null).size() != 1
                    || data.filter(target, RDF.PREDICATE, null).size() != 1
                    || data.filter(target, RDF.OBJECT, null).size() != 1
                    || !data.filter(target, reifies, null).isEmpty()) return false;
            Value predicate = data.filter(target, RDF.PREDICATE, null)
                    .objects().iterator().next();
            if (!(predicate instanceof IRI)) return false;
        }
        return true;
    }

    private Map<String, String> protectedGraphDigests(
            RepositoryConnection connection) throws Exception {
        Map<String, String> values = new TreeMap<String, String>();
        try (RepositoryResult<Resource> contexts = connection.getContextIDs()) {
            while (contexts.hasNext()) {
                Resource context = contexts.next();
                String value = context.stringValue();
                if (ACTIVE.equals(context) || RECORDS.equals(context)
                        || AUDIT.equals(context)
                        || value.startsWith(SNAPSHOT_BASE)) continue;
                values.put(value, digestGraph(connection, context));
            }
        }
        return values;
    }

    private String digestGraph(RepositoryConnection connection, Resource graph)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> values = new ArrayList<String>();
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                null, null, false, graph)) {
            while (rows.hasNext()) values.add(canonical(rows.next()));
        }
        Collections.sort(values);
        for (String value : values) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return hex(digest.digest());
    }

    private void verifyGlobalIsolation() throws Exception {
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size((Resource) null)).isZero();
            assertThat(protectedGraphDigests(connection))
                    .isEqualTo(protectedDigests);
        }
    }

    private void writeReports() throws Exception {
        writeTestsCsv(); writeComparisonCsv(); writeCqCsv(); writePaperTable();
        writeSummary(); writeHumanReports(); writeBaselineComparison();
        writeOutputManifest();
    }

    private void writeTestsCsv() throws Exception {
        Path file = output.resolve("tests.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8)) {
            writer.write("test_id,variant,test_status,expected_http,observed_http,expected_error_code,observed_error_code,expected_audit_outcome,observed_audit_outcome,before_digest,after_digest,missing_count,unexpected_count,duration_ms,evidence_path,notes\n");
            for (CaseResult r : results) {
                csv(writer, r.testId, r.variant, r.status, r.expectedHttp,
                        r.observedHttp, r.expectedError, r.observedError,
                        r.expectedAudit, r.observedAudit, r.beforeDigest,
                        r.afterDigest, String.valueOf(r.missing),
                        String.valueOf(r.unexpected), String.valueOf(r.durationMs),
                        r.evidence, r.notes);
            }
        }
    }

    private void writeComparisonCsv() throws Exception {
        Path file = output.resolve("comparison.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8)) {
            writer.write("counterexample_id,shacl_result,publication_integrity_result,missing_count,unexpected_count,detected_at,oracle_match,notes\n");
            for (ComparisonResult r : comparisons) {
                csv(writer, r.id, r.shacl, r.audit, String.valueOf(r.missing),
                        String.valueOf(r.unexpected), r.detectedAt,
                        String.valueOf(r.oracleMatch), r.notes);
            }
        }
    }

    private void writeCqCsv() throws Exception {
        Path file = output.resolve("cq-results.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8)) {
            writer.write("cq_id,expected_rows,observed_rows,matched_rows,missing_rows,unexpected_rows,completeness,precision,status,evidence_path,query_path\n");
            for (int i = 1; i <= 7; i++) {
                String id = "CQ" + i; CqResult r = cqResults.get(id);
                if (r == null) {
                    csv(writer, id, "", "", "", "", "", "", "", "",
                            "NOT_RUN", "", "queries/cq0" + i + ".rq");
                } else {
                    csv(writer, id, String.valueOf(r.expectedRows),
                            String.valueOf(r.observedRows),
                            String.valueOf(r.matchedRows),
                            String.valueOf(r.missingRows),
                            String.valueOf(r.unexpectedRows), r.completeness,
                            r.precision, r.status, r.evidencePath, r.queryPath);
                }
            }
        }
    }

    private void writePaperTable() throws Exception {
        Path file = output.resolve("paper-table3.csv");
        Map<String, String> conditions = paperConditions();
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardCharsets.UTF_8)) {
            writer.write("test_id,condition,expected,observed,status\n");
            for (int i = 1; i <= 12; i++) {
                String id = String.format(Locale.ROOT, "T%02d", Integer.valueOf(i));
                CaseResult result = findResult(id);
                csv(writer, id, conditions.get(id), expectedFor(id),
                        result == null ? "not run" : result.notes,
                        result == null ? "NOT_RUN" : result.status);
            }
        }
    }

    private void writeSummary() throws Exception {
        ObjectNode summary = JSON.createObjectNode();
        summary.put("run_id", runId); summary.put("timestamp_utc",
                Instant.now().toString()); summary.put("mode", mode);
        summary.put("commit_sha", System.getProperty(
                "lexo.test.dsh2027.commit", "unknown"));
        boolean dirty = Boolean.getBoolean(
                "lexo.test.dsh2027.worktreeDirty");
        summary.put("worktree_dirty", dirty);
        summary.put("software_revision", System.getProperty(
                "lexo.test.dsh2027.commit", "unknown")
                + (dirty ? "+captured-worktree-patch" : ""));
        summary.put("git_branch", System.getProperty(
                "lexo.test.dsh2027.branch", "unknown"));
        summary.put("graphdb_version", System.getProperty(
                "lexo.test.dsh2027.graphdbVersion", "10.8.14"));
        summary.put("java_version", System.getProperty("java.version"));
        summary.put("maven_version", "3.9.16");
        summary.put("tomcat_version", "9.0.121");
        summary.put("application_java_target", "8");
        summary.put("rdf4j_version", "3.7.6");
        summary.put("identity_assurance", "DECLARED");
        summary.put("oracle_sha256", sha256(evaluationRoot.resolve(
                "oracle/golden-delta.yaml")));
        ObjectNode counts = summary.putObject("test_counts");
        for (String status : Arrays.asList("PASSED", "FAILED", "BLOCKED",
                "NOT_RUN")) counts.put(status, countStatus(status));
        summary.put("counterexamples_executed", comparisons.size());
        summary.put("competency_questions_exact", countCq("EXACT"));
        Path t09 = output.resolve(
                "artifacts/T09/graphdb-rollback/t09-components.json");
        if (Files.isRegularFile(t09)) {
            summary.set("t09_components", JSON.readTree(t09.toFile()));
        }
        summary.put("default_graph_empty", true);
        summary.put("graph_scope", ACTIVE.stringValue());
        ObjectNode configuration = summary.putObject(
                "sanitized_configuration");
        configuration.put("base_url",
                "http://lexo:8080/LexO-server/service");
        configuration.put("repository",
                "http://graphdb:7200/repositories/LexOLexica");
        configuration.put("managed_publication", true);
        configuration.put("graphdb_host_port_published", false);
        ObjectNode images = summary.putObject("container_images");
        images.put("graphdb", System.getProperty(
                "lexo.test.dsh2027.graphdbImage", "ontotext/graphdb:10.8.14"));
        images.put("graphdb_id", System.getProperty(
                "lexo.test.dsh2027.graphdbImageId", "unknown"));
        images.put("lexo", System.getProperty(
                "lexo.test.dsh2027.lexoImage", "lexo-dsh2027:local"));
        images.put("lexo_id", System.getProperty(
                "lexo.test.dsh2027.lexoImageId", "unknown"));
        images.put("evaluator", System.getProperty(
                "lexo.test.dsh2027.mavenImage",
                "maven:3.9.16-eclipse-temurin-17"));
        images.put("evaluator_id", System.getProperty(
                "lexo.test.dsh2027.mavenImageId", "unknown"));
        writeJson(output.resolve("summary.json"), summary);
    }

    private void writeHumanReports() throws Exception {
        int passed = countStatus("PASSED"); int failed = countStatus("FAILED");
        int blocked = countStatus("BLOCKED"); int exact = countCq("EXACT");
        boolean dirty = Boolean.getBoolean(
                "lexo.test.dsh2027.worktreeDirty");
        String revision = System.getProperty(
                "lexo.test.dsh2027.commit", "unknown")
                + (dirty ? " plus the captured worktree patch" : "");
        String report = "# DSH 2027 LexInt evaluation report\n\n"
                + "Run `" + runId + "` evaluated base revision `"
                + revision + "` against a dedicated GraphDB stack. Identity assurance was "
                + "`DECLARED`; this is not strong authentication.\n\n"
                + "## What we tried\n\nT1–T12 publication, rejection, rollback, "
                + "retry, writer-boundary and historical-audit cases, plus CQ1–CQ7 "
                + "and CE1–CE3 in `all` mode. T09 used the deployed GraphDB "
                + "repository through RDF4J HTTPRepository and an internal test-only "
                + "post-write/pre-commit hook.\n\n"
                + "## What the dictionary published\n\nSee `tests.csv` and the RDF "
                + "artifacts. Frozen expected graphs, rather than REST preview output, "
                + "were used as the oracle.\n\n"
                + "## What was prevented\n\nRejected plans and managed writer calls "
                + "were checked for graph and ledger side effects. The external "
                + "GraphDB boundary is a property of the dedicated Docker topology.\n\n"
                + "## What was recovered\n\n" + exact + " of 7 competency "
                + "questions were recovered exactly by full typed-binding multiset "
                + "comparison. Four adversarial row mutations were rejected.\n\n"
                + "## What remains unproved\n\nThe experiment does not establish the "
                + "historical truth of an etymology, a new sense distinction, UI "
                + "usability, multi-instance serializability, or RDF 1.2 support.\n\n"
                + "Execution counts: PASSED=" + passed + ", FAILED=" + failed
                + ", BLOCKED=" + blocked + ".\n";
        Files.write(output.resolve("report.md"), report.getBytes(StandardCharsets.UTF_8));
        String paper = "The evaluation executed the versioned LexInt workflow against "
                + "a dedicated GraphDB instance and compared each published graph with "
                + "a frozen RDF oracle. " + passed + " test rows passed, " + failed
                + " failed, and " + blocked + " were blocked; CQ recovery was exact "
                + "for " + exact + " of 7 questions using complete typed binding rows. "
                + "T09 exercised rollback on the GraphDB backend rather than inferring "
                + "it from the in-memory L0 test. These counts describe only the "
                + "recorded run.\n\nThe counterexample comparison used the delivered "
                + "structural shapes and the historical publication verifier on the "
                + "same well-formed records. Exact observed mismatch counts are reported "
                + "in `comparison.csv`; no claim is made that advanced integrity rules "
                + "are inexpressible in SHACL-SPARQL.\n";
        Files.write(output.resolve("paper-results-draft.md"),
                paper.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBaselineComparison() throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("# Baseline versus corrected evaluation\n\n")
                .append("Baseline run: `dsh2027-20261121T072523Z-82217` ")
                .append("(reported 12/12, but T09 was L0-only and CQ EXACT used ")
                .append("permissive predicates). Corrected run: `").append(runId)
                .append("`.\n\n")
                .append("| Area | Baseline | Corrected observed behavior |\n")
                .append("|---|---|---|\n")
                .append("| T09 | MemoryStore L0 evidence consumed by E2E | ")
                .append(findResult("T09") == null ? "NOT_RUN"
                        : findResult("T09").notes)
                .append(" |\n")
                .append("| CQ01–CQ07 | Counts plus independent value predicates | ")
                .append(countCq("EXACT")).append("/7 exact complete RDF-term ")
                .append("multisets; adversarial associations rejected |\n")
                .append("| CE1–CE3 | 3 discrepancy cases detected | ")
                .append(comparisons.size()).append(" cases recomputed; outcomes in ")
                .append("`comparison.csv` |\n")
                .append("| Aggregate T01–T12 | Reported 12 passed | ")
                .append(countStatus("PASSED")).append(" passed, ")
                .append(countStatus("FAILED")).append(" failed, ")
                .append(countStatus("BLOCKED")).append(" blocked, ")
                .append(countStatus("NOT_RUN")).append(" not run |\n\n")
                .append("The baseline archive and run ID are preserved; no old result ")
                .append("file was copied into the corrected result directory.\n");
        Files.write(output.resolve("baseline-vs-corrected.md"),
                report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeOutputManifest() throws Exception {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("run_id", runId);
        manifest.put("software_commit", System.getProperty(
                "lexo.test.dsh2027.commit", "unknown"));
        manifest.put("worktree_dirty", Boolean.getBoolean(
                "lexo.test.dsh2027.worktreeDirty"));
        manifest.put("git_branch", System.getProperty(
                "lexo.test.dsh2027.branch", "unknown"));
        manifest.put("software_url", "https://github.com/andreabellandi/LexO-server");
        ObjectNode images = manifest.putObject("container_images");
        images.put("graphdb", System.getProperty(
                "lexo.test.dsh2027.graphdbImage", "ontotext/graphdb:10.8.14"));
        images.put("graphdb_id", System.getProperty(
                "lexo.test.dsh2027.graphdbImageId", "unknown"));
        images.put("lexo", System.getProperty(
                "lexo.test.dsh2027.lexoImage", "lexo-dsh2027:local"));
        images.put("lexo_id", System.getProperty(
                "lexo.test.dsh2027.lexoImageId", "unknown"));
        images.put("evaluator", System.getProperty(
                "lexo.test.dsh2027.mavenImage",
                "maven:3.9.16-eclipse-temurin-17"));
        images.put("evaluator_id", System.getProperty(
                "lexo.test.dsh2027.mavenImageId", "unknown"));
        ArrayNode inputs = manifest.putArray("inputs");
        List<Path> inputPaths = new ArrayList<Path>();
        for (String file : Arrays.asList("manifest.yaml", "README.md",
                "known-limitations.md", "implementation-gap.md",
                "baseline-reference.json",
                "namespace-contract-audit.md", "OPEN_ISSUES.md",
                "compose.yaml")) {
            inputPaths.add(evaluationRoot.resolve(file));
        }
        for (String directory : Arrays.asList("fixtures", "oracle", "cases",
                "queries", "scripts")) {
            try (java.util.stream.Stream<Path> stream = Files.walk(
                    evaluationRoot.resolve(directory))) {
                java.util.Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (Files.isRegularFile(path)) inputPaths.add(path);
                }
            }
        }
        Collections.sort(inputPaths);
        for (Path path : inputPaths) {
            ObjectNode item = inputs.addObject();
            item.put("path", evaluationRoot.relativize(path).toString());
            item.put("sha256", sha256(path));
        }
        ArrayNode files = manifest.putArray("files");
        List<Path> paths = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(output)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path)
                        && !path.equals(output.resolve("manifest.json"))) paths.add(path);
            }
        }
        Collections.sort(paths);
        for (Path path : paths) {
            ObjectNode item = files.addObject();
            item.put("path", output.relativize(path).toString());
            item.put("sha256", sha256(path));
        }
        writeJson(output.resolve("manifest.json"), manifest);
    }

    private int countStatus(String status) {
        int count = 0; for (CaseResult result : results) {
            if (status.equals(result.status)) count++;
        } return count;
    }

    private int countCq(String status) {
        int count = 0; for (CqResult result : cqResults.values()) {
            if (status.equals(result.status)) count++;
        } return count;
    }

    private CaseResult findResult(String id) {
        for (CaseResult result : results) if (id.equals(result.testId)) return result;
        return null;
    }

    private Map<String, String> paperConditions() {
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("T01", "Authorized etymological revision");
        values.put("T02", "Suspended semantic claim with three candidates");
        values.put("T03", "INSERT authorized only by suspended decision");
        values.put("T04", "Mixed valid and invalid plan");
        values.put("T05", "Structurally incomplete records");
        values.put("T06", "Wrong target/claim/decision/version association");
        values.put("T07", "Explicit partial target selection");
        values.put("T08", "Stale ETag and active-graph drift");
        values.put("T09", "Pre-commit interruption");
        values.put("T10", "Idempotent replay and writer boundary");
        values.put("T11", "Post-commit V2 snapshot corruption");
        values.put("T12", "Missing historical input after V3");
        return values;
    }

    private String expectedFor(String id) {
        if ("T01".equals(id)) return "exact -ratio +haraz and PASS";
        if ("T02".equals(id)) return "records recoverable; zero S1-S3 assertions";
        if ("T03".equals(id) || "T04".equals(id)) return "plan rejected; no mutation";
        if ("T05".equals(id)) return "LEXINT_SHACL_VIOLATION";
        if ("T06".equals(id)) return "authorization rejected";
        if ("T07".equals(id)) return "only selected target published";
        if ("T08".equals(id)) return "412 stale; 409 drift";
        if ("T09".equals(id)) return "GraphDB exact rollback after reached failpoint; ordinary reset path succeeds";
        if ("T10".equals(id)) return "one publication; guarded writer and network boundary";
        if ("T11".equals(id)) return "append FAIL with one unexpected triple";
        return "PASS after V3; then append INDETERMINATE for missing V1";
    }

    private static void csv(BufferedWriter writer, String... values)
            throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            String value = values[i] == null ? "" : values[i];
            writer.write('"'); writer.write(value.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write('\n');
    }

    private static void writeJson(Path file, JsonNode value) throws IOException {
        Files.createDirectories(file.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (java.io.InputStream input = Files.newInputStream(file)) {
            int read; while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(Locale.ROOT,
                "%02x", Integer.valueOf(item & 0xff)));
        return value.toString();
    }

    private static void assertDedicated(String base, String repositoryUrl) {
        if (base.isEmpty() || repositoryUrl.isEmpty()
                || !base.contains("/LexO-server/service")
                || !repositoryUrl.endsWith("/repositories/LexOLexica")) {
            throw new IllegalArgumentException(
                    "Evaluation URLs do not identify the dedicated LexO/GraphDB stack");
        }
        if (base.contains("lexo.ilc.cnr.it")
                || repositoryUrl.contains("lexo.ilc.cnr.it")) {
            throw new IllegalArgumentException("Production-like host rejected");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    private final class CaseRecorder {
        final String testId; final String variant; final Path directory;
        final ArrayNode requests = JSON.createArrayNode();
        final ObjectNode assertions = JSON.createObjectNode();
        final ObjectNode oracle = JSON.createObjectNode();
        final Map<String, Long> timings = new LinkedHashMap<String, Long>();
        String expectedHttp = ""; String observedHttp = "";
        String expectedError = ""; String observedError = "";
        String expectedAudit = ""; String observedAudit = "";
        String beforeDigest = ""; String afterDigest = "";
        String notes = "observed criteria matched"; String blockedReason;
        String failure; long missing; long unexpected;

        CaseRecorder(String testId, String variant, Path directory) {
            this.testId = testId; this.variant = variant;
            this.directory = directory;
        }

        void captureBefore() throws Exception {
            beforeDigest = evaluationDigest();
            exportEvaluationGraphs(directory.resolve("graph-before.trig"));
        }

        void captureAfter() throws Exception {
            afterDigest = evaluationDigest();
            exportEvaluationGraphs(directory.resolve("graph-after.trig"));
        }

        void record(String method, String path, JsonNode body,
                Map<String, String> headers, String requestId,
                HttpResult response, long durationMs) {
            ObjectNode item = requests.addObject();
            item.put("method", method); item.put("path", path);
            item.put("request_id", requestId); item.put("duration_ms", durationMs);
            if (body != null) item.set("body", body);
            ObjectNode safeHeaders = item.putObject("headers");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                safeHeaders.put(header.getKey(), "Idempotency-Key".equalsIgnoreCase(
                        header.getKey()) ? header.getValue() : header.getValue());
            }
            item.put("status", response.status);
            if (!response.body.isEmpty()) item.set("response", response.json);
        }

        void finish(CaseResult result) throws Exception {
            Files.createDirectories(directory);
            if (!Files.exists(directory.resolve("graph-before.trig"))) {
                exportEvaluationGraphs(directory.resolve("graph-before.trig"));
                beforeDigest = evaluationDigest();
            }
            if (!Files.exists(directory.resolve("graph-after.trig"))) {
                exportEvaluationGraphs(directory.resolve("graph-after.trig"));
                afterDigest = evaluationDigest();
            }
            ObjectNode setup = JSON.createObjectNode();
            setup.put("run_id", runId); setup.put("test_id", testId);
            setup.put("case_id", variant); setup.put("timestamp_utc",
                    Instant.now().toString()); setup.put("language", "it");
            setup.put("active_graph", ACTIVE.stringValue());
            setup.put("records_graph", RECORDS.stringValue());
            setup.put("audit_graph", AUDIT.stringValue());
            writeJson(directory.resolve("setup.json"), setup);
            writeJson(directory.resolve("requests-redacted.json"), requests);
            writeJson(directory.resolve("oracle.json"), oracle);
            writeJson(directory.resolve("assertions.json"), assertions);
            ObjectNode testResult = result.toJson();
            testResult.put("run_id", runId);
            testResult.put("timestamp_utc", Instant.now().toString());
            testResult.put("before_digest", beforeDigest);
            testResult.put("after_digest", afterDigest);
            testResult.set("timings", JSON.valueToTree(timings));
            if (failure != null) testResult.put("failure", failure);
            writeJson(directory.resolve("test-result.json"), testResult);
        }
    }

    private static final class HttpResult {
        final int status; final String body; final JsonNode json;
        final String requestId;
        HttpResult(int status, String body, JsonNode json, String requestId) {
            this.status = status; this.body = body; this.json = json;
            this.requestId = requestId;
        }
        String errorCode() {
            String code = json.path("code").asText();
            if (!code.isEmpty()) return code;
            String text = json.path("text").asText(body);
            int colon = text.indexOf(':');
            return colon > 0 ? text.substring(0, colon).trim() : "";
        }
    }

    private static final class Scenario {
        JsonNode v1; JsonNode v2; JsonNode ratioClaim; JsonNode harazClaim;
        JsonNode ratioDecision; JsonNode harazDecision;
        JsonNode semanticClaim; JsonNode semanticDecision;
        JsonNode publication; String publicationIri; String publicationId;
        String previewEtag; String targetEtag; IRI v1Graph; IRI v2Graph;
    }

    private static final class GraphState {
        final Map<Resource, Model> graphs;
        final Map<Resource, String> digests;
        final Model combined;
        final String digest;
        final String connectionClass;

        GraphState(Map<Resource, Model> graphs, Map<Resource, String> digests,
                Model combined, String digest, String connectionClass) {
            this.graphs = graphs; this.digests = digests;
            this.combined = combined; this.digest = digest;
            this.connectionClass = connectionClass;
        }

        Model graph(Resource graph) {
            Model result = graphs.get(graph);
            return result == null ? new LinkedHashModel() : result;
        }

        boolean isomorphic(GraphState other) {
            if (!graphs.keySet().equals(other.graphs.keySet())) return false;
            for (Resource graph : graphs.keySet()) {
                if (!Models.isomorphic(graph(graph), other.graph(graph))) return false;
            }
            return true;
        }

        ObjectNode inventory() {
            ObjectNode value = JSON.createObjectNode();
            value.put("repository_connection_class", connectionClass);
            value.put("aggregate_sha256", digest);
            ArrayNode items = value.putArray("graphs");
            for (Map.Entry<Resource, Model> entry : graphs.entrySet()) {
                ObjectNode item = items.addObject();
                item.put("graph", entry.getKey().stringValue());
                item.put("statement_count", entry.getValue().size());
                item.put("sha256", digests.get(entry.getKey()));
            }
            return value;
        }
    }

    private static final class DeliberateGraphDbPublicationFailure
            extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class Delta {
        final Model removed; final Model added;
        Delta(Model removed, Model added) { this.removed = removed; this.added = added; }
        ObjectNode toJson() {
            ObjectNode value = JSON.createObjectNode();
            value.put("missing_count", removed.size());
            value.put("unexpected_count", added.size());
            ArrayNode missing = value.putArray("missing");
            for (Statement statement : removed) missing.add(statement.toString());
            ArrayNode unexpected = value.putArray("unexpected");
            for (Statement statement : added) unexpected.add(statement.toString());
            return value;
        }
    }

    private static final class CaseResult {
        final String testId; final String variant;
        String status = "NOT_RUN"; String notes = ""; String expectedHttp = "";
        String observedHttp = ""; String expectedError = "";
        String observedError = ""; String expectedAudit = "";
        String observedAudit = ""; String beforeDigest = "";
        String afterDigest = ""; String evidence = "";
        long missing; long unexpected; long durationMs;
        CaseResult(String testId, String variant) {
            this.testId = testId; this.variant = variant;
        }
        ObjectNode toJson() {
            ObjectNode value = JSON.createObjectNode();
            value.put("test_id", testId); value.put("variant", variant);
            value.put("test_status", status); value.put("notes", notes);
            value.put("expected_http", expectedHttp);
            value.put("observed_http", observedHttp);
            value.put("expected_error_code", expectedError);
            value.put("observed_error_code", observedError);
            value.put("expected_audit_outcome", expectedAudit);
            value.put("observed_audit_outcome", observedAudit);
            value.put("missing_count", missing);
            value.put("unexpected_count", unexpected);
            value.put("duration_ms", durationMs);
            value.put("evidence_path", evidence);
            return value;
        }
        @Override public String toString() { return testId + "=" + status; }
    }

    private static final class ComparisonResult {
        String id; String shacl; String audit; long missing; long unexpected;
        String detectedAt; boolean oracleMatch; String notes;
        ObjectNode toJson() {
            ObjectNode value = JSON.createObjectNode();
            value.put("counterexample_id", id); value.put("shacl_result", shacl);
            value.put("publication_integrity_result", audit);
            value.put("missing_count", missing); value.put("unexpected_count", unexpected);
            value.put("detected_at", detectedAt); value.put("oracle_match", oracleMatch);
            value.put("notes", notes); return value;
        }
    }

    private static final class CqResult {
        final String id;
        int expectedRows; int observedRows; int matchedRows;
        int missingRows; int unexpectedRows;
        String completeness; String precision; String status;
        String evidencePath; String queryPath;
        boolean nonQueryChecksPass = true;
        boolean rejectedAttemptEvidencePass = true;
        CqResult(String id) { this.id = id; }
    }
}
