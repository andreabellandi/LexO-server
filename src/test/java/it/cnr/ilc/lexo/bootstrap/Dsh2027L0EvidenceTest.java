package it.cnr.ilc.lexo.bootstrap;

import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.TARGET_ASSERTION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.LexIntManager;
import it.cnr.ilc.lexo.manager.LexIntPublicationManager;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.lexint.LexIntActor;
import it.cnr.ilc.lexo.manager.lexint.LexIntException;
import it.cnr.ilc.lexo.manager.lexint.LexIntMetadataWriter;
import it.cnr.ilc.lexo.manager.lexint.LexIntRepository;
import it.cnr.ilc.lexo.manager.lexint.LexIntResourceUris;
import it.cnr.ilc.lexo.manager.lexint.LexIntShaclValidator;
import it.cnr.ilc.lexo.manager.lexint.PublicationFaultInjector;
import it.cnr.ilc.lexo.service.data.lexint.input.ClaimCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.DecisionCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationOperationInput;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.TargetInput;
import it.cnr.ilc.lexo.service.data.lexint.input.TargetObjectInput;
import it.cnr.ilc.lexo.service.data.lexint.output.ClaimDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.DecisionDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.PublicationPreview;
import it.cnr.ilc.lexo.service.data.lexint.output.VersionDetails;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/** Produces measured L0 evidence consumed by the remote DSH2027 runner. */
class Dsh2027L0EvidenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    @Test
    void realShaclAndPrecommitFailpointProduceMeasuredEvidence() throws Exception {
        String enabled = LexOProperties.getProperty("LexInt.enabled");
        String managed = LexOProperties.getProperty(
                "LexInt.managedPublication.enabled");
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        boolean shaclReached = false;
        boolean failpointReached = false;
        boolean rollbackComplete = false;
        try {
            LexOProperties.setProperty("LexInt.enabled", "true");
            LexOProperties.setProperty("LexInt.managedPublication.enabled", "false");
            GraphDbBootstrap.importSchema(repository);
            GraphDbBootstrap.importLexIntShapes(repository);
            IRI recordsGraph = iri(LexOProperties.getProperty(
                    "GraphDb.lexintRecordsNamedGraph"));
            LexIntActor actor = new LexIntActor("dsh-test-editor", "DECLARED",
                    false, null, null, Collections.<String>emptySet());
            try (RepositoryConnection connection = repository.getConnection()) {
                IRI target = iri("https://example.org/dsh2027/incomplete-target");
                connection.add(target, RDF.TYPE, TARGET_ASSERTION, recordsGraph);
                connection.add(target, RDF.SUBJECT,
                        iri("https://example.org/dsh2027/razza"), recordsGraph);
                connection.add(target, RDF.PREDICATE,
                        iri("https://example.org/dsh2027/predicate"), recordsGraph);
                new LexIntMetadataWriter().createIndividual(connection, target,
                        actor, recordsGraph);
                LexIntShaclValidator validator = new LexIntShaclValidator(
                        new LexIntRepository(repository));
                try {
                    validator.validate(connection, Collections.singletonList(target));
                } catch (LexIntException failure) {
                    assertThat(failure.code).isEqualTo("LEXINT_SHACL_VIOLATION");
                    shaclReached = true;
                }
                connection.clear(recordsGraph);
            }

            LexIntManager records = new LexIntManager(repository);
            LexIntPublicationManager publications =
                    new LexIntPublicationManager(repository);
            IRI active = iri(LexiconCrudSupport.lexicalGraphUri("it"));
            IRI stableSubject = iri("https://example.org/dsh2027/stable");
            IRI stablePredicate = iri("https://example.org/dsh2027/status");
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.add(stableSubject, stablePredicate,
                        VF.createLiteral("before"), active);
            }
            LexIntPublicationManager.VersionCreation baseline =
                    publications.baseline("it", actor);
            VersionDetails draft = publications.reserveDraft("it", actor);
            ClaimDetails claim = records.createClaim(claim(), actor);
            DecisionDetails decision = records.createDecision(
                    decision(claim.iri, draft.iri), actor);
            decision = records.finalizeDecision(decision.id, actor);
            PublicationRequest request = publication(baseline.version.iri,
                    draft.iri, claim.targets.get(0).iri, decision.iri);
            PublicationPreview preview = publications.preview(request, actor);
            IRI auditGraph = iri(LexOProperties.getProperty(
                    "GraphDb.lexintAuditNamedGraph"));
            IRI targetSnapshot = iri(LexOProperties.getProperty(
                    "GraphDb.lexintSnapshotsBase") + "it/" + draft.versionCode);
            long auditSize; long activeSize;
            try (RepositoryConnection connection = repository.getConnection()) {
                auditSize = connection.size(auditGraph);
                activeSize = connection.size(active);
            }
            final AtomicBoolean reached = new AtomicBoolean(false);
            LexIntPublicationManager interrupted = new LexIntPublicationManager(
                    new LexIntRepository(repository), new LexIntResourceUris(),
                    new LexIntMetadataWriter(), new PublicationFaultInjector() {
                        @Override public void afterProvisionalWrites() {
                            reached.set(true); throw new DeliberateFailure();
                        }
                    });
            assertThatThrownBy(() -> interrupted.publish(request, actor,
                    preview.baseEtag, "dsh-l0-failpoint", "dsh-l0-request"))
                    .isInstanceOf(DeliberateFailure.class);
            failpointReached = reached.get();
            try (RepositoryConnection connection = repository.getConnection()) {
                rollbackComplete = connection.size(auditGraph) == auditSize
                        && connection.size(active) == activeSize
                        && connection.size(targetSnapshot) == 0L
                        && connection.hasStatement(stableSubject, stablePredicate,
                                VF.createLiteral("before"), false, active)
                        && connection.size((Resource) null) == 0L;
            }
            assertThat(shaclReached).isTrue();
            assertThat(failpointReached).isTrue();
            assertThat(rollbackComplete).isTrue();
        } finally {
            LexOProperties.setProperty("LexInt.enabled", enabled);
            LexOProperties.setProperty("LexInt.managedPublication.enabled", managed);
            repository.shutDown();
        }
        String evidencePath = System.getProperty(
                "lexo.test.dsh2027.l0Evidence", "").trim();
        if (!evidencePath.isEmpty()) {
            Path path = Paths.get(evidencePath).toAbsolutePath().normalize();
            Files.createDirectories(path.getParent());
            ObjectNode evidence = JSON.createObjectNode();
            evidence.put("timestamp_utc", Instant.now().toString());
            evidence.put("real_shacl_validator_reached", shaclReached);
            evidence.put("shacl_error_code", "LEXINT_SHACL_VIOLATION");
            evidence.put("failpoint_reached", failpointReached);
            evidence.put("rollback_complete", rollbackComplete);
            evidence.put("fault_injection_channel", "in-process test-only interface");
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), evidence);
        }
    }

    private ClaimCreateRequest claim() {
        TargetObjectInput object = new TargetObjectInput();
        object.kind = "LITERAL"; object.value = "candidate"; object.language = "it";
        TargetInput target = new TargetInput();
        target.subjectIri = "https://example.org/dsh2027/entry";
        target.predicateIri = "https://example.org/dsh2027/predicate";
        target.object = object;
        ClaimCreateRequest claim = new ClaimCreateRequest();
        claim.description = "DSH L0 rollback claim";
        claim.targets = Arrays.asList(target);
        return claim;
    }

    private DecisionCreateRequest decision(String claim, String version) {
        DecisionCreateRequest request = new DecisionCreateRequest();
        request.claimIri = claim; request.versionIri = version;
        request.outcome = "adopted"; request.description = "DSH L0 decision";
        return request;
    }

    private PublicationRequest publication(String source, String target,
            String targetAssertion, String decision) {
        PublicationOperationInput operation = new PublicationOperationInput();
        operation.action = "INSERT";
        operation.targetAssertionIri = targetAssertion;
        operation.decisionIri = decision;
        PublicationRequest request = new PublicationRequest();
        request.language = "it"; request.sourceVersionIri = source;
        request.targetVersionIri = target;
        request.operations = Arrays.asList(operation);
        return request;
    }

    private static IRI iri(String value) { return VF.createIRI(value); }

    private static final class DeliberateFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
