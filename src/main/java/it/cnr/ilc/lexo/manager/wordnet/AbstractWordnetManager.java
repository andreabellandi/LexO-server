package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.Manager;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

abstract class AbstractWordnetManager implements Manager {

    final Repository repository;
    final WordnetGraphResolver resolver;
    final WordnetRdfSupport rdf = new WordnetRdfSupport();

    AbstractWordnetManager(Repository repository) {
        this.repository = repository;
        this.resolver = new WordnetGraphResolver(repository);
    }

    RepositoryConnection acquire() {
        return repository == null
                ? GraphDbUtil.getConnection(RepositoryTarget.WORDNET)
                : repository.getConnection();
    }

    void release(RepositoryConnection connection) {
        if (repository == null) {
            GraphDbUtil.releaseConnection(RepositoryTarget.WORDNET, connection);
        } else {
            connection.close();
        }
    }

    void rollback(RepositoryConnection connection) {
        if (connection != null && connection.isActive()) {
            connection.rollback();
        }
    }
}
