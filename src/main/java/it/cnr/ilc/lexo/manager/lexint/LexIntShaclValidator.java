package it.cnr.ilc.lexo.manager.lexint;

import java.util.Collection;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDF4J;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.sail.shacl.ShaclSail;
import org.eclipse.rdf4j.sail.shacl.ShaclSailConnection;
import org.eclipse.rdf4j.sail.shacl.ShaclSailValidationException;
import org.eclipse.rdf4j.sail.shacl.results.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** In-process SHACL validation over controlled schema/records/audit unions. */
public final class LexIntShaclValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            LexIntShaclValidator.class);
    private static final String SH = "http://www.w3.org/ns/shacl#";
    private static final String RUNTIME_SHAPES =
            "https://lexo.ilc.cnr.it/vocabulary/lexint-management/runtime-shapes#";
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final LexIntRepository repository;

    public LexIntShaclValidator(LexIntRepository repository) {
        this.repository = repository;
    }

    public void validate(RepositoryConnection source,
            Collection<? extends IRI> runtimeFocusNodes) {
        Model shapes = contextModel(source, repository.shapesGraph);
        if (shapes.isEmpty()) {
            throw new LexIntException(503, "LEXINT_SHACL_UNAVAILABLE",
                    "LexInt SHACL resources are not installed");
        }
        addRuntimeMetadataShape(shapes, runtimeFocusNodes);
        Model data = new LinkedHashModel();
        data.addAll(contextModel(source, repository.schemaGraph));
        data.addAll(contextModel(source, repository.recordsGraph));
        data.addAll(contextModel(source, repository.auditGraph));
        requireFocusMetadata(data, runtimeFocusNodes);
        validateCandidateXone(data);
        // RDF4J 3.7 (the Java 8-compatible line used by this WAR) explicitly
        // does not implement XoneConstraintComponent. The equivalent
        // fail-closed check above evaluates that installed constraint over
        // every TargetAssertion; remove only that one constraint from this
        // ephemeral validator model so RDF4J can validate all remaining SHACL
        // components without silently skipping sh:xone.
        shapes.remove(vf.createIRI(
                "https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation/shapes#CandidateShape"),
                sh("xone"), null);

        validateModels(shapes, data);
    }

    /**
     * Validates a newly created verification/report independently from any
     * damaged historical operation that the verification must report as
     * INDETERMINATE rather than silently refusing to persist the audit result.
     */
    public void validateVerificationResult(RepositoryConnection source,
            Collection<? extends IRI> runtimeFocusNodes, IRI publication) {
        Model installed = contextModel(source, repository.shapesGraph);
        if (installed.isEmpty()) {
            throw new LexIntException(503, "LEXINT_SHACL_UNAVAILABLE",
                    "LexInt SHACL resources are not installed");
        }
        IRI verificationShape = vf.createIRI(
                LexIntVocabulary.PUBLICATION_NS + "VerificationShape");
        IRI reportShape = vf.createIRI(
                LexIntVocabulary.PUBLICATION_NS + "AuditReportShape");
        Model shapes = shapeClosure(installed, Arrays.asList(
                verificationShape, reportShape));
        if (!shapes.contains(verificationShape, RDF.TYPE, sh("NodeShape"))
                || !shapes.contains(reportShape, RDF.TYPE, sh("NodeShape"))) {
            throw new LexIntException(503, "LEXINT_SHACL_UNAVAILABLE",
                    "Verification SHACL resources are incomplete");
        }
        addRuntimeMetadataShape(shapes, runtimeFocusNodes);
        Model data = new LinkedHashModel();
        data.addAll(contextModel(source, repository.schemaGraph));
        if (runtimeFocusNodes != null) {
            for (IRI focus : runtimeFocusNodes) {
                addSubjectStatements(source, data, focus,
                        repository.auditGraph);
            }
        }
        if (publication != null) {
            try (RepositoryResult<Statement> rows = source.getStatements(
                    publication, RDF.TYPE, null, false,
                    repository.auditGraph)) {
                while (rows.hasNext()) {
                    Statement statement = rows.next();
                    data.add(statement.getSubject(), statement.getPredicate(),
                            statement.getObject());
                }
            }
        }
        Set<IRI> referenced = new HashSet<IRI>();
        for (Statement statement : new LinkedHashModel(data)) {
            if (statement.getObject() instanceof IRI) {
                referenced.add((IRI) statement.getObject());
            }
        }
        for (IRI resource : referenced) {
            try (RepositoryResult<Statement> rows = source.getStatements(
                    resource, RDF.TYPE, null, false,
                    repository.auditGraph)) {
                while (rows.hasNext()) {
                    Statement statement = rows.next();
                    data.add(statement.getSubject(), statement.getPredicate(),
                            statement.getObject());
                }
            }
        }
        requireFocusMetadata(data, runtimeFocusNodes);
        validateModels(shapes, data);
    }

    private void validateModels(Model shapes, Model data) {

        ShaclSail sail = new ShaclSail(new MemoryStore());
        // RDF4J 3.7 cannot transactionally evaluate sh:xone. Load the
        // controlled dataset with validation disabled, then run its supported
        // full revalidation path so all four installed shape resources are
        // evaluated without weakening or rewriting them.
        sail.disableValidation();
        SailRepository validationRepository = new SailRepository(sail);
        validationRepository.init();
        try {
            try (RepositoryConnection connection =
                    validationRepository.getConnection()) {
                connection.begin();
                connection.add(shapes, RDF4J.SHACL_SHAPE_GRAPH);
                connection.commit();
                connection.begin();
                connection.add(data);
                connection.commit();
            }
            sail.enableValidation();
            try (RepositoryConnection connection =
                    validationRepository.getConnection()) {
                connection.begin();
                ShaclSailConnection shaclConnection = (ShaclSailConnection)
                        ((SailRepositoryConnection) connection)
                                .getSailConnection();
                ValidationReport report = shaclConnection.revalidate();
                connection.rollback();
                if (!report.conforms()) {
                    Map<String, Object> details = validationDetails(
                            report.toString());
                    LOGGER.warn("LexInt SHACL validation failed: {}",
                            details.get("validationReport"));
                    throw new LexIntException(422,
                            "LEXINT_SHACL_VIOLATION",
                            "LexInt data does not conform to the installed SHACL profile",
                            details);
                }
            }
        } catch (RepositoryException ex) {
            if (isValidationFailure(ex)) {
                Map<String, Object> details = validationDetails(ex);
                LOGGER.warn("LexInt SHACL validation failed: {}",
                        details.get("validationReport"));
                throw new LexIntException(422, "LEXINT_SHACL_VIOLATION",
                        "LexInt data does not conform to the installed SHACL profile",
                        details);
            }
            throw new LexIntException(503, "LEXINT_SHACL_UNAVAILABLE",
                    "The in-process SHACL validator failed");
        } finally {
            validationRepository.shutDown();
        }
    }

    private Model shapeClosure(Model source, Collection<? extends Resource> roots) {
        Model result = new LinkedHashModel();
        Set<Resource> visited = new HashSet<Resource>();
        Deque<Resource> pending = new ArrayDeque<Resource>();
        pending.addAll(roots);
        while (!pending.isEmpty()) {
            Resource current = pending.removeFirst();
            if (!visited.add(current)) { continue; }
            for (Statement statement : source.filter(current, null, null)) {
                result.add(statement);
                if (statement.getObject() instanceof BNode) {
                    pending.addLast((BNode) statement.getObject());
                }
            }
        }
        return result;
    }

    private void addSubjectStatements(RepositoryConnection source, Model data,
            IRI subject, Resource graph) {
        try (RepositoryResult<Statement> rows = source.getStatements(subject,
                null, null, false, graph)) {
            while (rows.hasNext()) {
                Statement statement = rows.next();
                data.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject());
            }
        }
    }

    private void requireFocusMetadata(Model data,
            Collection<? extends IRI> runtimeFocusNodes) {
        if (runtimeFocusNodes == null) { return; }
        for (IRI focus : runtimeFocusNodes) {
            if (!data.contains(focus, DCTERMS.CREATOR, null)
                    || !data.contains(focus, DCTERMS.CREATED, null)
                    || !data.contains(focus, DCTERMS.MODIFIED, null)) {
                throw new LexIntException(409,
                        "LEXINT_METADATA_CONFLICT",
                        "Runtime focus node is missing server metadata");
            }
        }
    }

    private Model contextModel(RepositoryConnection connection, Resource graph) {
        Model model = new LinkedHashModel();
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                null, null, false, graph)) {
            while (rows.hasNext()) {
                Statement statement = rows.next();
                model.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject());
            }
        }
        return model;
    }

    private void addRuntimeMetadataShape(Model shapes,
            Collection<? extends IRI> focusNodes) {
        if (focusNodes == null || focusNodes.isEmpty()) {
            return;
        }
        IRI nodeShape = vf.createIRI(RUNTIME_SHAPES + "RuntimeMetadataShape");
        shapes.add(nodeShape, RDF.TYPE, sh("NodeShape"));
        shapes.add(nodeShape, sh("nodeKind"), sh("IRI"));
        for (IRI focus : focusNodes) {
            shapes.add(nodeShape, sh("targetNode"), focus);
        }
        propertyShape(shapes, nodeShape, DCTERMS.CREATOR, XSD.STRING,
                "creator");
        propertyShape(shapes, nodeShape, DCTERMS.CREATED, XSD.DATETIME,
                "created");
        propertyShape(shapes, nodeShape, DCTERMS.MODIFIED, XSD.DATETIME,
                "modified");
    }

    private void propertyShape(Model shapes, IRI nodeShape, IRI path,
            IRI datatype, String suffix) {
        BNode property = vf.createBNode("runtime-" + suffix);
        shapes.add(nodeShape, sh("property"), property);
        shapes.add(property, sh("path"), path);
        shapes.add(property, sh("minCount"), vf.createLiteral(1));
        shapes.add(property, sh("maxCount"), vf.createLiteral(1));
        shapes.add(property, sh("datatype"), datatype);
    }

    private IRI sh(String local) { return vf.createIRI(SH, local); }

    private void validateCandidateXone(Model data) {
        Set<Resource> targets = new HashSet<Resource>();
        for (Statement statement : data.filter(null, RDF.TYPE,
                LexIntVocabulary.TARGET_ASSERTION)) {
            targets.add(statement.getSubject());
        }
        IRI reifies = vf.createIRI(LexIntVocabulary.RDF_REIFIES);
        for (Resource target : targets) {
            int reifiesCount = data.filter(target, reifies, null).size();
            Set<org.eclipse.rdf4j.model.Value> subjects = data.filter(target,
                    RDF.SUBJECT, null).objects();
            Set<org.eclipse.rdf4j.model.Value> predicates = data.filter(target,
                    RDF.PREDICATE, null).objects();
            int objectCount = data.filter(target, RDF.OBJECT, null).size();
            boolean rdf12 = reifiesCount == 1 && subjects.isEmpty()
                    && predicates.isEmpty() && objectCount == 0;
            boolean subjectKind = subjects.size() == 1
                    && subjects.iterator().next() instanceof Resource;
            boolean predicateKind = predicates.size() == 1
                    && predicates.iterator().next() instanceof IRI;
            boolean classic = reifiesCount == 0 && subjectKind
                    && predicateKind && objectCount == 1;
            if (rdf12 == classic) {
                Map<String, Object> details =
                        new LinkedHashMap<String, Object>();
                details.put("focusNode", target.stringValue());
                details.put("constraint", "sh:xone");
                throw new LexIntException(422,
                        "LEXINT_SHACL_VIOLATION",
                        "TargetAssertion must satisfy exactly one complete reification form",
                        details);
            }
        }
    }

    private boolean isValidationFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getClass().getName().contains(
                    "ShaclSailValidationException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, Object> validationDetails(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ShaclSailValidationException) {
                return validationDetails(((ShaclSailValidationException)
                        current).getValidationReport().toString());
            }
            current = current.getCause();
        }
        return java.util.Collections.<String, Object>emptyMap();
    }

    private Map<String, Object> validationDetails(String report) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("validationReport", report.length() > 4000
                ? report.substring(0, 4000) : report);
        return details;
    }
}
