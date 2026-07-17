package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import java.util.UUID;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.core.Response;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.media.multipart.file.FileDataBodyPart;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Black-box tests for a deployed LexO-server backed by a real GraphDB.
 *
 * <p>The class is named {@code *IT}: Surefire excludes it from {@code mvn test},
 * while Failsafe executes it during {@code mvn verify}. Every test creates
 * unique data and removes it in a finally block.</p>
 */
class TextServicesIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static Client client;
    private static String baseUrl;
    private static String authorization;
    private static String serverStorageDir;

    @TempDir
    Path temporaryFiles;

    @BeforeAll
    static void configureClient() {
        baseUrl = trimTrailingSlash(System.getProperty("lexo.test.baseUrl", ""));
        authorization = System.getProperty("lexo.test.authorization", "").trim();
        serverStorageDir = System.getProperty("lexo.test.storageDir", "").trim();
        if (!baseUrl.isEmpty() && !authorization.isEmpty()) {
            client = ClientBuilder.newBuilder().register(MultiPartFeature.class).build();
        }
    }

    @AfterAll
    static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    @DisplayName("Plain TXT: upload, asynchronous conversion, RDF download and deletion")
    void convertsAndDeletesStandalonePlainText() throws Exception {
        assumeConfigured();
        String fileId = null;
        try {
            Path input = write("plain-" + UUID.randomUUID() + ".txt",
                    "Prima riga senza heading.\nSeconda riga.");
            fileId = upload(input);

            assertStatus(post("texts/" + fileId + "/convert"), 200);
            JsonNode terminal = awaitTerminalJob(fileId, Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("COMPLETED");

            Response nifResponse = get("texts/" + fileId + "/nif");
            assertStatus(nifResponse, 200);
            Model nif = parseTurtle(nifResponse.readEntity(byte[].class));
            assertThat(nif.contains(null, iri(
                    "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#isString"),
                    null)).isTrue();

            assertStatus(get("texts/" + fileId + "/original"), 200);
            assertStatus(get("texts/" + fileId + "/canonical"), 200);

            JsonNode deletion = json(delete("texts/" + fileId));
            assertThat(deletion.path("deleted").asBoolean()).isTrue();
            assertStatus(get("texts/" + fileId), 404);
            assertStatus(get("texts/" + fileId + "/nif"), 404);
            assertNoServerFilesystemArtifacts(fileId);
            fileId = null;
        } finally {
            deleteQuietly(fileId == null ? null : "texts/" + fileId);
        }
    }

    @Test
    @DisplayName("Corpus: creation, bidirectional membership, member deletion and corpus deletion")
    void managesCorpusMembershipLifecycle() throws Exception {
        assumeConfigured();
        String corpusId = null;
        String fileId = null;
        try {
            Path descriptor = write("corpus-" + UUID.randomUUID() + ".txt",
                    "---\n"
                            + "id: https://example.org/corpora/test-suite\n"
                            + "title: Corpus della test suite\n"
                            + "author:\n  - Test automatico\n"
                            + "---\n");
            JsonNode corpus = json(uploadMultipart("texts/corpora", descriptor));
            corpusId = corpus.path("corpusId").asText();
            assertThat(corpusId).isNotBlank();

            Path input = write("member-" + UUID.randomUUID() + ".txt", "Documento del corpus.");
            fileId = upload(input);
            assertStatus(post("texts/" + fileId + "/convert?corpusId=" + corpusId), 200);
            assertThat(awaitTerminalJob(fileId, Duration.ofSeconds(30)).path("state").asText())
                    .isEqualTo("COMPLETED");

            JsonNode record = json(get("texts/" + fileId));
            String corpusUri = record.path("corpusUri").asText();
            String documentContext = record.path("documentUri").asText() + "#context";
            Model documentNif = turtle(get("texts/" + fileId + "/nif"));
            Model corpusNif = turtle(get("texts/corpora/" + corpusId + "/nif"));
            assertThat(documentNif.contains(iri(documentContext), iri(DCTERMS + "isPartOf"),
                    iri(corpusUri))).isTrue();
            assertThat(corpusNif.contains(iri(corpusUri), iri(DCTERMS + "hasPart"),
                    iri(documentContext))).isTrue();

            assertThat(json(delete("texts/" + fileId)).path("deleted").asBoolean()).isTrue();
            fileId = null;
            Model corpusAfterMemberDeletion = turtle(get("texts/corpora/" + corpusId + "/nif"));
            assertThat(corpusAfterMemberDeletion.contains(iri(corpusUri),
                    iri(DCTERMS + "hasPart"), iri(documentContext))).isFalse();

            assertThat(json(delete("texts/corpora/" + corpusId))
                    .path("deleted").asBoolean()).isTrue();
            assertStatus(get("texts/corpora/" + corpusId), 404);
            corpusId = null;
        } finally {
            deleteQuietly(fileId == null ? null : "texts/" + fileId);
            deleteQuietly(corpusId == null ? null : "texts/corpora/" + corpusId);
        }
    }

    @Test
    @DisplayName("Failed conversion leaves neither a text record nor a NIF graph")
    void rollsBackInvalidControlledCommonMark() throws Exception {
        assumeConfigured();
        String fileId = null;
        try {
            Path invalid = write("invalid-" + UUID.randomUUID() + ".md",
                    "# Titolo senza attributo id\nTesto non convertibile");
            fileId = upload(invalid);
            assertStatus(post("texts/" + fileId + "/convert"), 200);

            JsonNode terminal = awaitTerminalJob(fileId, Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("FAILED");
            assertThat(terminal.path("issues").toString())
                    .contains("INVALID_HEADING", "MISSING_HEADING");
            assertStatus(get("texts/" + fileId), 404);
            assertStatus(get("texts/" + fileId + "/nif"), 404);
            assertNoServerFilesystemArtifacts(fileId);
        } finally {
            deleteQuietly(fileId == null ? null : "texts/" + fileId);
        }
    }

    private String upload(Path input) throws Exception {
        JsonNode response = json(uploadMultipart("texts/upload", input));
        String fileId = response.path("fileId").asText();
        assertThat(fileId).isNotBlank();
        return fileId;
    }

    private static void assumeConfigured() {
        Assumptions.assumeTrue(!baseUrl.isEmpty(),
                "Set -Dlexo.test.baseUrl to run deployed-service tests");
        Assumptions.assumeTrue(!authorization.isEmpty(),
                "Set -Dlexo.test.authorization to a valid Authorization header value");
    }

    /**
     * When tests run on the same host as LexO-server, this optional assertion
     * checks the rollback requirement directly on disk. Remote executions omit
     * lexo.test.storageDir and still verify record/NIF cleanup through REST.
     */
    private static void assertNoServerFilesystemArtifacts(String fileId) throws Exception {
        if (serverStorageDir.isEmpty()) {
            return;
        }
        Path root = java.nio.file.Paths.get(serverStorageDir).toAbsolutePath().normalize();
        assertThat(root.resolve("uploads").resolve(fileId)).doesNotExist();
        assertThat(root.resolve("documents").resolve(fileId)).doesNotExist();
        Path work = root.resolve("work");
        if (Files.isDirectory(work)) {
            try (Stream<Path> paths = Files.list(work)) {
                assertThat(paths.map(path -> path.getFileName().toString())
                        .filter(name -> name.startsWith(fileId + "-")))
                        .isEmpty();
            }
        }
    }

    private Response uploadMultipart(String endpoint, Path input) {
        FormDataMultiPart multipart = new FormDataMultiPart();
        multipart.bodyPart(new FileDataBodyPart("file", input.toFile()));
        try {
            Response response = request(endpoint).post(Entity.entity(multipart, multipart.getMediaType()));
            assertStatus(response, 200);
            return response;
        } finally {
            try {
                multipart.close();
            } catch (Exception ignored) {
                // Test cleanup must not hide the service assertion.
            }
        }
    }

    private JsonNode awaitTerminalJob(String fileId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            JsonNode jobs = json(get("texts/" + fileId + "/status"));
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
        throw new AssertionError("Text job did not terminate within " + timeout + "; last=" + last);
    }

    private Path write(String fileName, String content) throws Exception {
        Path path = temporaryFiles.resolve(fileName);
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static JsonNode json(Response response) throws Exception {
        assertStatus(response, 200);
        return JSON.readTree(response.readEntity(String.class));
    }

    private static Model turtle(Response response) throws Exception {
        assertStatus(response, 200);
        return parseTurtle(response.readEntity(byte[].class));
    }

    private static Model parseTurtle(byte[] bytes) throws Exception {
        return Rio.parse(new ByteArrayInputStream(bytes), "", RDFFormat.TURTLE);
    }

    private static Response get(String path) {
        return request(path).get();
    }

    private static Response post(String path) {
        return request(path).post(Entity.text(""));
    }

    private static Response delete(String path) {
        return request(path).delete();
    }

    private static Invocation.Builder request(String path) {
        return client.target(baseUrl + "/" + path).request()
                .header("Authorization", authorization);
    }

    private static void deleteQuietly(String path) {
        if (path != null) {
            try (Response ignored = delete(path)) {
                // Best-effort isolation cleanup.
            }
        }
    }

    private static void assertStatus(Response response, int expected) {
        if (response.getStatus() != expected) {
            // Read the body only on failure; reading it on a successful request
            // would consume a streamed JSON or Turtle entity before the test.
            String body = safeBody(response);
            assertThat(response.getStatus())
                    .withFailMessage("Expected HTTP %s but got %s: %s", expected,
                            response.getStatus(), body)
                    .isEqualTo(expected);
        }
    }

    private static String safeBody(Response response) {
        try {
            return response.hasEntity() ? response.readEntity(String.class) : "";
        } catch (RuntimeException ignored) {
            return "<unreadable response>";
        }
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
