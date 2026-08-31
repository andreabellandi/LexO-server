package it.cnr.ilc.lexo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.config.RepositoryConfigException;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;
import org.eclipse.rdf4j.repository.manager.RepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime connection pools for LexO-server's logical GraphDB repositories. */
public final class GraphDbUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphDbUtil.class);
    private static final Map<RepositoryTarget, RepositoryContext> CONTEXTS =
            new EnumMap<RepositoryTarget, RepositoryContext>(RepositoryTarget.class);

    private GraphDbUtil() {
    }

    /** Backward-compatible lexical repository connection. */
    public static RepositoryConnection getConnection() throws RepositoryException {
        return getConnection(RepositoryTarget.LEXICON);
    }

    public static RepositoryConnection getConnection(RepositoryTarget target)
            throws RepositoryException {
        RepositoryContext context = context(target);
        synchronized (context.lock) {
            while (context.pool.isEmpty()) {
                try {
                    context.lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RepositoryException(
                            "Interrupted while waiting for repository " + target, e);
                }
            }
            RepositoryConnection connection = context.pool.remove(0);
            if (!testRdfServer(context.url)) {
                context.pool.add(connection);
                context.lock.notifyAll();
                throw new RepositoryException("Repository is unreachable: "
                        + context.repositoryId);
            }
            context.lock.notifyAll();
            return connection;
        }
    }

    /** Backward-compatible lexical repository release. */
    public static void releaseConnection(RepositoryConnection connection) {
        releaseConnection(RepositoryTarget.LEXICON, connection);
    }

    public static void releaseConnection(RepositoryTarget target,
                                         RepositoryConnection connection) {
        RepositoryContext context = context(target);
        synchronized (context.lock) {
            if (connection != null) {
                if (connection.isActive()) {
                    connection.rollback();
                }
                context.pool.add(connection);
                context.lock.notifyAll();
            }
        }
    }

    public static void shutDown() {
        synchronized (CONTEXTS) {
            for (RepositoryContext context : CONTEXTS.values()) {
                synchronized (context.lock) {
                    for (RepositoryConnection connection : context.pool) {
                        close(connection);
                    }
                    context.pool.clear();
                    if (context.repository != null) {
                        context.repository.shutDown();
                    }
                    if (context.manager != null) {
                        context.manager.shutDown();
                    }
                    context.lock.notifyAll();
                }
            }
            CONTEXTS.clear();
        }
    }

    /** Lightweight readiness probe that does not borrow from the application pool. */
    public static boolean isAvailable(RepositoryTarget target) {
        String lexicalUrl = property("GraphDb.url", "http://localhost:7200");
        String url = target == RepositoryTarget.TEXT
                ? property("TextGraphDb.url", lexicalUrl) : lexicalUrl;
        String repositoryId = target == RepositoryTarget.TEXT
                ? property("TextGraphDb.repository", "LexOTexts")
                : property("GraphDb.repository", "LexOLexica");
        RemoteRepositoryManager manager = null;
        try {
            manager = new RemoteRepositoryManager(url);
            manager.init();
            Repository repository = manager.getRepository(repositoryId);
            if (repository == null) {
                return false;
            }
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.prepareBooleanQuery(QueryLanguage.SPARQL,
                        "ASK WHERE {}").evaluate();
                return true;
            }
        } catch (RuntimeException ex) {
            LOGGER.debug("GraphDB readiness probe failed for repository {} at {}",
                    repositoryId, url, ex);
            return false;
        } finally {
            if (manager != null) {
                try {
                    manager.shutDown();
                } catch (RuntimeException ex) {
                    LOGGER.debug("Unable to close GraphDB readiness manager", ex);
                }
            }
        }
    }

    private static RepositoryContext context(RepositoryTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("Repository target is required");
        }
        synchronized (CONTEXTS) {
            RepositoryContext existing = CONTEXTS.get(target);
            if (existing != null) {
                return existing;
            }
            RepositoryContext created = createContext(target);
            CONTEXTS.put(target, created);
            return created;
        }
    }

    private static RepositoryContext createContext(RepositoryTarget target) {
        String lexicalUrl = property("GraphDb.url", "http://localhost:7200");
        String url = target == RepositoryTarget.TEXT
                ? property("TextGraphDb.url", lexicalUrl) : lexicalUrl;
        String repositoryId = target == RepositoryTarget.TEXT
                ? property("TextGraphDb.repository", "LexOTexts")
                : property("GraphDb.repository", "LexOLexica");
        String poolSetting = target == RepositoryTarget.TEXT
                ? property("TextGraphDb.size", property("GraphDb.size", "5"))
                : property("GraphDb.size", "5");
        try {
            int poolSize = Integer.parseInt(poolSetting);
            if (poolSize < 1) {
                throw new IllegalArgumentException("Repository pool size must be positive");
            }
            RemoteRepositoryManager manager = new RemoteRepositoryManager(url);
            manager.init();
            Repository repository = manager.getRepository(repositoryId);
            if (repository == null) {
                manager.shutDown();
                throw new RepositoryException("GraphDB repository does not exist: "
                        + repositoryId);
            }
            RepositoryContext context = new RepositoryContext(
                    url, repositoryId, manager, repository);
            for (int i = 0; i < poolSize; i++) {
                context.pool.add(repository.getConnection());
            }
            LOGGER.info("Initialized GraphDB repository {} at {} with pool size {}",
                    repositoryId, url, poolSize);
            return context;
        } catch (RepositoryException | RepositoryConfigException
                | IllegalArgumentException e) {
            LOGGER.error("Unable to initialize GraphDB repository " + repositoryId, e);
            throw new RepositoryException("Unable to initialize GraphDB repository "
                    + repositoryId + " at " + url, e);
        }
    }

    private static String property(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        return value == null || value.trim().isEmpty() || value.contains("${")
                ? fallback : value.trim();
    }

    private static boolean testRdfServer(String url) {
        HttpGet request = new HttpGet(url);
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(5000)
                .setConnectionRequestTimeout(5000)
                .setSocketTimeout(5000)
                .build();
        try (CloseableHttpClient client = HttpClientBuilder.create()
                .setDefaultRequestConfig(config).build();
             CloseableHttpResponse response = client.execute(request)) {
            return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
        } catch (IOException e) {
            LOGGER.error("GraphDB server is unreachable at " + url, e);
            return false;
        }
    }

    private static void close(RepositoryConnection connection) {
        try {
            if (connection != null) {
                if (connection.isActive()) {
                    connection.rollback();
                }
                connection.close();
            }
        } catch (RepositoryException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
        }
    }

    private static final class RepositoryContext {
        final Object lock = new Object();
        final String url;
        final String repositoryId;
        final RepositoryManager manager;
        final Repository repository;
        final List<RepositoryConnection> pool = new ArrayList<RepositoryConnection>();

        RepositoryContext(String url, String repositoryId, RepositoryManager manager,
                          Repository repository) {
            this.url = url;
            this.repositoryId = repositoryId;
            this.manager = manager;
            this.repository = repository;
        }
    }
}
