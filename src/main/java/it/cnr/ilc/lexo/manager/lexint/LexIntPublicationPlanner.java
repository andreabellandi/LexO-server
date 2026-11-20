package it.cnr.ilc.lexo.manager.lexint;

import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.*;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationOperationInput;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Explicit per-target publication authorization and delta construction. */
public final class LexIntPublicationPlanner {
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final LexIntRepository repository;
    private final LexIntMetadataWriter metadata;
    private final LexIntShaclValidator shacl;

    public LexIntPublicationPlanner(LexIntRepository repository,
            LexIntMetadataWriter metadata) {
        this.repository = repository;
        this.metadata = metadata;
        this.shacl = new LexIntShaclValidator(repository);
    }

    public Plan plan(RepositoryConnection connection, PublicationRequest request) {
        if (request == null) {
            throw LexIntException.invalid("Publication body is required");
        }
        Plan plan = new Plan();
        plan.language = LexIntResourceUris.language(request.language);
        plan.sourceVersion = iri(request.sourceVersionIri,
                "sourceVersionIri");
        plan.targetVersion = iri(request.targetVersionIri,
                "targetVersionIri");
        if (plan.sourceVersion.equals(plan.targetVersion)) {
            throw LexIntException.invalid(
                    "Source and target versions must be different");
        }
        requireVersion(connection, plan.sourceVersion, plan.language, "CURRENT");
        requireVersion(connection, plan.targetVersion, plan.language, "RESERVED");
        plan.sourceSnapshotGraph = objectIri(connection, plan.sourceVersion,
                SNAPSHOT_GRAPH, repository.auditGraph);
        plan.targetSnapshotGraph = targetSnapshot(plan.language,
                literal(connection, plan.targetVersion, VERSION_CODE,
                        repository.auditGraph));
        plan.activeGraph = vf.createIRI(
                LexiconCrudSupport.lexicalGraphUri(plan.language));
        plan.baseEtag = literal(connection, plan.sourceVersion, ETAG,
                repository.auditGraph);
        metadata.validate(connection, plan.sourceVersion, repository.auditGraph);
        metadata.validate(connection, plan.targetVersion, repository.auditGraph);
        plan.source = repository.model(connection, plan.sourceSnapshotGraph);
        Model active = repository.model(connection, plan.activeGraph);
        if (!Models.isomorphic(plan.source, active)) {
            throw new LexIntException(409, "LEXINT_ACTIVE_GRAPH_DRIFT",
                    "Active lexical graph diverges from its current snapshot");
        }
        if (request.operations == null || request.operations.isEmpty()) {
            throw LexIntException.invalid(
                    "At least one explicit publication operation is required");
        }
        if (request.operations.size() > LexIntResourceUris.limit(
                "LexInt.write.maxOperations", 1000)) {
            throw new LexIntException(413, "LEXINT_LIMIT_EXCEEDED",
                    "Too many publication operations");
        }
        Set<String> targets = new HashSet<String>();
        Set<Statement> deltaStatements = new HashSet<Statement>();
        Map<IRI, Set<IRI>> selectedByClaim = new HashMap<IRI, Set<IRI>>();
        for (PublicationOperationInput input : request.operations) {
            PlannedOperation operation = operation(connection, input,
                    plan.targetVersion, plan.source);
            if (!targets.add(operation.target.stringValue())) {
                throw new LexIntException(422,
                        "LEXINT_AMBIGUOUS_AUTHORIZATION",
                        "Each target may occur only once in a publication plan");
            }
            if (!deltaStatements.add(operation.statement)) {
                throw new LexIntException(422,
                        "LEXINT_AMBIGUOUS_AUTHORIZATION",
                        "Publication operations must not overlap on the same RDF statement");
            }
            plan.operations.add(operation);
            Set<IRI> selected = selectedByClaim.get(operation.claim);
            if (selected == null) {
                selected = new HashSet<IRI>();
                selectedByClaim.put(operation.claim, selected);
            }
            selected.add(operation.target);
        }
        Collections.sort(plan.operations, new Comparator<PlannedOperation>() {
            @Override public int compare(PlannedOperation left,
                    PlannedOperation right) {
                int action = left.action.compareTo(right.action);
                if (action != 0) { return action; }
                int target = left.target.stringValue().compareTo(
                        right.target.stringValue());
                return target != 0 ? target : left.decision.stringValue()
                        .compareTo(right.decision.stringValue());
            }
        });
        plan.expected = new LinkedHashModel();
        plan.expected.addAll(plan.source);
        for (PlannedOperation operation : plan.operations) {
            if ("DELETE".equals(operation.action)) {
                plan.expected.remove(operation.statement);
                plan.deletions++;
            } else {
                plan.expected.add(operation.statement);
                plan.insertions++;
            }
        }
        for (Map.Entry<IRI, Set<IRI>> item : selectedByClaim.entrySet()) {
            int all = countTargets(connection, item.getKey());
            if (item.getValue().size() < all) {
                plan.partialClaims = true;
                plan.warnings.add("Claim " + item.getKey().stringValue()
                        + " has unselected targets");
            }
        }
        shacl.validate(connection, Collections.<IRI>emptyList());
        return plan;
    }

    private PlannedOperation operation(RepositoryConnection connection,
            PublicationOperationInput input, IRI targetVersion, Model source) {
        if (input == null) {
            throw LexIntException.invalid(
                    "Publication operations must be objects");
        }
        String action = text(input.action, "action").toUpperCase();
        if (!"INSERT".equals(action) && !"DELETE".equals(action)) {
            throw LexIntException.invalid("action must be INSERT or DELETE");
        }
        IRI target = iri(input.targetAssertionIri, "targetAssertionIri");
        IRI decision = iri(input.decisionIri, "decisionIri");
        requireType(connection, target, TARGET_ASSERTION,
                repository.recordsGraph);
        requireType(connection, decision, LEXICOGRAPHIC_DECISION,
                repository.recordsGraph);
        metadata.validate(connection, target, repository.recordsGraph);
        metadata.validate(connection, decision, repository.recordsGraph);
        if (!connection.hasStatement(decision, ENDED_AT_TIME, null, false,
                repository.recordsGraph)) {
            throw new LexIntException(422, "LEXINT_DECISION_NOT_ELIGIBLE",
                    "Publication requires a finalized decision");
        }
        if (!connection.hasStatement(decision, APPLIES_TO_VERSION,
                targetVersion, false, repository.recordsGraph)) {
            throw new LexIntException(422, "LEXINT_DECISION_NOT_ELIGIBLE",
                    "Decision applies to a different target version");
        }
        IRI claim = objectIri(connection, decision, CONCERNS_CLAIM,
                repository.recordsGraph);
        requireType(connection, claim, INTERPRETIVE_CLAIM,
                repository.recordsGraph);
        metadata.validate(connection, claim, repository.recordsGraph);
        if (!connection.hasStatement(claim, TARGET_ASSERTION_PROPERTY, target,
                false, repository.recordsGraph)) {
            throw new LexIntException(422, "LEXINT_DECISION_NOT_ELIGIBLE",
                    "Target does not belong to the decision claim");
        }
        IRI outcome = objectIri(connection, decision, DECISION_OUTCOME,
                repository.recordsGraph);
        if ("INSERT".equals(action) && !ADOPTED.equals(outcome)) {
            throw new LexIntException(422, "LEXINT_DECISION_NOT_ELIGIBLE",
                    "INSERT requires a finalized adopted decision");
        }
        if ("DELETE".equals(action) && !REJECTED.equals(outcome)
                && !REPLACED.equals(outcome)) {
            throw new LexIntException(422, "LEXINT_DECISION_NOT_ELIGIBLE",
                    "DELETE requires a finalized rejected or replaced decision");
        }
        Statement statement = reifiedStatement(connection, target);
        boolean present = source.contains(statement.getSubject(),
                statement.getPredicate(), statement.getObject());
        if ("INSERT".equals(action) && present
                || "DELETE".equals(action) && !present) {
            throw new LexIntException(422, "LEXINT_DECISION_NOT_ELIGIBLE",
                    "Requested operation is a no-op in the source version");
        }
        return new PlannedOperation(action, target, decision, claim, outcome,
                statement);
    }

    private Statement reifiedStatement(RepositoryConnection connection,
            IRI target) {
        if (connection.hasStatement(target, vf.createIRI(RDF_REIFIES), null,
                false, repository.recordsGraph)) {
            throw new LexIntException(422, "LEXINT_RDF12_UNSUPPORTED",
                    "RDF 1.2 triple terms are unsupported in runtime v1");
        }
        IRI subject = objectIri(connection, target, RDF.SUBJECT,
                repository.recordsGraph);
        IRI predicate = objectIri(connection, target, RDF.PREDICATE,
                repository.recordsGraph);
        Value object = object(connection, target, RDF.OBJECT,
                repository.recordsGraph);
        if (!(object instanceof IRI) && !(object instanceof org.eclipse.rdf4j.model.Literal)) {
            throw new LexIntException(422, "LEXINT_INVALID_SEMANTICS",
                    "Target object must be an IRI or literal");
        }
        return vf.createStatement(subject, predicate, object);
    }

    private void requireVersion(RepositoryConnection connection, IRI version,
            String language, String state) {
        requireType(connection, version, ENTITY, repository.auditGraph);
        if (!connection.hasStatement(version, LANGUAGE,
                vf.createLiteral(language), false, repository.auditGraph)
                || !connection.hasStatement(version, VERSION_STATE,
                        vf.createLiteral(state), false,
                        repository.auditGraph)) {
            throw new LexIntException(412, "LEXINT_BASE_VERSION_MISMATCH",
                    "Version language or state does not match the request");
        }
    }

    private IRI targetSnapshot(String language, String code) {
        String base = repository.snapshotsBase;
        return vf.createIRI(base + language + "/" + code);
    }

    private int countTargets(RepositoryConnection connection, IRI claim) {
        int count = 0;
        try (RepositoryResult<Statement> rows = connection.getStatements(claim,
                TARGET_ASSERTION_PROPERTY, null, false,
                repository.recordsGraph)) {
            while (rows.hasNext()) { rows.next(); count++; }
        }
        return count;
    }

    private void requireType(RepositoryConnection connection, IRI resource,
            IRI type, Resource graph) {
        if (!connection.hasStatement(resource, RDF.TYPE, type, false, graph)) {
            throw LexIntException.notFound(resource.stringValue()
                    + " does not exist in the required graph");
        }
    }

    private IRI objectIri(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        Value value = object(connection, subject, predicate, graph);
        if (!(value instanceof IRI)) {
            throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                    "Required IRI is missing or invalid");
        }
        return (IRI) value;
    }

    private Value object(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        Value result = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                if (result != null) {
                    throw new LexIntException(409,
                            "LEXINT_DATA_INCONSISTENT",
                            "Required property has invalid cardinality");
                }
                result = rows.next().getObject();
            }
        }
        if (result == null) {
            throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                    "Required property is missing");
        }
        return result;
    }

    private String literal(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        Value value = object(connection, subject, predicate, graph);
        if (!(value instanceof org.eclipse.rdf4j.model.Literal)) {
            throw new LexIntException(409, "LEXINT_DATA_INCONSISTENT",
                    "Required literal is missing");
        }
        return value.stringValue();
    }

    private IRI iri(String value, String field) {
        String text = text(value, field);
        try {
            URI parsed = new URI(text);
            if (!parsed.isAbsolute()) { throw new URISyntaxException(text,
                    "not absolute"); }
            return vf.createIRI(text);
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw LexIntException.invalid(field + " must be an absolute IRI");
        }
    }

    private String text(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw LexIntException.invalid(field + " is required");
        }
        return value.trim();
    }

    public static final class Plan {
        public String language;
        public IRI sourceVersion;
        public IRI targetVersion;
        public IRI sourceSnapshotGraph;
        public IRI targetSnapshotGraph;
        public IRI activeGraph;
        public String baseEtag;
        public Model source;
        public Model expected;
        public int insertions;
        public int deletions;
        public boolean partialClaims;
        public final List<String> warnings = new ArrayList<String>();
        public final List<PlannedOperation> operations =
                new ArrayList<PlannedOperation>();
    }

    public static final class PlannedOperation {
        public final String action;
        public final IRI target;
        public final IRI decision;
        public final IRI claim;
        public final IRI outcome;
        public final Statement statement;
        PlannedOperation(String action, IRI target, IRI decision, IRI claim,
                IRI outcome, Statement statement) {
            this.action = action; this.target = target;
            this.decision = decision; this.claim = claim;
            this.outcome = outcome; this.statement = statement;
        }
    }
}
