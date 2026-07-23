package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/** Lifecycle operations for per-text graphs stored in LexOLexica. */
public final class LexicalTextGraphManager {

    private static final LexicalTextGraphManager INSTANCE =
            new LexicalTextGraphManager();

    public static LexicalTextGraphManager get() {
        return INSTANCE;
    }

    private LexicalTextGraphManager() {
    }

    /** Atomically clears both lexical graph families associated with a text. */
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
}
