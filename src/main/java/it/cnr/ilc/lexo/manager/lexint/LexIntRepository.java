package it.cnr.ilc.lexo.manager.lexint;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** LexOLexica-only connection lifecycle and immutable graph policy. */
public final class LexIntRepository {
    public interface Work<T> { T run(RepositoryConnection connection); }

    private final Repository injected;
    public final IRI schemaGraph;
    public final IRI shapesGraph;
    public final IRI recordsGraph;
    public final IRI auditGraph;
    public final String snapshotsBase;

    public LexIntRepository() { this(null); }

    public LexIntRepository(Repository repository) {
        this.injected = repository;
        schemaGraph = iri("GraphDb.schemaNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/schema");
        shapesGraph = iri("GraphDb.lexintShapesNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/shapes");
        recordsGraph = iri("GraphDb.lexintRecordsNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/records");
        auditGraph = iri("GraphDb.lexintAuditNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/audit");
        snapshotsBase = property("GraphDb.lexintSnapshotsBase",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/snapshots/");
        validateConfiguration();
    }

    public <T> T read(Work<T> work) { return access(false, work); }
    public <T> T write(Work<T> work) { return access(true, work); }

    private <T> T access(boolean write, Work<T> work) {
        RepositoryConnection connection = null;
        try {
            connection = injected == null
                    ? GraphDbUtil.getConnection(RepositoryTarget.LEXICON)
                    : injected.getConnection();
            if (write) {
                connection.begin();
            }
            T value = work.run(connection);
            if (write) {
                connection.commit();
            }
            return value;
        } catch (LexIntException ex) {
            rollback(connection);
            throw ex;
        } catch (RepositoryException ex) {
            rollback(connection);
            throw new LexIntException(503, "LEXINT_REPOSITORY_UNAVAILABLE",
                    "LexInt repository operation failed");
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            if (connection != null) {
                if (injected == null) {
                    GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON,
                            connection);
                } else {
                    connection.close();
                }
            }
        }
    }

    public Model model(RepositoryConnection connection, Resource graph) {
        Model model = new LinkedHashModel();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, null, null, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                model.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject());
            }
        }
        return model;
    }

    private void validateConfiguration() {
        LexIntGraphConfiguration.validate(shapesGraph.stringValue());
    }

    private static void rollback(RepositoryConnection connection) {
        if (connection != null && connection.isActive()) {
            try { connection.rollback(); } catch (RuntimeException ignored) { }
        }
    }

    private static IRI iri(String key, String fallback) {
        return SimpleValueFactory.getInstance().createIRI(property(key, fallback));
    }

    private static String property(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        return value == null || value.trim().isEmpty() || value.contains("${")
                ? fallback : value.trim();
    }

}
