package it.cnr.ilc.lexo.bootstrap;

import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.LexIntManager;
import it.cnr.ilc.lexo.manager.LexIntPublicationManager;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.lexint.LexIntActor;
import it.cnr.ilc.lexo.manager.lexint.LexIntException;
import it.cnr.ilc.lexo.manager.lexint.LexIntGraphGuard;
import it.cnr.ilc.lexo.manager.lexint.LexIntMetadataWriter;
import it.cnr.ilc.lexo.manager.lexint.LexIntRepository;
import it.cnr.ilc.lexo.manager.lexint.LexIntShaclValidator;
import it.cnr.ilc.lexo.service.data.lexint.input.ClaimCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.ClaimPatchRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.DecisionCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.DecisionPatchRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationOperationInput;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.TargetInput;
import it.cnr.ilc.lexo.service.data.lexint.input.TargetObjectInput;
import it.cnr.ilc.lexo.service.data.lexint.output.AuditReportDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.ClaimDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.DecisionDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.PublicationDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.PublicationPreview;
import it.cnr.ilc.lexo.service.data.lexint.output.TargetDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.VersionDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexIntWorkflowTest {
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexIntManager records;
    private LexIntPublicationManager publications;
    private LexIntActor actor;
    private String enabled;
    private String managed;

    @BeforeEach
    void setUp() {
        enabled = LexOProperties.getProperty("LexInt.enabled");
        managed = LexOProperties.getProperty(
                "LexInt.managedPublication.enabled");
        LexOProperties.setProperty("LexInt.enabled", "true");
        LexOProperties.setProperty("LexInt.managedPublication.enabled", "false");
        repository = new SailRepository(new MemoryStore());
        repository.init();
        GraphDbBootstrap.importSchema(repository);
        GraphDbBootstrap.importLexIntShapes(repository);
        records = new LexIntManager(repository);
        publications = new LexIntPublicationManager(repository);
        actor = new LexIntActor("editor", "DECLARED", false, null, null,
                Collections.<String>emptySet());
    }

    @AfterEach
    void tearDown() {
        LexOProperties.setProperty("LexInt.enabled", enabled);
        LexOProperties.setProperty("LexInt.managedPublication.enabled", managed);
        repository.shutDown();
    }

    @Test
    void publishesExplicitTargetIdempotentlyAndAuditsHistoricalSnapshots() {
        IRI active = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI oldSubject = iri("https://example.org/entry/old");
        IRI oldPredicate = iri("https://example.org/vocabulary/status");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(oldSubject, oldPredicate,
                    vf.createLiteral("existing"), active);
        }

        LexIntPublicationManager.VersionCreation baseline =
                publications.baseline("IT", actor);
        VersionDetails draft = publications.reserveDraft("it", actor);
        ClaimDetails claim = records.createClaim(claim(), actor);
        DecisionDetails decision = records.createDecision(
                decision(claim.iri, draft.iri), actor);
        decision = records.finalizeDecision(decision.id, actor);
        TargetDetails selected = claim.targets.stream().filter(item ->
                "https://example.org/entry/casa".equals(item.subjectIri))
                .findFirst().get();
        PublicationRequest request = publication(baseline.version.iri,
                draft.iri, selected.iri, decision.iri);

        long beforePreview;
        try (RepositoryConnection connection = repository.getConnection()) {
            beforePreview = connection.size();
        }
        PublicationPreview preview = publications.preview(request, actor);
        assertThat(preview.baseEtag).isEqualTo(baseline.version.etag);
        assertThat(preview.expectedInsertions).isEqualTo(1);
        assertThat(preview.expectedDeletions).isZero();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size()).isEqualTo(beforePreview);
        }

        long beforeRejectedPublish;
        try (RepositoryConnection connection = repository.getConnection()) {
            beforeRejectedPublish = connection.size();
        }
        assertThatThrownBy(() -> publications.publish(request, actor,
                "\"stale\"", "wrong-etag", "request-rejected"))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(412));
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size()).isEqualTo(beforeRejectedPublish);
        }

        PublicationDetails first = publications.publish(request, actor,
                preview.baseEtag, "workflow-1", "request-1");
        assertThat(first.replayed).isFalse();
        assertThat(first.auditStatus).isEqualTo("PASS");
        assertThat(first.username).isEqualTo("editor");
        assertThat(publications.getVersion(draft.id).state).isEqualTo("CURRENT");
        assertThat(publications.getVersion(draft.id).audit).hasSize(1);
        assertThat(publications.getVersion(baseline.version.id).state)
                .isEqualTo("PUBLISHED");
        LexIntPublicationManager.VersionCreation baselineReplay =
                publications.baseline("it", actor);
        assertThat(baselineReplay.replayed).isTrue();
        assertThat(baselineReplay.version.iri)
                .isEqualTo(baseline.version.iri);
        assertThat(baselineReplay.version.versionCode).isEqualTo("v000001");

        long publishedSize;
        try (RepositoryConnection connection = repository.getConnection()) {
            publishedSize = connection.size();
            assertThat(connection.hasStatement(
                    iri("https://example.org/entry/casa"),
                    iri("https://example.org/vocabulary/interpretation"),
                    vf.createLiteral("abitazione", "it"), false, active))
                    .isTrue();
            assertThat(connection.size((Resource) null)).isZero();
            assertRuntimeIndividualsHaveMetadata(connection);
        }

        PublicationDetails replay = publications.publish(request, actor,
                preview.baseEtag, "workflow-1", "request-2");
        assertThat(replay.replayed).isTrue();
        assertThat(replay.iri).isEqualTo(first.iri);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size()).isEqualTo(publishedSize);
        }

        LexIntActor differentPrincipal = new LexIntActor("editor",
                "AUTHENTICATED", true, "https://issuer.example/realm",
                "different-subject", Collections.<String>emptySet());
        assertThatThrownBy(() -> publications.publish(request,
                differentPrincipal, preview.baseEtag, "workflow-1",
                "request-other-principal"))
                .as("idempotency keys are scoped to the stable principal, not the username")
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(412));

        PublicationRequest conflicting = publication(baseline.version.iri,
                draft.iri, claim.targets.get(0).iri, decision.iri);
        conflicting.operations.get(0).action = "DELETE";
        assertThatThrownBy(() -> publications.publish(conflicting, actor,
                preview.baseEtag, "workflow-1", "request-3"))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.code)
                                .isEqualTo("LEXINT_IDEMPOTENCY_CONFLICT"));

        AuditReportDetails second = publications.verify(first.id, false, actor);
        assertThat(second.outcome).isEqualTo("PASS");
        assertThat(publications.auditHistory(first.id)).hasSize(2);

        VersionDetails third = publications.reserveDraft("it", actor);
        ClaimCreateRequest nextClaim = claim();
        nextClaim.targets.get(0).subjectIri =
                "https://example.org/entry/seconda";
        ClaimDetails next = records.createClaim(nextClaim, actor);
        DecisionDetails nextDecision = records.createDecision(
                decision(next.iri, third.iri), actor);
        nextDecision = records.finalizeDecision(nextDecision.id, actor);
        PublicationRequest nextRequest = publication(draft.iri, third.iri,
                next.targets.get(0).iri, nextDecision.iri);
        PublicationDetails nextPublication = publications.publish(nextRequest,
                actor, first.targetEtag, "workflow-2", "request-4");
        assertThat(nextPublication.auditStatus).isEqualTo("PASS");
        assertThat(publications.getVersion(third.id).state).isEqualTo("CURRENT");
        assertThat(publications.verify(first.id, false, actor).outcome)
                .as("V1 to V2 remains independently auditable after V3")
                .isEqualTo("PASS");

        VersionDetails fourth = publications.reserveDraft("it", actor);
        ClaimCreateRequest deleteClaim = claim();
        deleteClaim.targets.get(0).subjectIri = oldSubject.stringValue();
        deleteClaim.targets.get(0).predicateIri = oldPredicate.stringValue();
        deleteClaim.targets.get(0).object.value = "existing";
        deleteClaim.targets.get(0).object.language = null;
        ClaimDetails removal = records.createClaim(deleteClaim, actor);
        DecisionCreateRequest rejected = decision(removal.iri, fourth.iri);
        rejected.outcome = "rejected";
        DecisionDetails removalDecision = records.createDecision(rejected,
                actor);
        removalDecision = records.finalizeDecision(removalDecision.id, actor);
        PublicationRequest deletion = publication(third.iri, fourth.iri,
                removal.targets.get(0).iri, removalDecision.iri);
        deletion.operations.get(0).action = "DELETE";
        PublicationDetails deletionPublication = publications.publish(deletion,
                actor, nextPublication.targetEtag, "workflow-3", "request-5");
        assertThat(deletionPublication.expectedDeletions).isEqualTo(1);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(oldSubject, oldPredicate,
                    vf.createLiteral("existing"), false, active)).isFalse();
        }

        IRI targetSnapshot = iri(firstTargetSnapshot(first));
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.remove(iri("https://example.org/entry/casa"),
                    iri("https://example.org/vocabulary/interpretation"),
                    vf.createLiteral("abitazione", "it"), targetSnapshot);
            connection.add(iri("https://example.org/entry/unexpected"),
                    iri("https://example.org/vocabulary/interpretation"),
                    vf.createLiteral("extra"), targetSnapshot);
        }
        AuditReportDetails drift = publications.verify(first.id, false, actor);
        assertThat(drift.outcome).isEqualTo("FAIL");
        assertThat(drift.missingTripleCount).isEqualTo(1);
        assertThat(drift.unexpectedTripleCount).isEqualTo(1);
        assertThat(drift.missingTriples).hasSize(1);
        assertThat(drift.unexpectedTriples).hasSize(1);
        AuditReportDetails historical = publications.auditHistory(first.id)
                .get(publications.auditHistory(first.id).size() - 1);
        assertThat(historical.missingTriples).hasSize(1);
        assertThat(historical.unexpectedTriples).hasSize(1);
        assertThat(historical.identityAssurance).isEqualTo("DECLARED");

        IRI auditGraph = iri(LexOProperties.getProperty(
                "GraphDb.lexintAuditNamedGraph"));
        IRI operation = iri(first.operations.get(0).iri);
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.remove(operation, AUTHORIZED_BY, null, auditGraph);
        }
        AuditReportDetails indeterminate = publications.verify(first.id, false,
                actor);
        assertThat(indeterminate.outcome).isEqualTo("INDETERMINATE");
        assertThat(indeterminate.errorCodes)
                .contains("LEXINT_DATA_INCONSISTENT");
        assertThat(publications.auditHistory(first.id).get(
                publications.auditHistory(first.id).size() - 1).errorCodes)
                .contains("LEXINT_DATA_INCONSISTENT");
    }

    @Test
    void createsClassicReificationOnlyAndRejectsRdf12Input() {
        ClaimDetails result = records.createClaim(claim(), actor);
        IRI recordsGraph = iri(LexOProperties.getProperty(
                "GraphDb.lexintRecordsNamedGraph"));
        try (RepositoryConnection connection = repository.getConnection()) {
            IRI claim = iri(result.iri);
            IRI target = iri(result.targets.get(0).iri);
            assertThat(connection.hasStatement(claim,
                    TARGET_ASSERTION_PROPERTY, target, false, recordsGraph))
                    .isTrue();
            assertThat(connection.hasStatement(target, RDF.TYPE,
                    RDF.STATEMENT, false, recordsGraph)).isTrue();
            assertThat(connection.hasStatement(target,
                    iri(RDF_REIFIES), null, false, recordsGraph)).isFalse();
            assertThat(connection.size((Resource) null)).isZero();
        }

        ClaimCreateRequest rdf12 = claim();
        rdf12.targets.get(0).object.reifies = "<<triple>>";
        assertThatThrownBy(() -> records.createClaim(rdf12, actor))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.code)
                                .isEqualTo("LEXINT_RDF12_UNSUPPORTED"));
    }

    @Test
    void crudTouchesOnlyModifiedAndFreezesClaimTargetsAndDecisionOnFinalize() {
        ClaimCreateRequest request = claim();
        TargetInput second = target("https://example.org/entry/casetta",
                "seconda interpretazione");
        request.targets = Arrays.asList(request.targets.get(0), second);
        ClaimDetails created = records.createClaim(request, actor);
        String originalCreator = created.creator;
        String originalCreated = created.created;

        ClaimPatchRequest claimPatch = new ClaimPatchRequest();
        claimPatch.description = "Descrizione editoriale aggiornata";
        ClaimDetails patched = records.patchClaim(created.id, claimPatch,
                created.etag, actor);
        assertThat(patched.creator).isEqualTo(originalCreator);
        assertThat(patched.created).isEqualTo(originalCreated);
        assertThat(Instant.parse(patched.modified))
                .isAfter(Instant.parse(created.modified));

        TargetDetails target = patched.targets.get(1);
        TargetInput replacement = target(
                "https://example.org/entry/casetta-rivista",
                "interpretazione rivista");
        TargetDetails changed = records.patchTarget(target.id, replacement,
                target.etag, actor);
        assertThat(changed.created).isEqualTo(target.created);
        assertThat(changed.creator).isEqualTo(target.creator);
        assertThat(Instant.parse(changed.modified))
                .isAfter(Instant.parse(target.modified));
        records.deleteTarget(changed.id, actor);
        assertThat(records.getClaim(created.id).targets).hasSize(1);

        publications.baseline("it", actor);
        VersionDetails draft = publications.reserveDraft("it", actor);
        DecisionDetails decision = records.createDecision(
                decision(created.iri, draft.iri), actor);
        assertThatThrownBy(() -> records.deleteClaim(created.id))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(409));
        assertThat(records.getDecision(decision.id).claimIri)
                .isEqualTo(created.iri);
        DecisionPatchRequest decisionPatch = new DecisionPatchRequest();
        decisionPatch.outcome = "suspended";
        decisionPatch.description = "Pending additional evidence";
        DecisionDetails amended = records.patchDecision(decision.id,
                decisionPatch, decision.etag, actor);
        assertThat(amended.created).isEqualTo(decision.created);
        assertThat(amended.creator).isEqualTo(decision.creator);
        DecisionDetails finalized = records.finalizeDecision(decision.id,
                actor);
        assertThat(finalized.finalized).isTrue();
        assertThat(finalized.endedAt).isNotBlank();
        assertThat(records.getClaim(created.id).editable).isFalse();

        assertThatThrownBy(() -> records.patchDecision(decision.id,
                decisionPatch, finalized.etag, actor))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.code)
                                .isEqualTo("LEXINT_IMMUTABLE_RESOURCE"));
        assertThatThrownBy(() -> records.deleteDecision(decision.id))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(409));
        assertThatThrownBy(() -> records.deleteClaim(created.id))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(409));
    }

    @Test
    void managedGuardActivatesOnlyForLanguagesWithCurrentBaseline() {
        LexIntGraphGuard guard = new LexIntGraphGuard();
        try (RepositoryConnection connection = repository.getConnection()) {
            guard.requireDirectWriteAllowed(connection, "it");
        }
        publications.baseline("it", actor);
        LexOProperties.setProperty("LexInt.managedPublication.enabled", "true");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThatThrownBy(() -> guard.requireDirectWriteAllowed(connection,
                    "it")).isInstanceOf(
                            LexIntGraphGuard.ManagedWriteException.class);
            guard.requireDirectWriteAllowed(connection, "en");
        }
        LexOProperties.setProperty("LexInt.managedPublication.enabled", "false");
        try (RepositoryConnection connection = repository.getConnection()) {
            guard.requireDirectWriteAllowed(connection, "it");
        }
    }

    @Test
    void metadataWriterPreservesCreatorAndCreatedWhenTouching() {
        IRI graph = iri("https://example.org/graph");
        IRI subject = iri("https://example.org/runtime/item");
        LexIntMetadataWriter writer = new LexIntMetadataWriter(Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        try (RepositoryConnection connection = repository.getConnection()) {
            writer.createIndividual(connection, subject, actor, graph);
            Literal created = value(connection, subject, DCTERMS.CREATED, graph);
            Literal creator = value(connection, subject, DCTERMS.CREATOR, graph);
            writer.touch(connection, subject, actor, graph);
            assertThat(value(connection, subject, DCTERMS.CREATED, graph))
                    .isEqualTo(created);
            assertThat(value(connection, subject, DCTERMS.CREATOR, graph))
                    .isEqualTo(creator);
            assertThat(value(connection, subject, DCTERMS.MODIFIED, graph)
                    .getLabel()).isEqualTo("2026-01-01T00:00:00.001Z");
        }
    }

    @Test
    void installedShaclRejectsAnIncompleteClassicReification() {
        IRI recordsGraph = iri(LexOProperties.getProperty(
                "GraphDb.lexintRecordsNamedGraph"));
        IRI target = iri("https://example.org/runtime/incomplete-target");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(target, RDF.TYPE, TARGET_ASSERTION, recordsGraph);
            connection.add(target, RDF.SUBJECT,
                    iri("https://example.org/entry/casa"), recordsGraph);
            connection.add(target, RDF.PREDICATE,
                    iri("https://example.org/vocabulary/interpretation"),
                    recordsGraph);
            new LexIntMetadataWriter().createIndividual(connection, target,
                    actor, recordsGraph);
            LexIntShaclValidator validator = new LexIntShaclValidator(
                    new LexIntRepository(repository));
            assertThatThrownBy(() -> validator.validate(connection,
                    Collections.singletonList(target)))
                    .isInstanceOfSatisfying(LexIntException.class,
                            error -> assertThat(error.code)
                                    .isEqualTo("LEXINT_SHACL_VIOLATION"));
        }
    }

    @Test
    void rejectsInvalidLanguageAndPublicationFilterAsClientErrors() {
        assertThatThrownBy(() -> publications.baseline("not-a-language",
                actor)).isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(400));
        assertThatThrownBy(() -> publications.listPublications(null,
                "not an iri", null, null, null, 10, 0, "request"))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(400));
        ClaimCreateRequest missingDescription = claim();
        missingDescription.description = "  ";
        assertThatThrownBy(() -> records.createClaim(missingDescription, actor))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> {
                            assertThat(error.status).isEqualTo(422);
                            assertThat(error.code)
                                    .isEqualTo("LEXINT_SHACL_VIOLATION");
                        });
    }

    @Test
    void rollsBackActiveSnapshotAndAuditWhenPostWriteShaclFails() {
        IRI active = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI existingSubject = iri("https://example.org/entry/existing");
        IRI existingPredicate = iri("https://example.org/vocabulary/status");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(existingSubject, existingPredicate,
                    vf.createLiteral("stable"), active);
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
        IRI shapesGraph = iri(LexOProperties.getProperty(
                "GraphDb.lexintShapesNamedGraph"));
        IRI targetSnapshot = iri(LexOProperties.getProperty(
                "GraphDb.lexintSnapshotsBase") + "it/" + draft.versionCode);
        long auditSize;
        try (RepositoryConnection connection = repository.getConnection()) {
            auditSize = connection.size(auditGraph);
            BNode property = vf.createBNode();
            IRI shProperty = iri("http://www.w3.org/ns/shacl#property");
            IRI shPath = iri("http://www.w3.org/ns/shacl#path");
            IRI shMinCount = iri("http://www.w3.org/ns/shacl#minCount");
            connection.add(iri(PUBLICATION_NS + "PublicationShape"),
                    shProperty, property, shapesGraph);
            connection.add(property, shPath,
                    iri("https://example.org/required-after-write"),
                    shapesGraph);
            connection.add(property, shMinCount, vf.createLiteral(1),
                    shapesGraph);
        }

        assertThatThrownBy(() -> publications.publish(request, actor,
                preview.baseEtag, "rollback-after-write", "request-rollback"))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.code)
                                .isEqualTo("LEXINT_SHACL_VIOLATION"));
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(auditGraph)).isEqualTo(auditSize);
            assertThat(connection.size(targetSnapshot)).isZero();
            assertThat(connection.hasStatement(existingSubject,
                    existingPredicate, vf.createLiteral("stable"), false,
                    active)).isTrue();
            assertThat(connection.hasStatement(
                    iri("https://example.org/entry/casa"),
                    iri("https://example.org/vocabulary/interpretation"),
                    vf.createLiteral("abitazione", "it"), false, active))
                    .isFalse();
            assertThat(connection.size((Resource) null)).isZero();
        }
        assertThat(publications.getVersion(baseline.version.id).state)
                .isEqualTo("CURRENT");
        assertThat(publications.getVersion(draft.id).state)
                .isEqualTo("RESERVED");
    }

    @Test
    void rejectsActiveGraphDriftAfterPreviewWithoutCreatingPublication() {
        IRI active = iri(LexiconCrudSupport.lexicalGraphUri("it"));
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
        long auditSize;
        try (RepositoryConnection connection = repository.getConnection()) {
            auditSize = connection.size(auditGraph);
            connection.add(iri("https://example.org/outside-workflow"),
                    iri("https://example.org/vocabulary/status"),
                    vf.createLiteral("drift"), active);
        }

        assertThatThrownBy(() -> publications.publish(request, actor,
                preview.baseEtag, "active-drift", "request-drift"))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.code)
                                .isEqualTo("LEXINT_ACTIVE_GRAPH_DRIFT"));
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(auditGraph)).isEqualTo(auditSize);
            assertThat(connection.size(iri(LexOProperties.getProperty(
                    "GraphDb.lexintSnapshotsBase") + "it/"
                    + draft.versionCode))).isZero();
            assertThat(connection.size((Resource) null)).isZero();
        }
    }

    @Test
    void publishesOnlyTheExplicitTargetOfAPartialClaim() {
        IRI active = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        LexIntPublicationManager.VersionCreation baseline =
                publications.baseline("it", actor);
        VersionDetails draft = publications.reserveDraft("it", actor);
        ClaimCreateRequest input = claim();
        input.targets = Arrays.asList(input.targets.get(0), target(
                "https://example.org/entry/non-selezionata", "non pubblicata"));
        ClaimDetails claim = records.createClaim(input, actor);
        DecisionDetails decision = records.createDecision(
                decision(claim.iri, draft.iri), actor);
        decision = records.finalizeDecision(decision.id, actor);
        TargetDetails selected = claim.targets.stream().filter(item ->
                "https://example.org/entry/casa".equals(item.subjectIri))
                .findFirst().get();
        PublicationRequest request = publication(baseline.version.iri,
                draft.iri, selected.iri, decision.iri);
        PublicationPreview preview = publications.preview(request, actor);
        assertThat(preview.partialClaims).isTrue();
        assertThat(preview.warnings).hasSize(1);

        publications.publish(request, actor, preview.baseEtag,
                "partial-claim", "request-partial");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(
                    iri("https://example.org/entry/casa"),
                    iri("https://example.org/vocabulary/interpretation"),
                    vf.createLiteral("abitazione", "it"), false, active))
                    .isTrue();
            assertThat(connection.hasStatement(
                    iri("https://example.org/entry/non-selezionata"),
                    iri("https://example.org/vocabulary/interpretation"),
                    vf.createLiteral("non pubblicata", "it"), false, active))
                    .isFalse();
        }
    }

    @Test
    void rejectsDifferentTargetsThatOverlapOnTheSameRdfStatement() {
        LexIntPublicationManager.VersionCreation baseline =
                publications.baseline("it", actor);
        VersionDetails draft = publications.reserveDraft("it", actor);
        ClaimCreateRequest input = claim();
        input.targets = Arrays.asList(input.targets.get(0), target(
                "https://example.org/entry/casa", "abitazione"));
        ClaimDetails claim = records.createClaim(input, actor);
        DecisionDetails decision = records.createDecision(
                decision(claim.iri, draft.iri), actor);
        decision = records.finalizeDecision(decision.id, actor);
        PublicationRequest request = publication(baseline.version.iri,
                draft.iri, claim.targets.get(0).iri, decision.iri);
        PublicationOperationInput duplicate = new PublicationOperationInput();
        duplicate.action = "INSERT";
        duplicate.targetAssertionIri = claim.targets.get(1).iri;
        duplicate.decisionIri = decision.iri;
        request.operations = Arrays.asList(request.operations.get(0), duplicate);

        assertThatThrownBy(() -> publications.preview(request, actor))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.code)
                                .isEqualTo("LEXINT_AMBIGUOUS_AUTHORIZATION"));
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size((Resource) null)).isZero();
            assertThat(connection.hasStatement(null, RDF.TYPE, PUBLICATION,
                    false, iri(LexOProperties.getProperty(
                            "GraphDb.lexintAuditNamedGraph")))).isFalse();
        }
    }

    @Test
    void rejectsOutcomeClaimAndVersionMismatchesWithoutPublishing() {
        LexIntPublicationManager.VersionCreation baseline =
                publications.baseline("it", actor);
        VersionDetails firstDraft = publications.reserveDraft("it", actor);
        VersionDetails secondDraft = publications.reserveDraft("it", actor);
        ClaimDetails firstClaim = records.createClaim(claim(), actor);
        ClaimCreateRequest otherInput = claim();
        otherInput.targets.get(0).subjectIri =
                "https://example.org/entry/other-claim";
        ClaimDetails otherClaim = records.createClaim(otherInput, actor);
        DecisionDetails adopted = records.createDecision(
                decision(firstClaim.iri, firstDraft.iri), actor);
        adopted = records.finalizeDecision(adopted.id, actor);

        PublicationRequest wrongClaim = publication(baseline.version.iri,
                firstDraft.iri, otherClaim.targets.get(0).iri, adopted.iri);
        assertNotEligible(wrongClaim);

        PublicationRequest wrongVersion = publication(baseline.version.iri,
                secondDraft.iri, firstClaim.targets.get(0).iri, adopted.iri);
        assertNotEligible(wrongVersion);

        ClaimCreateRequest suspendedInput = claim();
        suspendedInput.targets.get(0).subjectIri =
                "https://example.org/entry/suspended";
        ClaimDetails suspendedClaim = records.createClaim(suspendedInput, actor);
        DecisionCreateRequest suspendedRequest = decision(suspendedClaim.iri,
                firstDraft.iri);
        suspendedRequest.outcome = "suspended";
        DecisionDetails suspended = records.createDecision(suspendedRequest,
                actor);
        suspended = records.finalizeDecision(suspended.id, actor);
        assertNotEligible(publication(baseline.version.iri, firstDraft.iri,
                suspendedClaim.targets.get(0).iri, suspended.iri));

        PublicationRequest invalidDelete = publication(baseline.version.iri,
                firstDraft.iri, firstClaim.targets.get(0).iri, adopted.iri);
        invalidDelete.operations.get(0).action = "DELETE";
        assertNotEligible(invalidDelete);

        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(null, RDF.TYPE, PUBLICATION,
                    false, iri(LexOProperties.getProperty(
                            "GraphDb.lexintAuditNamedGraph")))).isFalse();
            assertThat(connection.size((Resource) null)).isZero();
        }
    }

    @Test
    void serializesConcurrentPublishAndCommitsExactlyOnce() throws Exception {
        LexIntPublicationManager.VersionCreation baseline =
                publications.baseline("it", actor);
        VersionDetails draft = publications.reserveDraft("it", actor);
        ClaimDetails claim = records.createClaim(claim(), actor);
        DecisionDetails decision = records.createDecision(
                decision(claim.iri, draft.iri), actor);
        decision = records.finalizeDecision(decision.id, actor);
        final PublicationRequest request = publication(baseline.version.iri,
                draft.iri, claim.targets.get(0).iri, decision.iri);
        final String etag = publications.preview(request, actor).baseEtag;
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> first = concurrentPublish(request, etag,
                    "concurrent-one", ready, start);
            Callable<Object> second = concurrentPublish(request, etag,
                    "concurrent-two", ready, start);
            Future<Object> left = executor.submit(first);
            Future<Object> right = executor.submit(second);
            ready.await();
            start.countDown();
            Object leftResult = left.get();
            Object rightResult = right.get();
            assertThat(Arrays.asList(leftResult, rightResult)
                    .stream().filter(value -> value instanceof PublicationDetails)
                    .count()).isEqualTo(1L);
            Object failure = leftResult instanceof LexIntException
                    ? leftResult : rightResult;
            assertThat(failure).isInstanceOfSatisfying(LexIntException.class,
                    error -> assertThat(error.status).isEqualTo(412));
            try (RepositoryConnection connection = repository.getConnection()) {
                long publicationsCount = 0L;
                try (RepositoryResult<Statement> rows =
                        connection.getStatements(null, RDF.TYPE, PUBLICATION,
                                false, iri(LexOProperties.getProperty(
                                        "GraphDb.lexintAuditNamedGraph")))) {
                    while (rows.hasNext()) {
                        rows.next();
                        publicationsCount++;
                    }
                }
                assertThat(publicationsCount).isEqualTo(1L);
                assertThat(connection.size((Resource) null)).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertNotEligible(PublicationRequest request) {
        assertThatThrownBy(() -> publications.preview(request, actor))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.code)
                                .isEqualTo("LEXINT_DECISION_NOT_ELIGIBLE"));
    }

    private Callable<Object> concurrentPublish(final PublicationRequest request,
            final String etag, final String key, final CountDownLatch ready,
            final CountDownLatch start) {
        return new Callable<Object>() {
            @Override public Object call() throws Exception {
                ready.countDown();
                start.await();
                try {
                    return publications.publish(request, actor, etag, key,
                            "request-" + key);
                } catch (LexIntException ex) {
                    return ex;
                }
            }
        };
    }

    private ClaimCreateRequest claim() {
        ClaimCreateRequest claim = new ClaimCreateRequest();
        claim.description = "Interpretazione editoriale controllata";
        claim.evidenceIris = Arrays.asList("https://example.org/evidence/1");
        claim.targets = Arrays.asList(target(
                "https://example.org/entry/casa", "abitazione"));
        return claim;
    }

    private TargetInput target(String subject, String value) {
        TargetObjectInput object = new TargetObjectInput();
        object.kind = "LITERAL";
        object.value = value;
        object.language = "it";
        TargetInput target = new TargetInput();
        target.subjectIri = subject;
        target.predicateIri =
                "https://example.org/vocabulary/interpretation";
        target.object = object;
        return target;
    }

    private DecisionCreateRequest decision(String claim, String version) {
        DecisionCreateRequest decision = new DecisionCreateRequest();
        decision.claimIri = claim;
        decision.versionIri = version;
        decision.outcome = "adopted";
        decision.description = "Approved for the next Italian version";
        return decision;
    }

    private PublicationRequest publication(String source, String target,
            String targetAssertion, String decision) {
        PublicationOperationInput operation = new PublicationOperationInput();
        operation.action = "INSERT";
        operation.targetAssertionIri = targetAssertion;
        operation.decisionIri = decision;
        PublicationRequest publication = new PublicationRequest();
        publication.language = "it";
        publication.sourceVersionIri = source;
        publication.targetVersionIri = target;
        publication.operations = Arrays.asList(operation);
        return publication;
    }

    private String firstTargetSnapshot(PublicationDetails publication) {
        VersionDetails target = publications.listVersions("it", 10, 0).items
                .stream().filter(item -> item.iri.equals(
                        publication.targetVersionIri)).findFirst().get();
        return target.snapshotGraphIri;
    }

    private void assertRuntimeIndividualsHaveMetadata(
            RepositoryConnection connection) {
        IRI recordsGraph = iri(LexOProperties.getProperty(
                "GraphDb.lexintRecordsNamedGraph"));
        IRI auditGraph = iri(LexOProperties.getProperty(
                "GraphDb.lexintAuditNamedGraph"));
        for (Resource graph : Arrays.<Resource>asList(recordsGraph, auditGraph)) {
            try (RepositoryResult<Statement> types = connection.getStatements(
                    null, RDF.TYPE, null, false, graph)) {
                while (types.hasNext()) {
                    Resource subject = types.next().getSubject();
                    if (!(subject instanceof IRI)) { continue; }
                    assertThat(connection.hasStatement(subject, DCTERMS.CREATOR,
                            null, false, graph)).as(subject.stringValue()).isTrue();
                    Literal created = value(connection, (IRI) subject,
                            DCTERMS.CREATED, graph);
                    Literal modified = value(connection, (IRI) subject,
                            DCTERMS.MODIFIED, graph);
                    assertThat(created.getDatatype()).isEqualTo(XSD.DATETIME);
                    assertThat(modified.getDatatype()).isEqualTo(XSD.DATETIME);
                }
            }
        }
    }

    private Literal value(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        try (RepositoryResult<Statement> values = connection.getStatements(
                subject, predicate, null, false, graph)) {
            assertThat(values.hasNext()).isTrue();
            return (Literal) values.next().getObject();
        }
    }

    private IRI iri(String value) { return vf.createIRI(value); }
}
