package it.cnr.ilc.lexo.manager;

import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.*;
import it.cnr.ilc.lexo.manager.lexint.LexIntActor;
import it.cnr.ilc.lexo.manager.lexint.LexIntException;
import it.cnr.ilc.lexo.manager.lexint.LexIntJsonCodec;
import it.cnr.ilc.lexo.manager.lexint.LexIntMetadataWriter;
import it.cnr.ilc.lexo.manager.lexint.LexIntPublicationAuditor;
import it.cnr.ilc.lexo.manager.lexint.LexIntPublicationPlanner;
import it.cnr.ilc.lexo.manager.lexint.LexIntRepository;
import it.cnr.ilc.lexo.manager.lexint.LexIntResourceUris;
import it.cnr.ilc.lexo.manager.lexint.LexIntShaclValidator;
import it.cnr.ilc.lexo.manager.lexint.PublicationFaultInjector;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationOperationInput;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationRequest;
import it.cnr.ilc.lexo.service.data.lexint.output.AuditReportDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.LexIntPage;
import it.cnr.ilc.lexo.service.data.lexint.output.PublicationDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.PublicationOperationDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.PublicationPreview;
import it.cnr.ilc.lexo.service.data.lexint.output.TripleDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.VersionDetails;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Version ledger, preview, atomic publication, idempotency and audit. */
public final class LexIntPublicationManager implements Manager {
    private static final Object WRITE_LOCK = new Object();
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final LexIntRepository repository;
    private final LexIntResourceUris uris;
    private final LexIntMetadataWriter metadata;
    private final LexIntPublicationPlanner planner;
    private final LexIntPublicationAuditor auditor;
    private final LexIntShaclValidator shacl;
    private final PublicationFaultInjector faultInjector;

    public LexIntPublicationManager() {
        this(new LexIntRepository(), new LexIntResourceUris(),
                new LexIntMetadataWriter(), PublicationFaultInjector.NONE);
    }

    public LexIntPublicationManager(Repository rdfRepository) {
        this(new LexIntRepository(rdfRepository), new LexIntResourceUris(),
                new LexIntMetadataWriter(), PublicationFaultInjector.NONE);
    }

    public LexIntPublicationManager(LexIntRepository repository,
            LexIntResourceUris uris, LexIntMetadataWriter metadata) {
        this(repository, uris, metadata, PublicationFaultInjector.NONE);
    }

    /** Constructor for in-process tests that need a pre-commit failpoint. */
    public LexIntPublicationManager(LexIntRepository repository,
            LexIntResourceUris uris, LexIntMetadataWriter metadata,
            PublicationFaultInjector faultInjector) {
        this.repository = repository; this.uris = uris;
        this.metadata = metadata;
        this.planner = new LexIntPublicationPlanner(repository, metadata);
        this.auditor = new LexIntPublicationAuditor();
        this.shacl = new LexIntShaclValidator(repository);
        this.faultInjector = faultInjector == null
                ? PublicationFaultInjector.NONE : faultInjector;
    }

    public VersionCreation baseline(String language, final LexIntActor actor) {
        final String canonical = LexIntResourceUris.language(language);
        synchronized (WRITE_LOCK) {
            return repository.write(new LexIntRepository.Work<VersionCreation>() {
                @Override public VersionCreation run(
                        RepositoryConnection connection) {
                    IRI existing = baselineVersion(connection, canonical);
                    if (existing != null) {
                        return new VersionCreation(versionDetails(connection,
                                existing, actor.identityAssurance), true);
                    }
                    if (hasAnyVersion(connection, canonical)) {
                        throw new LexIntException(409,
                                "LEXINT_BASE_VERSION_MISMATCH",
                                "Baseline must precede every reserved version");
                    }
                    List<IRI> focus = new ArrayList<IRI>();
                    IRI agent = ensureAgent(connection, actor, focus);
                    IRI version = uris.mint("version");
                    String code = "v000001";
                    IRI snapshot = uris.snapshotGraph(canonical, code);
                    IRI active = vf.createIRI(
                            LexiconCrudSupport.lexicalGraphUri(canonical));
                    if (connection.hasStatement(null, null, null, false,
                            snapshot)) {
                        throw new LexIntException(409,
                                "LEXINT_SNAPSHOT_CONFLICT",
                                "Baseline snapshot graph is not empty");
                    }
                    Model activeModel = repository.model(connection, active);
                    connection.add(activeModel, snapshot);
                    addVersion(connection, version, canonical, code, "CURRENT",
                            snapshot, Long.valueOf(activeModel.size()),
                            uris.opaqueEtag(), agent, actor, focus);
                    shacl.validate(connection, focus);
                    if (!Models.isomorphic(activeModel,
                            repository.model(connection, snapshot))) {
                        throw new LexIntException(500,
                                "LEXINT_PUBLICATION_FAILURE",
                                "Baseline snapshot verification failed");
                    }
                    return new VersionCreation(versionDetails(connection,
                            version, actor.identityAssurance), false);
                }
            });
        }
    }

    public VersionDetails reserveDraft(String language,
            final LexIntActor actor) {
        final String canonical = LexIntResourceUris.language(language);
        synchronized (WRITE_LOCK) {
            return repository.write(new LexIntRepository.Work<VersionDetails>() {
                @Override public VersionDetails run(
                        RepositoryConnection connection) {
                    if (currentVersion(connection, canonical) == null) {
                        throw new LexIntException(409,
                                "LEXINT_BASE_VERSION_MISMATCH",
                                "Create a baseline before reserving a version");
                    }
                    List<IRI> focus = new ArrayList<IRI>();
                    IRI agent = ensureAgent(connection, actor, focus);
                    IRI version = uris.mint("version");
                    String code = nextVersionCode(connection, canonical);
                    addVersion(connection, version, canonical, code, "RESERVED",
                            null, null, null, agent, actor, focus);
                    shacl.validate(connection, focus);
                    return versionDetails(connection, version,
                            actor.identityAssurance);
                }
            });
        }
    }

    public VersionDetails getVersion(String id) {
        final IRI version = uris.resource("version", id);
        return repository.read(new LexIntRepository.Work<VersionDetails>() {
            @Override public VersionDetails run(RepositoryConnection connection) {
                requireType(connection, version, ENTITY, repository.auditGraph);
                VersionDetails result = versionDetails(connection, version,
                        null);
                IRI publication = optionalIri(connection, version,
                        WAS_GENERATED_BY, repository.auditGraph);
                if (publication != null) {
                    result.audit.addAll(auditHistory(connection, publication));
                }
                return result;
            }
        });
    }

    public LexIntPage<VersionDetails> listVersions(final String language,
            final int limit, final int offset) {
        pagination(limit, offset);
        final String canonical = language == null || language.trim().isEmpty()
                ? null : LexIntResourceUris.language(language);
        return repository.read(new LexIntRepository.Work<LexIntPage<VersionDetails>>() {
            @Override public LexIntPage<VersionDetails> run(
                    RepositoryConnection connection) {
                List<VersionDetails> values = new ArrayList<VersionDetails>();
                try (RepositoryResult<Statement> rows = connection.getStatements(
                        null, VERSION_CODE, null, false, repository.auditGraph)) {
                    Set<IRI> seen = new HashSet<IRI>();
                    while (rows.hasNext()) {
                        Resource resource = rows.next().getSubject();
                        if (!(resource instanceof IRI)
                                || !seen.add((IRI) resource)) { continue; }
                        IRI version = (IRI) resource;
                        if (canonical != null && !connection.hasStatement(version,
                                LANGUAGE, vf.createLiteral(canonical), false,
                                repository.auditGraph)) { continue; }
                        values.add(versionDetails(connection, version, null));
                    }
                }
                Collections.sort(values, new Comparator<VersionDetails>() {
                    @Override public int compare(VersionDetails left,
                            VersionDetails right) {
                        int languageOrder = left.language.compareTo(right.language);
                        return languageOrder != 0 ? languageOrder
                                : left.versionCode.compareTo(right.versionCode);
                    }
                });
                return page(values, limit, offset);
            }
        });
    }

    public void deleteReservedVersion(final String id) {
        final IRI version = uris.resource("version", id);
        synchronized (WRITE_LOCK) {
            repository.write(new LexIntRepository.Work<Void>() {
                @Override public Void run(RepositoryConnection connection) {
                    requireType(connection, version, ENTITY,
                            repository.auditGraph);
                    if (!connection.hasStatement(version, VERSION_STATE,
                            vf.createLiteral("RESERVED"), false,
                            repository.auditGraph)) {
                        throw LexIntException.immutable(
                                "Only an unused reserved version can be deleted");
                    }
                    if (connection.hasStatement(null, APPLIES_TO_VERSION, version,
                            false, repository.recordsGraph)
                            || connection.hasStatement(null, TARGET_VERSION,
                                    version, false, repository.auditGraph)) {
                        throw LexIntException.immutable(
                                "Reserved version is already referenced");
                    }
                    connection.remove(version, null, null,
                            repository.auditGraph);
                    connection.remove((Resource) null, null, version,
                            repository.auditGraph);
                    return null;
                }
            });
        }
    }

    public Model snapshot(String id) {
        final IRI version = uris.resource("version", id);
        return repository.read(new LexIntRepository.Work<Model>() {
            @Override public Model run(RepositoryConnection connection) {
                requireType(connection, version, ENTITY, repository.auditGraph);
                IRI graph = optionalIri(connection, version, SNAPSHOT_GRAPH,
                        repository.auditGraph);
                if (graph == null) {
                    throw new LexIntException(409, "LEXINT_VERSION_NOT_PUBLISHED",
                            "Reserved version has no snapshot");
                }
                return repository.model(connection, graph);
            }
        });
    }

    public PublicationPreview preview(final PublicationRequest request,
            final LexIntActor actor) {
        return repository.read(new LexIntRepository.Work<PublicationPreview>() {
            @Override public PublicationPreview run(
                    RepositoryConnection connection) {
                return preview(planner.plan(connection, request), actor);
            }
        });
    }

    public PublicationDetails publish(final PublicationRequest request,
            final LexIntActor actor, final String ifMatch,
            final String idempotencyKey, final String requestId) {
        if (idempotencyKey == null || !idempotencyKey.matches(
                "[A-Za-z0-9._~:-]{1,128}")) {
            throw LexIntException.invalid(
                    "A safe Idempotency-Key of 1..128 characters is required");
        }
        final String payloadHash = publicationFingerprint(request, actor);
        final String idempotencyHash = LexIntResourceUris.sha256(
                uris.agent(actor).stringValue() + "|" + idempotencyKey);
        synchronized (WRITE_LOCK) {
            return repository.write(new LexIntRepository.Work<PublicationDetails>() {
                @Override public PublicationDetails run(
                        RepositoryConnection connection) {
                    IRI replay = findPublicationByIdempotency(connection,
                            idempotencyHash);
                    if (replay != null) {
                        if (!connection.hasStatement(replay, PAYLOAD_HASH,
                                vf.createLiteral(payloadHash), false,
                                repository.auditGraph)) {
                            throw new LexIntException(409,
                                    "LEXINT_IDEMPOTENCY_CONFLICT",
                                    "Idempotency key was used for another payload");
                        }
                        PublicationDetails result = publicationDetails(connection,
                                replay, requestId);
                        result.replayed = true;
                        return result;
                    }
                    LexIntPublicationPlanner.Plan plan = planner.plan(connection,
                            request);
                    if (ifMatch == null || !ifMatch.equals(plan.baseEtag)) {
                        throw new LexIntException(412,
                                "LEXINT_BASE_VERSION_MISMATCH",
                                "If-Match does not match the current version");
                    }
                    if (connection.hasStatement(null, null, null, false,
                            plan.targetSnapshotGraph)) {
                        throw new LexIntException(409,
                                "LEXINT_SNAPSHOT_CONFLICT",
                                "Target snapshot graph is not empty");
                    }
                    connection.add(plan.expected, plan.targetSnapshotGraph);
                    connection.clear(plan.activeGraph);
                    connection.add(plan.expected, plan.activeGraph);
                    IRI publication = uris.mint("publication");
                    List<IRI> focus = new ArrayList<IRI>();
                    IRI agent = ensureAgent(connection, actor, focus);
                    addPublication(connection, publication, plan, actor, agent,
                            idempotencyHash, payloadHash, focus);
                    String targetEtag = uris.opaqueEtag();
                    completeVersionLedger(connection, plan, publication,
                            targetEtag, actor);
                    AuditRecord initial = addAudit(connection, publication,
                            actor, agent, "PASS", 0L, 0L,
                            "SNAPSHOT", false, plan.sourceSnapshotGraph,
                            plan.targetSnapshotGraph,
                            new ArrayList<TripleDetails>(),
                            new ArrayList<TripleDetails>(),
                            new ArrayList<String>(), focus);
                    faultInjector.afterProvisionalWrites();
                    shacl.validate(connection, focus);
                    if (!Models.isomorphic(plan.expected,
                            repository.model(connection,
                                    plan.targetSnapshotGraph))
                            || !Models.isomorphic(plan.expected,
                                    repository.model(connection,
                                            plan.activeGraph))) {
                        throw new LexIntException(500,
                                "LEXINT_PUBLICATION_FAILURE",
                                "Transactional graph verification failed");
                    }
                    PublicationDetails result = publicationDetails(connection,
                            publication, requestId);
                    result.targetEtag = targetEtag;
                    result.auditStatus = initial.details.outcome;
                    return result;
                }
            });
        }
    }

    public PublicationDetails getPublication(String id, final String requestId) {
        final IRI publication = uris.resource("publication", id);
        return repository.read(new LexIntRepository.Work<PublicationDetails>() {
            @Override public PublicationDetails run(
                    RepositoryConnection connection) {
                requireType(connection, publication, PUBLICATION,
                        repository.auditGraph);
                return publicationDetails(connection, publication, requestId);
            }
        });
    }

    public LexIntPage<PublicationDetails> listPublications(final String language,
            final String versionIri, final String username, final String from,
            final String to, final int limit, final int offset,
            final String requestId) {
        pagination(limit, offset);
        final String canonical = language == null || language.trim().isEmpty()
                ? null : LexIntResourceUris.language(language);
        final IRI version = versionIri == null || versionIri.trim().isEmpty()
                ? null : inputIri(versionIri, "versionIri");
        final Instant lower = parseTime(from);
        final Instant upper = parseTime(to);
        return repository.read(new LexIntRepository.Work<LexIntPage<PublicationDetails>>() {
            @Override public LexIntPage<PublicationDetails> run(
                    RepositoryConnection connection) {
                List<PublicationDetails> values = new ArrayList<PublicationDetails>();
                try (RepositoryResult<Statement> rows = connection.getStatements(
                        null, RDF.TYPE, PUBLICATION, false,
                        repository.auditGraph)) {
                    while (rows.hasNext()) {
                        Resource resource = rows.next().getSubject();
                        if (!(resource instanceof IRI)) { continue; }
                        IRI publication = (IRI) resource;
                        if (canonical != null && !connection.hasStatement(publication,
                                LANGUAGE, vf.createLiteral(canonical), false,
                                repository.auditGraph)) { continue; }
                        if (version != null && !connection.hasStatement(publication,
                                SOURCE_VERSION, version, false,
                                repository.auditGraph)
                                && !connection.hasStatement(publication,
                                        TARGET_VERSION, version, false,
                                        repository.auditGraph)) { continue; }
                        if (username != null && !username.trim().isEmpty()
                                && !connection.hasStatement(publication, USERNAME,
                                        vf.createLiteral(username.trim()), false,
                                        repository.auditGraph)) { continue; }
                        Instant created = Instant.parse(metadata.created(connection,
                                publication, repository.auditGraph));
                        if (lower != null && created.isBefore(lower)
                                || upper != null && created.isAfter(upper)) {
                            continue;
                        }
                        values.add(publicationDetails(connection, publication,
                                requestId));
                    }
                }
                Collections.sort(values, new Comparator<PublicationDetails>() {
                    @Override public int compare(PublicationDetails left,
                            PublicationDetails right) {
                        int created = right.created.compareTo(left.created);
                        return created != 0 ? created : left.iri.compareTo(right.iri);
                    }
                });
                return page(values, limit, offset);
            }
        });
    }

    public List<AuditReportDetails> auditHistory(String id) {
        final IRI publication = uris.resource("publication", id);
        return repository.read(new LexIntRepository.Work<List<AuditReportDetails>>() {
            @Override public List<AuditReportDetails> run(
                    RepositoryConnection connection) {
                requireType(connection, publication, PUBLICATION,
                        repository.auditGraph);
                return auditHistory(connection, publication);
            }
        });
    }

    public AuditReportDetails verify(String id, final boolean checkActive,
            final LexIntActor actor) {
        final IRI publication = uris.resource("publication", id);
        synchronized (WRITE_LOCK) {
            return repository.write(new LexIntRepository.Work<AuditReportDetails>() {
                @Override public AuditReportDetails run(
                        RepositoryConnection connection) {
                    requireType(connection, publication, PUBLICATION,
                            repository.auditGraph);
                    IRI sourceVersion = objectIri(connection, publication,
                            SOURCE_VERSION, repository.auditGraph);
                    IRI targetVersion = objectIri(connection, publication,
                            TARGET_VERSION, repository.auditGraph);
                    IRI sourceGraph = optionalIri(connection, sourceVersion,
                            SNAPSHOT_GRAPH, repository.auditGraph);
                    IRI targetGraph = optionalIri(connection, targetVersion,
                            SNAPSHOT_GRAPH, repository.auditGraph);
                    String language = literal(connection, publication, LANGUAGE,
                            repository.auditGraph);
                    if (checkActive && !connection.hasStatement(targetVersion,
                            VERSION_STATE, vf.createLiteral("CURRENT"), false,
                            repository.auditGraph)) {
                        throw new LexIntException(409,
                                "LEXINT_VERSION_NOT_CURRENT",
                                "Active verification is limited to the current version");
                    }
                    LexIntPublicationAuditor.Result comparison;
                    try {
                        if (sourceGraph == null || targetGraph == null
                                || snapshotInputUnavailable(connection,
                                        sourceVersion, sourceGraph)
                                || snapshotInputUnavailable(connection,
                                        targetVersion, targetGraph)) {
                            comparison = auditor.compare(null, null);
                        } else {
                            Model source = repository.model(connection, sourceGraph);
                            Model expected = reconstructExpected(connection,
                                    publication, sourceVersion, targetVersion,
                                    source);
                            Model observed = repository.model(connection,
                                    checkActive ? vf.createIRI(
                                            LexiconCrudSupport.lexicalGraphUri(
                                                    language)) : targetGraph);
                            comparison = auditor.compare(expected, observed);
                        }
                    } catch (LexIntException ex) {
                        comparison = auditor.compare(null, null);
                        if (!comparison.errorCodes.contains(ex.code)) {
                            comparison.errorCodes.add(ex.code);
                        }
                    }
                    List<IRI> focus = new ArrayList<IRI>();
                    IRI agent = ensureAgent(connection, actor, focus);
                    AuditRecord record = addAudit(connection, publication, actor,
                            agent, comparison.outcome, comparison.missingCount,
                            comparison.unexpectedCount,
                            checkActive ? "ACTIVE" : "SNAPSHOT", checkActive,
                            sourceGraph, targetGraph,
                            new ArrayList<TripleDetails>(comparison.missing),
                            new ArrayList<TripleDetails>(comparison.unexpected),
                            new ArrayList<String>(comparison.errorCodes), focus);
                    shacl.validateVerificationResult(connection, focus,
                            publication);
                    return record.details;
                }
            });
        }
    }

    private void addVersion(RepositoryConnection connection, IRI version,
            String language, String code, String state, IRI snapshot,
            Long snapshotTripleCount, String etag, IRI agent,
            LexIntActor actor, List<IRI> focus) {
        connection.add(version, RDF.TYPE, ENTITY, repository.auditGraph);
        connection.add(version, LANGUAGE, vf.createLiteral(language),
                repository.auditGraph);
        connection.add(version, VERSION_CODE, vf.createLiteral(code),
                repository.auditGraph);
        connection.add(version, VERSION_STATE, vf.createLiteral(state),
                repository.auditGraph);
        if (snapshot != null) {
            connection.add(version, SNAPSHOT_GRAPH, snapshot,
                    repository.auditGraph);
        }
        if (snapshotTripleCount != null) {
            connection.add(version, SNAPSHOT_TRIPLE_COUNT,
                    vf.createLiteral(Long.toString(
                            snapshotTripleCount.longValue()),
                            XSD.NON_NEGATIVE_INTEGER),
                    repository.auditGraph);
        }
        if (etag != null) {
            connection.add(version, ETAG, vf.createLiteral(etag),
                    repository.auditGraph);
        }
        connection.add(version, WAS_ATTRIBUTED_TO, agent,
                repository.auditGraph);
        metadata.createIndividual(connection, version, actor,
                repository.auditGraph);
        focus.add(version);
    }

    private void addPublication(RepositoryConnection connection,
            IRI publication, LexIntPublicationPlanner.Plan plan,
            LexIntActor actor, IRI agent, String idempotencyHash,
            String payloadHash, List<IRI> focus) {
        connection.add(publication, RDF.TYPE, PUBLICATION,
                repository.auditGraph);
        connection.add(publication, USERNAME, vf.createLiteral(actor.username),
                repository.auditGraph);
        connection.add(publication, WAS_ASSOCIATED_WITH, agent,
                repository.auditGraph);
        connection.add(publication, SOURCE_VERSION, plan.sourceVersion,
                repository.auditGraph);
        connection.add(publication, TARGET_VERSION, plan.targetVersion,
                repository.auditGraph);
        connection.add(publication, USED, plan.sourceVersion,
                repository.auditGraph);
        connection.add(publication, LANGUAGE, vf.createLiteral(plan.language),
                repository.auditGraph);
        connection.add(publication, IDEMPOTENCY_HASH,
                vf.createLiteral(idempotencyHash), repository.auditGraph);
        connection.add(publication, PAYLOAD_HASH, vf.createLiteral(payloadHash),
                repository.auditGraph);
        connection.add(publication, ETAG, vf.createLiteral(plan.baseEtag),
                repository.auditGraph);
        connection.add(publication, IDENTITY_ASSURANCE,
                vf.createLiteral(actor.identityAssurance), repository.auditGraph);
        String created = metadata.createIndividual(connection, publication,
                actor, repository.auditGraph);
        Literal time = vf.createLiteral(created, XSD.DATETIME);
        connection.add(publication, STARTED_AT_TIME, time,
                repository.auditGraph);
        connection.add(publication, ENDED_AT_TIME, time,
                repository.auditGraph);
        focus.add(publication);
        int index = 0;
        for (LexIntPublicationPlanner.PlannedOperation planned : plan.operations) {
            IRI operation = uris.mint("operation");
            connection.add(publication, HAS_OPERATION, operation,
                    repository.auditGraph);
            connection.add(operation, RDF.TYPE, OPERATION,
                    repository.auditGraph);
            connection.add(operation, RDF.TYPE,
                    "INSERT".equals(planned.action) ? INSERTION : DELETION,
                    repository.auditGraph);
            connection.add(operation, PUB_TARGET_ASSERTION, planned.target,
                    repository.auditGraph);
            connection.add(operation, AUTHORIZED_BY, planned.decision,
                    repository.auditGraph);
            connection.add(operation, OPERATION_INDEX,
                    vf.createLiteral(index++), repository.auditGraph);
            metadata.createIndividual(connection, operation, actor,
                    repository.auditGraph);
            focus.add(operation);
        }
    }

    private void completeVersionLedger(RepositoryConnection connection,
            LexIntPublicationPlanner.Plan plan, IRI publication,
            String targetEtag, LexIntActor actor) {
        connection.remove(plan.sourceVersion, VERSION_STATE, null,
                repository.auditGraph);
        connection.add(plan.sourceVersion, VERSION_STATE,
                vf.createLiteral("PUBLISHED"), repository.auditGraph);
        connection.remove(plan.targetVersion, VERSION_STATE, null,
                repository.auditGraph);
        connection.add(plan.targetVersion, VERSION_STATE,
                vf.createLiteral("CURRENT"), repository.auditGraph);
        connection.add(plan.targetVersion, SNAPSHOT_GRAPH,
                plan.targetSnapshotGraph, repository.auditGraph);
        connection.add(plan.targetVersion, SNAPSHOT_TRIPLE_COUNT,
                vf.createLiteral(Long.toString(plan.expected.size()),
                        XSD.NON_NEGATIVE_INTEGER),
                repository.auditGraph);
        connection.add(plan.targetVersion, ETAG, vf.createLiteral(targetEtag),
                repository.auditGraph);
        connection.add(plan.targetVersion, WAS_REVISION_OF, plan.sourceVersion,
                repository.auditGraph);
        connection.add(plan.targetVersion, WAS_GENERATED_BY, publication,
                repository.auditGraph);
        metadata.touch(connection, plan.sourceVersion, actor,
                repository.auditGraph);
        metadata.touch(connection, plan.targetVersion, actor,
                repository.auditGraph);
    }

    private boolean snapshotInputUnavailable(RepositoryConnection connection,
            IRI version, IRI graph) {
        String recorded = optionalLiteral(connection, version,
                SNAPSHOT_TRIPLE_COUNT, repository.auditGraph);
        if (recorded == null) {
            // Backward-compatible fallback for ledgers created before snapshot
            // cardinality was recorded.
            return false;
        }
        long expected;
        try {
            expected = Long.parseLong(recorded);
        } catch (NumberFormatException ex) {
            throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                    "Snapshot triple count is invalid");
        }
        return expected > 0L
                && !connection.hasStatement(null, null, null, false, graph);
    }

    private AuditRecord addAudit(RepositoryConnection connection,
            IRI publication, LexIntActor actor, IRI agent, String outcome,
            long missing, long unexpected, String scope, boolean checkActive,
            IRI sourceGraph, IRI targetGraph,
            List<TripleDetails> missingItems,
            List<TripleDetails> unexpectedItems, List<String> errorCodes,
            List<IRI> focus) {
        IRI verification = uris.mint("verification");
        IRI report = uris.mint("report");
        connection.add(verification, RDF.TYPE, VERIFICATION,
                repository.auditGraph);
        connection.add(verification, USERNAME, vf.createLiteral(actor.username),
                repository.auditGraph);
        connection.add(verification, IDENTITY_ASSURANCE,
                vf.createLiteral(actor.identityAssurance),
                repository.auditGraph);
        connection.add(verification, WAS_ASSOCIATED_WITH, agent,
                repository.auditGraph);
        connection.add(verification, VERIFIES_PUBLICATION, publication,
                repository.auditGraph);
        connection.add(verification, HAS_REPORT, report,
                repository.auditGraph);
        connection.add(report, RDF.TYPE, AUDIT_REPORT, repository.auditGraph);
        connection.add(report, HAS_OUTCOME, outcomeIri(outcome),
                repository.auditGraph);
        connection.add(report, MISSING_COUNT,
                vf.createLiteral(Long.toString(missing),
                        XSD.NON_NEGATIVE_INTEGER),
                repository.auditGraph);
        connection.add(report, UNEXPECTED_COUNT,
                vf.createLiteral(Long.toString(unexpected),
                        XSD.NON_NEGATIVE_INTEGER),
                repository.auditGraph);
        connection.add(report, AUDIT_SCOPE, vf.createLiteral(scope),
                repository.auditGraph);
        connection.add(report, CHECKED_ACTIVE, vf.createLiteral(checkActive),
                repository.auditGraph);
        AuditPayload payload = new AuditPayload();
        payload.outcome = outcome; payload.missing = missingItems;
        payload.unexpected = unexpectedItems;
        payload.errorCodes = errorCodes;
        connection.add(report, DCTERMS.DESCRIPTION,
                vf.createLiteral(LexIntJsonCodec.write(payload)),
                repository.auditGraph);
        String checked = metadata.createIndividual(connection, verification,
                actor, repository.auditGraph);
        metadata.createIndividual(connection, report, actor,
                repository.auditGraph);
        Literal time = vf.createLiteral(checked, XSD.DATETIME);
        connection.add(verification, STARTED_AT_TIME, time,
                repository.auditGraph);
        connection.add(verification, ENDED_AT_TIME, time,
                repository.auditGraph);
        focus.add(verification); focus.add(report);
        AuditReportDetails details = auditDetails(connection, verification,
                report, publication);
        details.sourceSnapshotGraphIri = sourceGraph == null ? null
                : sourceGraph.stringValue();
        details.targetSnapshotGraphIri = targetGraph == null ? null
                : targetGraph.stringValue();
        details.identityAssurance = actor.identityAssurance;
        return new AuditRecord(verification, report, details);
    }

    private Model reconstructExpected(RepositoryConnection connection,
            IRI publication, IRI sourceVersion, IRI targetVersion,
            Model source) {
        metadata.validate(connection, publication, repository.auditGraph);
        metadata.validate(connection, sourceVersion, repository.auditGraph);
        metadata.validate(connection, targetVersion, repository.auditGraph);
        Model expected = new LinkedHashModel(); expected.addAll(source);
        List<IRI> operations = requiredIriObjects(connection, publication,
                HAS_OPERATION, repository.auditGraph);
        if (operations.isEmpty()) {
            throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                    "Publication has no operations");
        }
        Set<Statement> statements = new HashSet<Statement>();
        for (IRI operation : operations) {
            requireType(connection, operation, OPERATION,
                    repository.auditGraph);
            metadata.validate(connection, operation, repository.auditGraph);
            boolean insertion = connection.hasStatement(operation, RDF.TYPE,
                    INSERTION, false, repository.auditGraph);
            boolean deletion = connection.hasStatement(operation, RDF.TYPE,
                    DELETION, false, repository.auditGraph);
            if (insertion == deletion) {
                throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                        "Historical operation kind is ambiguous");
            }
            IRI target = objectIri(connection, operation,
                    PUB_TARGET_ASSERTION, repository.auditGraph);
            requireType(connection, target, TARGET_ASSERTION,
                    repository.recordsGraph);
            metadata.validate(connection, target, repository.recordsGraph);
            if (connection.hasStatement(target, vf.createIRI(RDF_REIFIES),
                    null, false, repository.recordsGraph)) {
                throw new LexIntException(422, "LEXINT_RDF12_UNSUPPORTED",
                        "Historical RDF 1.2 target cannot be reconstructed by runtime v1");
            }
            IRI decision = objectIri(connection, operation, AUTHORIZED_BY,
                    repository.auditGraph);
            requireType(connection, decision, LEXICOGRAPHIC_DECISION,
                    repository.recordsGraph);
            metadata.validate(connection, decision, repository.recordsGraph);
            if (!connection.hasStatement(decision, ENDED_AT_TIME, null, false,
                    repository.recordsGraph)
                    || !connection.hasStatement(decision, APPLIES_TO_VERSION,
                            targetVersion, false, repository.recordsGraph)) {
                throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                        "Historical decision is not finalized for the publication version");
            }
            IRI claim = objectIri(connection, decision, CONCERNS_CLAIM,
                    repository.recordsGraph);
            requireType(connection, claim, INTERPRETIVE_CLAIM,
                    repository.recordsGraph);
            metadata.validate(connection, claim, repository.recordsGraph);
            if (!connection.hasStatement(claim, TARGET_ASSERTION_PROPERTY,
                    target, false, repository.recordsGraph)) {
                throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                        "Historical target is not linked to the authorizing claim");
            }
            IRI decisionOutcome = objectIri(connection, decision,
                    DECISION_OUTCOME, repository.recordsGraph);
            IRI subject = objectIri(connection, target, RDF.SUBJECT,
                    repository.recordsGraph);
            IRI predicate = objectIri(connection, target, RDF.PREDICATE,
                    repository.recordsGraph);
            Value object = object(connection, target, RDF.OBJECT,
                    repository.recordsGraph);
            if (!(object instanceof IRI) && !(object instanceof Literal)) {
                throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                        "Historical target object is not an IRI or literal");
            }
            Statement statement = vf.createStatement(subject, predicate, object);
            if (!statements.add(statement)) {
                throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                        "Historical operations overlap on one RDF statement");
            }
            if (insertion) {
                if (!ADOPTED.equals(decisionOutcome)) {
                    throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                            "Historical insertion lacks an adopted decision");
                }
                expected.add(statement);
            } else if (deletion) {
                if (!REJECTED.equals(decisionOutcome)
                        && !REPLACED.equals(decisionOutcome)) {
                    throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                            "Historical deletion lacks a rejected or replaced decision");
                }
                expected.remove(statement);
            }
        }
        return expected;
    }

    private PublicationPreview preview(LexIntPublicationPlanner.Plan plan,
            LexIntActor actor) {
        PublicationPreview result = new PublicationPreview();
        result.status = "READY"; result.language = plan.language;
        result.sourceVersionIri = plan.sourceVersion.stringValue();
        result.targetVersionIri = plan.targetVersion.stringValue();
        result.baseEtag = plan.baseEtag;
        result.expectedInsertions = plan.insertions;
        result.expectedDeletions = plan.deletions;
        result.partialClaims = plan.partialClaims;
        result.warnings.addAll(plan.warnings);
        result.identityAssurance = actor.identityAssurance;
        result.username = actor.username;
        int index = 0;
        for (LexIntPublicationPlanner.PlannedOperation item : plan.operations) {
            PublicationOperationDetails operation =
                    new PublicationOperationDetails();
            operation.action = item.action;
            operation.targetAssertionIri = item.target.stringValue();
            operation.decisionIri = item.decision.stringValue();
            operation.order = index++;
            result.operations.add(operation);
        }
        return result;
    }

    private PublicationDetails publicationDetails(
            RepositoryConnection connection, IRI publication,
            String requestId) {
        PublicationDetails result = new PublicationDetails();
        result.id = uris.id(publication); result.iri = publication.stringValue();
        result.status = "PUBLISHED";
        result.username = literal(connection, publication, USERNAME,
                repository.auditGraph);
        result.language = literal(connection, publication, LANGUAGE,
                repository.auditGraph);
        result.identityAssurance = literal(connection, publication,
                IDENTITY_ASSURANCE, repository.auditGraph);
        IRI source = objectIri(connection, publication, SOURCE_VERSION,
                repository.auditGraph);
        IRI target = objectIri(connection, publication, TARGET_VERSION,
                repository.auditGraph);
        result.sourceVersionIri = source.stringValue();
        result.targetVersionIri = target.stringValue();
        result.baseEtag = literal(connection, publication, ETAG,
                repository.auditGraph);
        result.targetEtag = optionalLiteral(connection, target, ETAG,
                repository.auditGraph);
        result.creator = metadata.creator(connection, publication,
                repository.auditGraph);
        result.created = metadata.created(connection, publication,
                repository.auditGraph);
        result.modified = metadata.modified(connection, publication,
                repository.auditGraph);
        result.requestId = requestId;
        for (IRI operation : orderedOperations(connection, publication)) {
            PublicationOperationDetails details = new PublicationOperationDetails();
            details.id = uris.id(operation); details.iri = operation.stringValue();
            details.action = connection.hasStatement(operation, RDF.TYPE,
                    INSERTION, false, repository.auditGraph) ? "INSERT" : "DELETE";
            details.targetAssertionIri = objectIri(connection, operation,
                    PUB_TARGET_ASSERTION, repository.auditGraph).stringValue();
            details.decisionIri = objectIri(connection, operation, AUTHORIZED_BY,
                    repository.auditGraph).stringValue();
            details.order = integer(connection, operation, OPERATION_INDEX,
                    repository.auditGraph);
            details.creator = metadata.creator(connection, operation,
                    repository.auditGraph);
            details.created = metadata.created(connection, operation,
                    repository.auditGraph);
            details.modified = metadata.modified(connection, operation,
                    repository.auditGraph);
            result.operations.add(details);
            if ("INSERT".equals(details.action)) { result.expectedInsertions++; }
            else { result.expectedDeletions++; }
        }
        result.audit.addAll(auditHistory(connection, publication));
        if (!result.audit.isEmpty()) {
            AuditReportDetails last = result.audit.get(result.audit.size() - 1);
            result.auditStatus = last.outcome;
            result.missingTripleCount = last.missingTripleCount;
            result.unexpectedTripleCount = last.unexpectedTripleCount;
        }
        return result;
    }

    private List<AuditReportDetails> auditHistory(
            RepositoryConnection connection, IRI publication) {
        List<AuditReportDetails> results = new ArrayList<AuditReportDetails>();
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                VERIFIES_PUBLICATION, publication, false,
                repository.auditGraph)) {
            while (rows.hasNext()) {
                Resource resource = rows.next().getSubject();
                if (!(resource instanceof IRI)) { continue; }
                IRI verification = (IRI) resource;
                for (IRI report : iriObjects(connection, verification,
                        HAS_REPORT, repository.auditGraph)) {
                    results.add(auditDetails(connection, verification, report,
                            publication));
                }
            }
        }
        Collections.sort(results, new Comparator<AuditReportDetails>() {
            @Override public int compare(AuditReportDetails left,
                    AuditReportDetails right) {
                int checked = left.checkedAt.compareTo(right.checkedAt);
                return checked != 0 ? checked
                        : left.verificationIri.compareTo(right.verificationIri);
            }
        });
        return results;
    }

    private AuditReportDetails auditDetails(RepositoryConnection connection,
            IRI verification, IRI report, IRI publication) {
        AuditReportDetails details = new AuditReportDetails();
        details.verificationId = uris.id(verification);
        details.verificationIri = verification.stringValue();
        details.reportId = uris.id(report); details.reportIri = report.stringValue();
        details.publicationIri = publication.stringValue();
        details.outcome = outcomeName(objectIri(connection, report,
                HAS_OUTCOME, repository.auditGraph));
        details.missingTripleCount = longValue(connection, report,
                MISSING_COUNT, repository.auditGraph);
        details.unexpectedTripleCount = longValue(connection, report,
                UNEXPECTED_COUNT, repository.auditGraph);
        details.auditScope = literal(connection, report, AUDIT_SCOPE,
                repository.auditGraph);
        details.checkedAt = metadata.created(connection, verification,
                repository.auditGraph);
        details.username = literal(connection, verification, USERNAME,
                repository.auditGraph);
        details.identityAssurance = optionalLiteral(connection, verification,
                IDENTITY_ASSURANCE, repository.auditGraph);
        details.creator = metadata.creator(connection, report,
                repository.auditGraph);
        details.created = metadata.created(connection, report,
                repository.auditGraph);
        details.modified = metadata.modified(connection, report,
                repository.auditGraph);
        IRI source = objectIri(connection, publication, SOURCE_VERSION,
                repository.auditGraph);
        IRI target = objectIri(connection, publication, TARGET_VERSION,
                repository.auditGraph);
        IRI sourceGraph = optionalIri(connection, source, SNAPSHOT_GRAPH,
                repository.auditGraph);
        IRI targetGraph = optionalIri(connection, target, SNAPSHOT_GRAPH,
                repository.auditGraph);
        details.sourceSnapshotGraphIri = sourceGraph == null ? null
                : sourceGraph.stringValue();
        details.targetSnapshotGraphIri = targetGraph == null ? null
                : targetGraph.stringValue();
        String payloadValue = optionalLiteral(connection, report,
                DCTERMS.DESCRIPTION, repository.auditGraph);
        if (payloadValue != null) {
            AuditPayload payload = LexIntJsonCodec.read(payloadValue,
                    AuditPayload.class);
            if (payload.missing != null) {
                details.missingTriples.addAll(payload.missing);
            }
            if (payload.unexpected != null) {
                details.unexpectedTriples.addAll(payload.unexpected);
            }
            if (payload.errorCodes != null) {
                details.errorCodes.addAll(payload.errorCodes);
            }
        }
        return details;
    }

    private VersionDetails versionDetails(RepositoryConnection connection,
            IRI version, String assurance) {
        VersionDetails result = new VersionDetails();
        result.id = uris.id(version); result.iri = version.stringValue();
        result.language = literal(connection, version, LANGUAGE,
                repository.auditGraph);
        result.versionCode = literal(connection, version, VERSION_CODE,
                repository.auditGraph);
        result.state = literal(connection, version, VERSION_STATE,
                repository.auditGraph);
        IRI snapshot = optionalIri(connection, version, SNAPSHOT_GRAPH,
                repository.auditGraph);
        result.snapshotGraphIri = snapshot == null ? null
                : snapshot.stringValue();
        IRI predecessor = optionalIri(connection, version, WAS_REVISION_OF,
                repository.auditGraph);
        result.predecessorIri = predecessor == null ? null
                : predecessor.stringValue();
        IRI publication = optionalIri(connection, version, WAS_GENERATED_BY,
                repository.auditGraph);
        result.publicationIri = publication == null ? null
                : publication.stringValue();
        result.creator = metadata.creator(connection, version,
                repository.auditGraph);
        result.created = metadata.created(connection, version,
                repository.auditGraph);
        result.modified = metadata.modified(connection, version,
                repository.auditGraph);
        result.etag = optionalLiteral(connection, version, ETAG,
                repository.auditGraph);
        result.identityAssurance = assurance;
        return result;
    }

    private IRI ensureAgent(RepositoryConnection connection, LexIntActor actor,
            List<IRI> focus) {
        IRI agent = uris.agent(actor);
        if (!connection.hasStatement(agent, null, null, false,
                repository.auditGraph)) {
            connection.add(agent, RDF.TYPE, AGENT, repository.auditGraph);
            metadata.createIndividual(connection, agent, actor,
                    repository.auditGraph);
            focus.add(agent);
        } else {
            requireType(connection, agent, AGENT, repository.auditGraph);
            metadata.validate(connection, agent, repository.auditGraph);
        }
        return agent;
    }

    private IRI currentVersion(RepositoryConnection connection,
            String language) {
        IRI found = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                VERSION_STATE, vf.createLiteral("CURRENT"), false,
                repository.auditGraph)) {
            while (rows.hasNext()) {
                Resource subject = rows.next().getSubject();
                if (subject instanceof IRI && connection.hasStatement(subject,
                        LANGUAGE, vf.createLiteral(language), false,
                        repository.auditGraph)) {
                    if (found != null) {
                        throw new LexIntException(409,
                                "LEXINT_DATA_INCONSISTENT",
                                "More than one current version exists");
                    }
                    found = (IRI) subject;
                }
            }
        }
        return found;
    }

    private IRI baselineVersion(RepositoryConnection connection,
            String language) {
        IRI found = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                VERSION_CODE, vf.createLiteral("v000001"), false,
                repository.auditGraph)) {
            while (rows.hasNext()) {
                Resource subject = rows.next().getSubject();
                if (subject instanceof IRI && connection.hasStatement(subject,
                        LANGUAGE, vf.createLiteral(language), false,
                        repository.auditGraph)) {
                    if (found != null) {
                        throw new LexIntException(409,
                                "LEXINT_DATA_INCONSISTENT",
                                "More than one baseline version exists");
                    }
                    found = (IRI) subject;
                }
            }
        }
        return found;
    }

    private boolean hasAnyVersion(RepositoryConnection connection,
            String language) {
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                LANGUAGE, vf.createLiteral(language), false,
                repository.auditGraph)) {
            while (rows.hasNext()) {
                if (connection.hasStatement(rows.next().getSubject(), VERSION_CODE,
                        null, false, repository.auditGraph)) { return true; }
            }
        }
        return false;
    }

    private String nextVersionCode(RepositoryConnection connection,
            String language) {
        int maximum = 0;
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                LANGUAGE, vf.createLiteral(language), false,
                repository.auditGraph)) {
            while (rows.hasNext()) {
                Resource version = rows.next().getSubject();
                String code = optionalLiteral(connection, version, VERSION_CODE,
                        repository.auditGraph);
                if (code != null && code.matches("v[0-9]{6}")) {
                    maximum = Math.max(maximum,
                            Integer.parseInt(code.substring(1)));
                }
            }
        }
        if (maximum >= 999999) {
            throw new LexIntException(409, "LEXINT_VERSION_EXHAUSTED",
                    "Version code space is exhausted");
        }
        return String.format(Locale.ROOT, "v%06d", maximum + 1);
    }

    private IRI findPublicationByIdempotency(RepositoryConnection connection,
            String hash) {
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                IDEMPOTENCY_HASH, vf.createLiteral(hash), false,
                repository.auditGraph)) {
            while (rows.hasNext()) {
                Resource subject = rows.next().getSubject();
                if (subject instanceof IRI && connection.hasStatement(subject,
                        RDF.TYPE, PUBLICATION, false, repository.auditGraph)) {
                    return (IRI) subject;
                }
            }
        }
        return null;
    }

    private String publicationFingerprint(PublicationRequest request,
            LexIntActor actor) {
        if (request == null || request.operations == null) {
            throw LexIntException.invalid("Publication body and operations are required");
        }
        List<String> operations = new ArrayList<String>();
        for (PublicationOperationInput item : request.operations) {
            if (item == null) { throw LexIntException.invalid(
                    "Publication operations must be objects"); }
            operations.add(normalize(item.action).toUpperCase(Locale.ROOT) + "|"
                    + normalize(item.targetAssertionIri) + "|"
                    + normalize(item.decisionIri));
        }
        Collections.sort(operations);
        return LexIntResourceUris.sha256(
                LexIntResourceUris.language(request.language) + "|"
                + normalize(request.sourceVersionIri) + "|"
                + normalize(request.targetVersionIri) + "|"
                + uris.agent(actor).stringValue() + "|"
                + operations.toString());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private IRI inputIri(String value, String field) {
        String normalized = normalize(value);
        try {
            java.net.URI parsed = new java.net.URI(normalized);
            if (!parsed.isAbsolute()) {
                throw new java.net.URISyntaxException(normalized,
                        "not absolute");
            }
            return vf.createIRI(normalized);
        } catch (java.net.URISyntaxException | IllegalArgumentException ex) {
            throw LexIntException.invalid(field + " must be an absolute IRI");
        }
    }

    private List<IRI> orderedOperations(RepositoryConnection connection,
            IRI publication) {
        List<IRI> values = iriObjects(connection, publication, HAS_OPERATION,
                repository.auditGraph);
        Collections.sort(values, new Comparator<IRI>() {
            @Override public int compare(IRI left, IRI right) {
                int leftIndex = integer(connection, left, OPERATION_INDEX,
                        repository.auditGraph);
                int rightIndex = integer(connection, right, OPERATION_INDEX,
                        repository.auditGraph);
                return Integer.compare(leftIndex, rightIndex);
            }
        });
        return values;
    }

    private List<IRI> iriObjects(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        List<IRI> values = new ArrayList<IRI>();
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                Value value = rows.next().getObject();
                if (value instanceof IRI) { values.add((IRI) value); }
            }
        }
        return values;
    }

    private List<IRI> requiredIriObjects(RepositoryConnection connection,
            IRI subject, IRI predicate, Resource graph) {
        List<IRI> values = new ArrayList<IRI>();
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                Value value = rows.next().getObject();
                if (!(value instanceof IRI)) {
                    throw new LexIntException(409,
                            "LEXINT_DATA_INCONSISTENT",
                            "Historical relation must contain only IRIs");
                }
                values.add((IRI) value);
            }
        }
        return values;
    }

    private void requireType(RepositoryConnection connection, IRI resource,
            IRI type, Resource graph) {
        if (!connection.hasStatement(resource, RDF.TYPE, type, false, graph)) {
            throw LexIntException.notFound(resource.stringValue()
                    + " was not found in the required graph");
        }
    }

    private IRI objectIri(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        Value value = object(connection, subject, predicate, graph);
        if (!(value instanceof IRI)) {
            throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                    "Required IRI property is invalid");
        }
        return (IRI) value;
    }

    private IRI optionalIri(RepositoryConnection connection, Resource subject,
            IRI predicate, Resource graph) {
        Value value = optionalObject(connection, subject, predicate, graph);
        if (value == null) { return null; }
        if (!(value instanceof IRI)) {
            throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                    "IRI property is invalid");
        }
        return (IRI) value;
    }

    private Value object(RepositoryConnection connection, Resource subject,
            IRI predicate, Resource graph) {
        Value value = optionalObject(connection, subject, predicate, graph);
        if (value == null) { throw new LexIntException(409,
                "LEXINT_DATA_INCONSISTENT", "Required property is missing"); }
        return value;
    }

    private Value optionalObject(RepositoryConnection connection,
            Resource subject, IRI predicate, Resource graph) {
        Value result = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                if (result != null) { throw new LexIntException(409,
                        "LEXINT_DATA_INCONSISTENT",
                        "Property cardinality is invalid"); }
                result = rows.next().getObject();
            }
        }
        return result;
    }

    private String literal(RepositoryConnection connection, Resource subject,
            IRI predicate, Resource graph) {
        String value = optionalLiteral(connection, subject, predicate, graph);
        if (value == null) { throw new LexIntException(409,
                "LEXINT_DATA_INCONSISTENT", "Required literal is missing"); }
        return value;
    }

    private String optionalLiteral(RepositoryConnection connection,
            Resource subject, IRI predicate, Resource graph) {
        Value value = optionalObject(connection, subject, predicate, graph);
        if (value == null) { return null; }
        if (!(value instanceof Literal)) { throw new LexIntException(409,
                "LEXINT_DATA_INCONSISTENT", "Literal property is invalid"); }
        return value.stringValue();
    }

    private int integer(RepositoryConnection connection, Resource subject,
            IRI predicate, Resource graph) {
        return Integer.parseInt(literal(connection, subject, predicate, graph));
    }

    private long longValue(RepositoryConnection connection, Resource subject,
            IRI predicate, Resource graph) {
        return Long.parseLong(literal(connection, subject, predicate, graph));
    }

    private IRI outcomeIri(String outcome) {
        if ("PASS".equals(outcome)) { return PASS; }
        if ("FAIL".equals(outcome)) { return FAIL; }
        return INDETERMINATE;
    }

    private String outcomeName(IRI outcome) {
        if (PASS.equals(outcome)) { return "PASS"; }
        if (FAIL.equals(outcome)) { return "FAIL"; }
        return "INDETERMINATE";
    }

    private Instant parseTime(String value) {
        if (value == null || value.trim().isEmpty()) { return null; }
        try { return Instant.parse(value.trim()); }
        catch (RuntimeException ex) { throw LexIntException.invalid(
                "from/to must be UTC ISO 8601 timestamps"); }
    }

    private void pagination(int limit, int offset) {
        if (limit < 1 || limit > LexIntResourceUris.limit(
                "LexInt.list.maxLimit", 500) || offset < 0) {
            throw LexIntException.invalid("Invalid pagination");
        }
    }

    private <T> LexIntPage<T> page(List<T> values, int limit, int offset) {
        LexIntPage<T> page = new LexIntPage<T>();
        page.total = values.size(); page.limit = limit; page.offset = offset;
        if (offset < values.size()) {
            page.items.addAll(values.subList(offset,
                    Math.min(values.size(), offset + limit)));
        }
        return page;
    }

    public static final class VersionCreation {
        public final VersionDetails version;
        public final boolean replayed;
        VersionCreation(VersionDetails version, boolean replayed) {
            this.version = version; this.replayed = replayed;
        }
    }

    private static final class AuditRecord {
        final IRI verification; final IRI report;
        final AuditReportDetails details;
        AuditRecord(IRI verification, IRI report, AuditReportDetails details) {
            this.verification = verification; this.report = report;
            this.details = details;
        }
    }

    public static final class AuditPayload {
        public String outcome;
        public List<TripleDetails> missing;
        public List<TripleDetails> unexpected;
        public List<String> errorCodes;
    }
}
