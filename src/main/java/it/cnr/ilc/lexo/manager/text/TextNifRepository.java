package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.LexOProperties;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RDF/NIF persistence isolated from LexO's lexical GraphDB repository. */
public final class TextNifRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextNifRepository.class);
    private static final TextNifRepository INSTANCE = new TextNifRepository();

    public static TextNifRepository get() {
        return INSTANCE;
    }

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final String serverUrl;
    private final String repositoryId;
    private final String graphBase;
    private final RemoteRepositoryManager manager;
    private final Repository repository;

    private TextNifRepository() {
        if (Boolean.getBoolean("lexo.text.nifRepository.memory")) {
            serverUrl = "memory";
            repositoryId = "LexOTextsTest";
            graphBase = trailingSeparator(System.getProperty(
                    "lexo.text.namedGraphBase", "https://lexo.test/graphs/nif/"));
            manager = null;
            repository = new SailRepository(new MemoryStore());
            repository.init();
            return;
        }
        serverUrl = configured("TextGraphDb.url",
                LexOProperties.getProperty("GraphDb.url", "http://localhost:7200"));
        repositoryId = configured("TextGraphDb.repository", "LexOTexts");
        graphBase = trailingSeparator(configured("TextGraphDb.namedGraphBase",
                "https://lexo.ilc.cnr.it/graphs/nif/"));
        try {
            manager = new RemoteRepositoryManager(serverUrl);
            manager.init();
            repository = manager.getRepository(repositoryId);
            if (repository == null) {
                throw new RepositoryException("GraphDB repository does not exist: " + repositoryId);
            }
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.size();
            }
            LOGGER.info("Text NIF repository initialized: {}/{}", serverUrl, repositoryId);
        } catch (RuntimeException e) {
            throw new RepositoryException("Unable to connect to text NIF repository "
                    + serverUrl + "/" + repositoryId, e);
        }
    }

    public String documentGraphUri(String fileId) {
        return graphBase + "documents/" + fileId;
    }

    public String corpusGraphUri(String corpusId) {
        return graphBase + "corpora/" + corpusId;
    }

    public void saveDocument(String fileId, Model model, String documentContextUri,
                             String corpusId, String corpusUri) {
        IRI documentGraph = iri(documentGraphUri(fileId));
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.begin();
            try {
                connection.clear(documentGraph);
                connection.add(model, documentGraph);
                if (corpusId != null) {
                    IRI corpusGraph = iri(corpusGraphUri(corpusId));
                    if (!connection.hasStatement(null, null, null, false, corpusGraph)) {
                        throw new IllegalArgumentException("Corpus NIF not found: " + corpusId);
                    }
                    connection.add(iri(documentContextUri), DCTERMS.IS_PART_OF,
                            iri(corpusUri), documentGraph);
                    connection.add(iri(corpusUri), DCTERMS.HAS_PART,
                            iri(documentContextUri), corpusGraph);
                }
                connection.commit();
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            }
        }
    }

    public void saveCorpus(String corpusId, Model model) {
        replaceGraph(iri(corpusGraphUri(corpusId)), model);
    }

    public boolean containsDocument(String fileId) {
        return containsGraph(iri(documentGraphUri(fileId)));
    }

    public boolean containsCorpus(String corpusId) {
        return containsGraph(iri(corpusGraphUri(corpusId)));
    }

    public void writeDocument(String fileId, OutputStream output) {
        exportGraph(iri(documentGraphUri(fileId)), output);
    }

    public void writeCorpus(String corpusId, OutputStream output) {
        exportGraph(iri(corpusGraphUri(corpusId)), output);
    }

    public void deleteDocument(String fileId, String documentContextUri,
                               String corpusId, String corpusUri) {
        IRI documentGraph = iri(documentGraphUri(fileId));
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.begin();
            try {
                connection.clear(documentGraph);
                connection.remove((Resource) null, DCTERMS.HAS_PART,
                        iri(documentContextUri));
                connection.commit();
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            }
        }
    }

    public void deleteCorpus(String corpusId, String corpusUri) {
        IRI corpusGraph = iri(corpusGraphUri(corpusId));
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.begin();
            try {
                connection.remove((Resource) null, DCTERMS.IS_PART_OF, iri(corpusUri));
                connection.clear(corpusGraph);
                connection.commit();
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            }
        }
    }

    public void importLegacyDocument(String fileId, Path turtle) throws IOException {
        importLegacy(iri(documentGraphUri(fileId)), turtle);
    }

    public void importLegacyCorpus(String corpusId, Path turtle) throws IOException {
        importLegacy(iri(corpusGraphUri(corpusId)), turtle);
    }

    private void importLegacy(IRI graph, Path turtle) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(turtle);
             RepositoryConnection connection = repository.getConnection()) {
            connection.begin();
            try {
                connection.clear(graph);
                connection.add(input, "", RDFFormat.TURTLE, graph);
                connection.commit();
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            }
        }
    }

    private void replaceGraph(IRI graph, Model model) {
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.begin();
            try {
                connection.clear(graph);
                connection.add(model, graph);
                connection.commit();
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            }
        }
    }

    private boolean containsGraph(IRI graph) {
        try (RepositoryConnection connection = repository.getConnection()) {
            return connection.hasStatement(null, null, null, false, graph);
        }
    }

    private void exportGraph(IRI graph, OutputStream output) {
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.export(Rio.createWriter(RDFFormat.TURTLE, output), graph);
        }
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }

    private static void rollback(RepositoryConnection connection) {
        if (connection.isActive()) {
            connection.rollback();
        }
    }

    private static String configured(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        if (value == null || value.trim().isEmpty() || value.contains("${")) {
            return fallback;
        }
        return value.trim();
    }

    private static String trailingSeparator(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "/";
    }
}
