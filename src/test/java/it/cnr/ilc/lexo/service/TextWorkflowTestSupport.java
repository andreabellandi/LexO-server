package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.junit.jupiter.api.Assumptions;

/**
 * HTTP, SPARQL and filesystem driver shared by workflow-oriented tests.
 *
 * <p>This is intentionally a black-box driver: it does not invoke managers or
 * repositories in-process. Therefore a successful workflow proves that the
 * deployed REST API, asynchronous job executor and GraphDB agree on state.</p>
 */
final class TextWorkflowTestSupport implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    final String serviceBaseUrl;
    final String authorization;
    final String graphdbUrl;
    final String graphdbRepository;
    final String graphdbAuthorization;
    final String namedGraphBase;
    final Path storageRoot;
    private final Client client;

    TextWorkflowTestSupport() {
        serviceBaseUrl = trimTrailingSlash(property("lexo.test.baseUrl"));
        authorization = property("lexo.test.authorization");
        graphdbUrl = trimTrailingSlash(property("lexo.test.graphdbUrl"));
        graphdbRepository = property("lexo.test.textRepository");
        graphdbAuthorization = property("lexo.test.graphdbAuthorization");
        namedGraphBase = trailingSlash(System.getProperty("lexo.test.namedGraphBase",
                "https://lexo.ilc.cnr.it/graphs/nif/"));
        String storage = property("lexo.test.storageDir");
        storageRoot = storage.isEmpty() ? null : Paths.get(storage).toAbsolutePath().normalize();
        client = ClientBuilder.newBuilder().register(MultiPartFeature.class).build();
    }

    /**
     * Workflow tests require direct visibility of every persistence layer.
     * Missing properties skip them safely instead of silently reducing their
     * assertions to ordinary endpoint tests.
     */
    void assumeFullyConfigured() {
        List<String> missing = new ArrayList<String>();
        required(missing, "lexo.test.baseUrl", serviceBaseUrl);
        required(missing, "lexo.test.authorization", authorization);
        required(missing, "lexo.test.graphdbUrl", graphdbUrl);
        required(missing, "lexo.test.textRepository", graphdbRepository);
        required(missing, "lexo.test.storageDir", storageRoot == null ? "" : storageRoot.toString());
        Assumptions.assumeTrue(missing.isEmpty(),
                "Workflow test requires: " + String.join(", ", missing));
    }

    String upload(Path text) throws Exception {
        return upload(text, null);
    }

    String upload(Path text, Path conllu) throws Exception {
        FormDataMultiPart multipart = new FormDataMultiPart();
        multipart.bodyPart(new FileDataBodyPart("file", text.toFile()));
        if (conllu != null) {
            multipart.bodyPart(new FileDataBodyPart("conllu", conllu.toFile()));
        }
        try {
            Response response = serviceRequest("texts/upload")
                    .post(Entity.entity(multipart, multipart.getMediaType()));
            JsonNode body = readJson(response, 200);
            String fileId = body.path("fileId").asText();
            assertThat(fileId).isNotBlank();
            return fileId;
        } finally {
            multipart.close();
        }
    }

    JsonNode createCorpus(Path descriptor) throws Exception {
        FormDataMultiPart multipart = new FormDataMultiPart();
        multipart.bodyPart(new FileDataBodyPart("file", descriptor.toFile()));
        try {
            return readJson(serviceRequest("texts/corpora")
                    .post(Entity.entity(multipart, multipart.getMediaType())), 200);
        } finally {
            multipart.close();
        }
    }

    JsonNode startConversion(String fileId, String corpusId) throws Exception {
        String path = "texts/" + fileId + "/convert";
        if (corpusId != null) {
            path += "?corpusId=" + encode(corpusId);
        }
        return readJson(serviceRequest(path).post(Entity.text("")), 200);
    }

    JsonNode awaitTerminalJob(String fileId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            JsonNode jobs = getJson("texts/" + fileId + "/status", 200);
            if (jobs.isArray() && jobs.size() > 0) {
                last = jobs.get(0);
                String state = last.path("state").asText();
                if ("COMPLETED".equals(state) || "FAILED".equals(state)
                        || "CANCELLED".equals(state)) {
                    return last;
                }
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("Job " + fileId + " did not terminate within "
                + timeout + "; last response=" + last);
    }

    JsonNode getJson(String path, int expectedStatus) throws Exception {
        return readJson(serviceRequest(path).get(), expectedStatus);
    }

    byte[] download(String path) {
        Response response = serviceRequest(path).get();
        try {
            assertStatus(response, 200);
            return response.readEntity(byte[].class);
        } finally {
            response.close();
        }
    }

    boolean deleteText(String fileId) throws Exception {
        return readJson(serviceRequest("texts/" + fileId).delete(), 200)
                .path("deleted").asBoolean();
    }

    boolean deleteCorpus(String corpusId) throws Exception {
        return readJson(serviceRequest("texts/corpora/" + corpusId).delete(), 200)
                .path("deleted").asBoolean();
    }

    void assertServiceStatus(String path, int expectedStatus) {
        Response response = serviceRequest(path).get();
        try {
            assertStatus(response, expectedStatus);
        } finally {
            response.close();
        }
    }

    /** Executes an ASK query directly against the configured GraphDB repository. */
    boolean ask(String sparql) throws Exception {
        Form form = new Form().param("query", sparql);
        Invocation.Builder request = graphdbRequest()
                .accept("application/sparql-results+json");
        Response response = request.post(Entity.entity(form,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE));
        JsonNode body = readJson(response, 200);
        return body.path("boolean").asBoolean();
    }

    boolean graphExists(String graphUri) throws Exception {
        return ask("ASK WHERE { GRAPH <" + graphUri + "> { ?s ?p ?o } }");
    }

    String documentGraph(String fileId) {
        return namedGraphBase + "documents/" + fileId;
    }

    String corpusGraph(String corpusId) {
        return namedGraphBase + "corpora/" + corpusId;
    }

    void assertUploadedFilesExist(String fileId) {
        assertThat(storageRoot.resolve("uploads").resolve(fileId)).isDirectory();
    }

    void assertCompletedDocumentFiles(String fileId, String originalName,
                                      boolean hasConllu, String conlluName) {
        Path document = storageRoot.resolve("documents").resolve(fileId);
        assertThat(document.resolve("original").resolve(originalName)).isRegularFile();
        assertThat(document.resolve("canonical.txt")).isRegularFile();
        assertThat(document.resolve("metadata.json")).isRegularFile();
        if (hasConllu) {
            assertThat(document.resolve("conllu").resolve(conlluName)).isRegularFile();
        }
        assertThat(storageRoot.resolve("uploads").resolve(fileId)).doesNotExist();
        assertNoWorkDirectory(fileId);
    }

    void assertCorpusFilesExist(String corpusId, String descriptorName) {
        Path corpus = storageRoot.resolve("corpora").resolve(corpusId);
        assertThat(corpus.resolve("original").resolve(descriptorName)).isRegularFile();
        assertThat(corpus.resolve("metadata.json")).isRegularFile();
    }

    void assertNoDocumentArtifacts(String fileId) {
        assertThat(storageRoot.resolve("uploads").resolve(fileId)).doesNotExist();
        assertThat(storageRoot.resolve("documents").resolve(fileId)).doesNotExist();
        assertNoWorkDirectory(fileId);
    }

    void assertNoCorpusArtifacts(String corpusId) {
        assertThat(storageRoot.resolve("corpora").resolve(corpusId)).doesNotExist();
        Path work = storageRoot.resolve("work");
        if (Files.isDirectory(work)) {
            try (Stream<Path> paths = Files.list(work)) {
                assertThat(paths.map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith("corpus-" + corpusId + "-")))
                        .isEmpty();
            } catch (java.io.IOException e) {
                throw new AssertionError("Cannot inspect corpus work directory", e);
            }
        }
    }

    void deleteTextQuietly(String fileId) {
        if (fileId == null) {
            return;
        }
        try {
            deleteText(fileId);
        } catch (Exception ignored) {
            // Best effort cleanup must not hide the original scenario failure.
        }
    }

    void deleteCorpusQuietly(String corpusId) {
        if (corpusId == null) {
            return;
        }
        try {
            deleteCorpus(corpusId);
        } catch (Exception ignored) {
            // Best effort cleanup must not hide the original scenario failure.
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private JsonNode readJson(Response response, int expectedStatus) throws Exception {
        try {
            assertStatus(response, expectedStatus);
            String body = response.hasEntity() ? response.readEntity(String.class) : "";
            return body.isEmpty() ? JSON.createObjectNode() : JSON.readTree(body);
        } finally {
            response.close();
        }
    }

    private Invocation.Builder serviceRequest(String path) {
        return client.target(serviceBaseUrl + "/" + path).request()
                .header("Authorization", authorization);
    }

    private Invocation.Builder graphdbRequest() {
        Invocation.Builder request = client.target(graphdbUrl + "/repositories/"
                + encode(graphdbRepository)).request();
        if (!graphdbAuthorization.isEmpty()) {
            request.header("Authorization", graphdbAuthorization);
        }
        return request;
    }

    private void assertNoWorkDirectory(String fileId) {
        Path work = storageRoot.resolve("work");
        if (!Files.isDirectory(work)) {
            return;
        }
        try (Stream<Path> paths = Files.list(work)) {
            assertThat(paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(fileId + "-")))
                    .isEmpty();
        } catch (java.io.IOException e) {
            throw new AssertionError("Cannot inspect text work directory", e);
        }
    }

    private static void assertStatus(Response response, int expected) {
        if (response.getStatus() == expected) {
            return;
        }
        String body;
        try {
            body = response.hasEntity() ? response.readEntity(String.class) : "";
        } catch (RuntimeException error) {
            body = "<unreadable response>";
        }
        throw new AssertionError("Expected HTTP " + expected + " but got "
                + response.getStatus() + ": " + body);
    }

    private static void required(List<String> missing, String key, String value) {
        if (value == null || value.isEmpty()) {
            missing.add(key);
        }
    }

    private static String property(String key) {
        return System.getProperty(key, "").trim();
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        return result.endsWith("/") ? result : result + "/";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
