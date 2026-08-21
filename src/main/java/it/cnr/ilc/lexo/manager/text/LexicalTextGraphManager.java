package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.LinkedHashSet;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Lifecycle operations for per-text graphs stored in LexOLexica. */
public final class LexicalTextGraphManager {

    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final LexicalTextGraphManager INSTANCE =
            new LexicalTextGraphManager();

    public static LexicalTextGraphManager get() {
        return INSTANCE;
    }

    private LexicalTextGraphManager() {
    }

    /**
     * Atomically removes the attestations belonging to a text, every statement
     * that references them in LexOLexica, and both document graph families.
     */
    public boolean deleteDocumentGraphs(String fileId) {
        RepositoryConnection connection =
                GraphDbUtil.getConnection(RepositoryTarget.LEXICON);
        try {
            return deleteDocumentGraphs(connection, fileId);
        } finally {
            GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON, connection);
        }
    }

    boolean deleteDocumentGraphs(RepositoryConnection connection, String fileId) {
        IRI attestations = SimpleValueFactory.getInstance().createIRI(
                LexicalNamedGraphs.attestationGraphUri(fileId));
        IRI annotations = SimpleValueFactory.getInstance().createIRI(
                LexicalNamedGraphs.annotationGraphUri(fileId));
        boolean existed = connection.hasStatement(null, null, null, false, attestations)
                || connection.hasStatement(null, null, null, false, annotations);
        try {
            connection.begin();
            for (Resource attestation : documentAttestations(connection, attestations)) {
                connection.remove(attestation, null, null);
                connection.remove((Resource) null, null, attestation);
            }
            connection.clear(attestations);
            connection.clear(annotations);
            connection.commit();
            return existed;
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        }
    }

    private Set<Resource> documentAttestations(RepositoryConnection connection,
                                               IRI graph) {
        Set<Resource> attestations = new LinkedHashSet<Resource>();
        IRI attestationType = SimpleValueFactory.getInstance().createIRI(
                FRAC + "Attestation");
        IRI attestationRelation = SimpleValueFactory.getInstance().createIRI(
                FRAC + "attestation");
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, RDF.TYPE, attestationType, false, graph)) {
            while (statements.hasNext()) {
                attestations.add(statements.next().getSubject());
            }
        }
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, attestationRelation, null, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                if (statement.getObject() instanceof Resource) {
                    attestations.add((Resource) statement.getObject());
                }
            }
        }
        return attestations;
    }
}
