package it.cnr.ilc.lexo.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.LexOProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Idempotent GraphDB Free initialization executed before normal repository use. */
public final class GraphDbBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphDbBootstrap.class);
    private static final Object LOCK = new Object();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String DEFAULT_METADATA_GRAPH = "https://lexo.ilc.cnr.it/graphs/bootstrap";
    private static volatile boolean initialized;

    private GraphDbBootstrap() {
    }

    public static void initialize() {
        if (!Boolean.parseBoolean(LexOProperties.getProperty("Bootstrap.enabled", "true")) || initialized) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            try {
                String lexiconUrl = LexOProperties.getProperty("GraphDb.url", "http://localhost:7200");
                String textUrl = LexOProperties.getProperty("TextGraphDb.url", lexiconUrl);
                String lexiconRepository = requiredProperty("GraphDb.repository");
                String textRepository = requiredProperty("TextGraphDb.repository");

                ensureRepository(lexiconUrl, lexiconRepository,
                        LexOProperties.getProperty("Bootstrap.lexicon.label", "LexO lexicon"),
                        LexOProperties.getProperty("repository.lexicon.namespace", "http://lexica/mylexicon#"),
                        LexOProperties.getProperty("Bootstrap.lexicon.repositoryConfig", "bootstrap/repositories/lexicon-repository.ttl"));
                ensureRepository(textUrl, textRepository,
                        LexOProperties.getProperty("Bootstrap.text.label", "LexO texts"),
                        LexOProperties.getProperty("TextGraphDb.namedGraphBase", "https://lexo.ilc.cnr.it/graphs/nif/"),
                        LexOProperties.getProperty("Bootstrap.text.repositoryConfig", "bootstrap/repositories/text-repository.ttl"));

                RemoteRepositoryManager manager = openManager(lexiconUrl);
                try {
                    Repository repository = manager.getRepository(lexiconRepository);
                    if (repository == null) {
                        throw new IllegalStateException("GraphDB repository unavailable after creation: " + lexiconRepository);
                    }
                    importSchema(repository);
                    applyIndexes(repository, false);
                } finally {
                    manager.shutDown();
                }
                initialized = true;
                LOGGER.info("GraphDB bootstrap completed");
            } catch (RuntimeException ex) {
                if (Boolean.parseBoolean(LexOProperties.getProperty("Bootstrap.required", "true"))) {
                    throw ex;
                }
                LOGGER.error("GraphDB bootstrap failed; startup continues because Bootstrap.required=false", ex);
            }
        }
    }

    public static void rebuildIndexes() {
        String url = LexOProperties.getProperty("GraphDb.url", "http://localhost:7200");
        String repositoryId = requiredProperty("GraphDb.repository");
        RemoteRepositoryManager manager = openManager(url);
        try {
            Repository repository = manager.getRepository(repositoryId);
            if (repository == null) {
                throw new IllegalStateException("GraphDB repository not found: " + repositoryId);
            }
            applyIndexes(repository, true);
        } finally {
            manager.shutDown();
        }
    }

    private static RemoteRepositoryManager openManager(String url) {
        RemoteRepositoryManager manager = new RemoteRepositoryManager(trimSlash(url));
        manager.init();
        return manager;
    }

    private static void ensureRepository(String serverUrl, String repositoryId, String label,
            String baseUrl, String templateResource) {
        String endpoint = trimSlash(serverUrl) + "/rest/repositories/"
                + urlEncode(repositoryId);
        int status = request("GET", endpoint, null, null);
        if (status >= 200 && status < 300) {
            LOGGER.info("GraphDB repository already exists: {}", repositoryId);
            return;
        }
        if (status != HttpURLConnection.HTTP_NOT_FOUND) {
            throw new IllegalStateException("Unable to check GraphDB repository " + repositoryId + ": HTTP " + status);
        }

        String config = BootstrapResources.readUtf8(templateResource)
                .replace("__REPOSITORY_ID__", escapeTurtle(repositoryId))
                .replace("__REPOSITORY_LABEL__", escapeTurtle(label))
                .replace("__BASE_URL__", escapeTurtle(baseUrl));
        byte[] body = multipartConfig(config);
        String boundary = MULTIPART_BOUNDARY;
        status = request("POST", trimSlash(serverUrl) + "/rest/repositories", body,
                "multipart/form-data; boundary=" + boundary);
        if ((status < 200 || status >= 300) && status != HttpURLConnection.HTTP_CONFLICT) {
            throw new IllegalStateException("Unable to create GraphDB repository " + repositoryId + ": HTTP " + status);
        }
        LOGGER.info("Created GraphDB Free repository: {}", repositoryId);
    }

    private static void importSchema(Repository repository) {
        String manifestResource = LexOProperties.getProperty("Bootstrap.schema.manifest",
                "bootstrap/schema/schema-imports.json");
        JsonNode manifest = parseJson(manifestResource);
        String namedGraph = manifest.path("namedGraph").asText();
        if (namedGraph.isEmpty()) {
            throw new IllegalStateException("Missing namedGraph in " + manifestResource);
        }
        List<String> resources = stringArray(manifest.path("files"), manifestResource, "files");
        String checksum = checksum(resources);
        IRI graph = VF.createIRI(namedGraph);
        IRI metadataGraph = metadataGraph();
        IRI marker = VF.createIRI(metadataGraph.stringValue() + "/schema");

        try (RepositoryConnection connection = repository.getConnection()) {
            if (checksum.equals(readChecksum(connection, marker, metadataGraph))
                    && connection.hasStatement(null, null, null, false, graph)) {
                LOGGER.info("Schema resources unchanged; import skipped");
                return;
            }
            connection.begin();
            try {
                connection.clear(graph);
                for (String resource : resources) {
                    byte[] bytes = BootstrapResources.readBytes(resource);
                    try (InputStream input = new ByteArrayInputStream(bytes)) {
                        connection.add(input, resource, RDFFormat.RDFXML, graph);
                    }
                }
                writeChecksum(connection, marker, checksum, metadataGraph);
                connection.commit();
                LOGGER.info("Imported {} schema resources into {}", resources.size(), namedGraph);
            } catch (RuntimeException | IOException ex) {
                connection.rollback();
                throw new IllegalStateException("Unable to import schema resources", ex);
            }
        }
    }

    private static void applyIndexes(Repository repository, boolean force) {
        String manifestResource = LexOProperties.getProperty("Bootstrap.indexes.manifest",
                "bootstrap/indexes/indexes.json");
        JsonNode manifest = parseJson(manifestResource);
        List<IndexDefinition> definitions = new ArrayList<>();
        List<String> checksumResources = new ArrayList<>();
        for (JsonNode item : manifest.path("indexes")) {
            String name = item.path("name").asText();
            String resource = item.path("resource").asText();
            if (name.isEmpty() || resource.isEmpty()) {
                throw new IllegalStateException("Invalid index entry in " + manifestResource);
            }
            definitions.add(new IndexDefinition(name, resource));
            checksumResources.add(resource);
        }
        if (definitions.isEmpty()) {
            throw new IllegalStateException("No indexes configured in " + manifestResource);
        }
        String checksum = checksum(checksumResources);
        IRI metadataGraph = metadataGraph();
        IRI marker = VF.createIRI(metadataGraph.stringValue() + "/indexes");

        try (RepositoryConnection connection = repository.getConnection()) {
            Set<String> existing = listConnectors(connection);
            boolean allPresent = true;
            for (IndexDefinition definition : definitions) {
                allPresent &= existing.contains(definition.name);
            }
            if (!force && allPresent && checksum.equals(readChecksum(connection, marker, metadataGraph))) {
                LOGGER.info("GraphDB connector indexes unchanged; rebuild skipped");
                return;
            }
            String drop = BootstrapResources.readUtf8("bootstrap/indexes/drop-connector.sparql");
            for (IndexDefinition definition : definitions) {
                if (existing.contains(definition.name)) {
                    executeUpdate(connection, drop.replace("_INDEX_NAME_", definition.name));
                }
            }
            for (IndexDefinition definition : definitions) {
                executeUpdate(connection, BootstrapResources.readUtf8(definition.resource));
            }
            connection.begin();
            try {
                writeChecksum(connection, marker, checksum, metadataGraph);
                connection.commit();
            } catch (RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
            LOGGER.info("Created {} GraphDB connector indexes", definitions.size());
        }
    }

    private static Set<String> listConnectors(RepositoryConnection connection) {
        Set<String> names = new HashSet<>();
        String query = BootstrapResources.readUtf8("bootstrap/indexes/list-connectors.sparql");
        try (TupleQueryResult result = connection.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            while (result.hasNext()) {
                BindingSet row = result.next();
                if (row.getValue("connectorName") != null) {
                    names.add(row.getValue("connectorName").stringValue());
                }
            }
        }
        return names;
    }

    private static void executeUpdate(RepositoryConnection connection, String update) {
        connection.prepareUpdate(QueryLanguage.SPARQL, update).execute();
    }

    private static String readChecksum(RepositoryConnection connection, IRI marker, IRI metadataGraph) {
        IRI predicate = checksumPredicate();
        try (org.eclipse.rdf4j.repository.RepositoryResult<Statement> statements
                = connection.getStatements(marker, predicate, null, false, metadataGraph)) {
            if (statements.hasNext()) {
                Statement statement = statements.next();
                if (statement.getObject() instanceof Literal) {
                    return statement.getObject().stringValue();
                }
            }
        }
        return null;
    }

    private static void writeChecksum(RepositoryConnection connection, IRI marker, String checksum, IRI metadataGraph) {
        IRI predicate = checksumPredicate();
        connection.remove(marker, predicate, null, metadataGraph);
        connection.add(marker, predicate, VF.createLiteral(checksum), metadataGraph);
    }

    private static IRI metadataGraph() {
        return VF.createIRI(LexOProperties.getProperty("Bootstrap.metadata.namedGraph", DEFAULT_METADATA_GRAPH));
    }

    private static IRI checksumPredicate() {
        return VF.createIRI("https://lexo.ilc.cnr.it/vocabulary/bootstrap/checksum");
    }

    private static JsonNode parseJson(String resource) {
        try {
            return JSON.readTree(BootstrapResources.readBytes(resource));
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid bootstrap manifest: " + resource, ex);
        }
    }

    private static List<String> stringArray(JsonNode node, String resource, String field) {
        if (!node.isArray()) {
            throw new IllegalStateException("Missing array '" + field + "' in " + resource);
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            values.add(value.asText());
        }
        return values;
    }

    private static String checksum(List<String> resources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String resource : resources) {
                digest.update(resource.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(BootstrapResources.readBytes(resource));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static final String MULTIPART_BOUNDARY = "----LexOGraphDbBootstrap";

    private static byte[] multipartConfig(String config) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(("--" + MULTIPART_BOUNDARY + "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write("Content-Disposition: form-data; name=\"config\"; filename=\"repository-config.ttl\"\r\n".getBytes(StandardCharsets.UTF_8));
            output.write("Content-Type: text/turtle\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            output.write(config.getBytes(StandardCharsets.UTF_8));
            output.write(("\r\n--" + MULTIPART_BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to build repository configuration request", ex);
        }
    }

    private static int request(String method, String endpoint, byte[] body, String contentType) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(Integer.parseInt(LexOProperties.getProperty("Bootstrap.connectTimeoutMs", "10000")));
            connection.setReadTimeout(Integer.parseInt(LexOProperties.getProperty("Bootstrap.readTimeoutMs", "60000")));
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            int status = connection.getResponseCode();
            consume(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            return status;
        } catch (IOException ex) {
            throw new IllegalStateException("GraphDB REST request failed: " + method + " " + endpoint, ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void consume(InputStream input) throws IOException {
        if (input == null) {
            return;
        }
        try (InputStream stream = input) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) >= 0) {
                // Consume the response so the HTTP connection can be released.
            }
        }
    }

    private static String requiredProperty(String name) {
        String value = LexOProperties.getProperty(name);
        if (value == null || value.trim().isEmpty() || value.startsWith("${")) {
            throw new IllegalStateException("Missing required property: " + name);
        }
        return value.trim();
    }

    private static String escapeTurtle(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class IndexDefinition {
        private final String name;
        private final String resource;

        private IndexDefinition(String name, String resource) {
            this.name = name;
            this.resource = resource;
        }
    }
}
