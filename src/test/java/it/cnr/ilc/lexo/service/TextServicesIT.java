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
import org.glassfish.jersey.client.ClientProperties;
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
            client = ClientBuilder.newBuilder()
                    .property(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION,
                            true)
                    .register(MultiPartFeature.class)
                    .build();
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
            String exactText = "  Prima  riga senza heading.\r\nSeconda\triga.  ";
            Path input = write("plain-" + UUID.randomUUID() + ".txt",
                    exactText);
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
            assertThat(nif.contains(null, iri(DCTERMS + "language"),
                    SimpleValueFactory.getInstance().createLiteral("it"))).isTrue();

            assertStatus(get("texts/" + fileId + "/original"), 200);
            assertThat(get("texts/" + fileId + "/canonical")
                    .readEntity(String.class))
                    .isEqualTo(exactText);

            JsonNode catalog = json(get("texts"));
            JsonNode catalogItem = findText(catalog, fileId);
            assertThat(catalogItem).isNotNull();
            assertThat(catalogItem.path("name").asText()).isEqualTo(input.getFileName().toString());
            assertThat(catalogItem.path("sizeBytes").asLong()).isPositive();
            assertThat(catalogItem.path("sentenceCount").asInt()).isPositive();
            assertThat(catalogItem.path("tokenCount").asInt()).isPositive();
            assertThat(catalogItem.path("attestationCount").asLong()).isNotNegative();
            assertThat(catalogItem.path("annotationCount").asLong()).isNotNegative();

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
    @DisplayName("Controlled CommonMark keeps converting an internal soft break to a space")
    void keepsCommonMarkSoftBreakBehavior() throws Exception {
        assumeConfigured();
        String fileId = null;
        try {
            Path input = write("commonmark-" + UUID.randomUUID() + ".md",
                    "# [id=chapter-1] Capitolo\nPrima riga.\nSeconda riga.");
            fileId = upload(input);

            assertStatus(post("texts/" + fileId + "/convert"), 200);
            JsonNode terminal = awaitTerminalJob(fileId, Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("COMPLETED");
            assertThat(get("texts/" + fileId + "/canonical")
                    .readEntity(String.class))
                    .isEqualTo("Capitolo\n\nPrima riga. Seconda riga.");
        } finally {
            deleteQuietly(fileId == null ? null : "texts/" + fileId);
        }
    }

    @Test
    @DisplayName("Text upload rejects missing and unknown ISO 639 language codes")
    void validatesRequiredUploadLanguage() throws Exception {
        assumeConfigured();
        Path input = write("language-" + UUID.randomUUID() + ".txt", "Testo.");

        try (Response missing = uploadMultipartWithLanguage(input, null)) {
            assertStatus(missing, 400);
            assertThat(missing.readEntity(String.class)).contains("MISSING_LANGUAGE");
        }
        try (Response invalid = uploadMultipartWithLanguage(input, "not-a-language")) {
            assertStatus(invalid, 400);
            assertThat(invalid.readEntity(String.class)).contains("INVALID_LANGUAGE");
        }
    }

    @Test
    @DisplayName("Bulk TXT/CommonMark conversion keeps successful documents after a partial failure")
    void convertsBulkWithIndependentRollback() throws Exception {
        assumeConfigured();
        java.util.List<String> fileIds = new java.util.ArrayList<String>();
        try {
            Path valid = write("bulk-valid-" + UUID.randomUUID() + ".txt",
                    "Documento bulk valido.");
            Path invalid = write("bulk-invalid-" + UUID.randomUUID() + ".md",
                    "# Heading senza identificatore\nDocumento bulk non valido.");
            FormDataMultiPart multipart = new FormDataMultiPart();
            multipart.field("language", "it");
            multipart.bodyPart(new FileDataBodyPart("file", valid.toFile()));
            multipart.bodyPart(new FileDataBodyPart("file", invalid.toFile()));
            JsonNode accepted;
            try {
                Response response = request("texts/bulk")
                        .post(Entity.entity(multipart, multipart.getMediaType()));
                assertStatus(response, 202);
                accepted = JSON.readTree(response.readEntity(String.class));
            } finally {
                multipart.close();
            }

            String bulkId = accepted.path("bulkId").asText();
            assertThat(bulkId).isNotBlank();
            assertThat(accepted.path("language").asText()).isEqualTo("it");
            assertThat(accepted.path("items")).hasSize(2);
            for (JsonNode item : accepted.path("items")) {
                fileIds.add(item.path("fileId").asText());
            }

            JsonNode terminal = awaitTerminalBulk(bulkId, Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("PARTIALLY_COMPLETED");
            assertThat(terminal.path("completed").asInt()).isEqualTo(1);
            assertThat(terminal.path("failed").asInt()).isEqualTo(1);

            for (JsonNode item : terminal.path("items")) {
                String fileId = item.path("fileId").asText();
                if ("COMPLETED".equals(item.path("state").asText())) {
                    assertStatus(get("texts/" + fileId), 200);
                } else {
                    assertThat(item.path("state").asText()).isEqualTo("FAILED");
                    assertStatus(get("texts/" + fileId), 404);
                    assertNoServerFilesystemArtifacts(fileId);
                }
            }
        } finally {
            for (String fileId : fileIds) {
                deleteQuietly("texts/" + fileId);
            }
        }
    }

    @Test
    @DisplayName("Asynchronous bulk deletion applies independent text outcomes")
    void deletesMultipleTextsAsynchronously() throws Exception {
        assumeConfigured();
        java.util.List<String> fileIds = new java.util.ArrayList<String>();
        try {
            for (int index = 0; index < 2; index++) {
                Path input = write("bulk-delete-" + index + "-"
                        + UUID.randomUUID() + ".txt", "Testo " + index + ".");
                String fileId = upload(input);
                fileIds.add(fileId);
                assertStatus(post("texts/" + fileId + "/convert"), 200);
                assertThat(awaitTerminalJob(fileId, Duration.ofSeconds(30))
                        .path("state").asText()).isEqualTo("COMPLETED");
            }
            String missing = "missing-" + UUID.randomUUID();
            com.fasterxml.jackson.databind.node.ObjectNode deleteRequest =
                    JSON.createObjectNode();
            deleteRequest.putArray("fileIds").add(fileIds.get(0)).add(missing)
                    .add(fileIds.get(1));
            Response acceptedResponse = request("texts/bulk").method("DELETE",
                    Entity.json(deleteRequest.toString()));
            assertStatus(acceptedResponse, 202);
            JsonNode accepted = JSON.readTree(
                    acceptedResponse.readEntity(String.class));
            String bulkId = accepted.path("bulkId").asText();
            assertThat(bulkId).isNotBlank();

            JsonNode terminal = awaitTerminalDeletion(bulkId,
                    Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("COMPLETED");
            assertThat(terminal.path("deleted").asInt()).isEqualTo(2);
            assertThat(terminal.path("notFound").asInt()).isEqualTo(1);
            assertThat(terminal.path("failed").asInt()).isZero();
            assertThat(terminal.path("items")).hasSize(3);
            assertThat(terminal.path("items").get(0).path("state").asText())
                    .isEqualTo("DELETED");
            assertThat(terminal.path("items").get(1).path("state").asText())
                    .isEqualTo("NOT_FOUND");
            assertThat(terminal.path("items").get(2).path("state").asText())
                    .isEqualTo("DELETED");

            for (String fileId : fileIds) {
                assertStatus(get("texts/" + fileId), 404);
                assertNoServerFilesystemArtifacts(fileId);
            }
            fileIds.clear();
        } finally {
            for (String fileId : fileIds) {
                deleteQuietly("texts/" + fileId);
            }
        }
    }

    @Test
    @DisplayName("Bulk JSON converts text and reports an invalid attestation without rolling it back")
    void convertsJsonAndReportsUnsavedAttestations() throws Exception {
        assumeConfigured();
        String fileId = null;
        try {
            Path input = write("bulk-json-" + UUID.randomUUID() + ".json", "{"
                    + "\"metadata\":{\"title\":\"Intervista JSON\"},"
                    + "\"text\":{\"type\":\"txt\","
                    + "\"content\":\"  Testo  importato.\\r\\nSeconda\\triga. \"},"
                    + "\"attestations\":[{\"id\":\"missing-1\",\"observable\":\"\","
                    + "\"type\":\"http://www.w3.org/ns/lemon/ontolex#LexicalSense\","
                    + "\"value\":\"Testo\",\"gloss\":\"Testo\","
                    + "\"start_char\":0,\"end_char\":5}]}" );
            FormDataMultiPart multipart = new FormDataMultiPart();
            multipart.field("language", "it");
            multipart.bodyPart(new FileDataBodyPart("file", input.toFile()));
            JsonNode accepted;
            try {
                Response response = request("texts/bulk")
                        .post(Entity.entity(multipart, multipart.getMediaType()));
                assertStatus(response, 202);
                accepted = JSON.readTree(response.readEntity(String.class));
            } finally {
                multipart.close();
            }

            String bulkId = accepted.path("bulkId").asText();
            fileId = accepted.path("items").get(0).path("fileId").asText();
            JsonNode terminal = awaitTerminalBulk(bulkId, Duration.ofSeconds(30));
            JsonNode item = terminal.path("items").get(0);
            assertThat(terminal.path("state").asText()).isEqualTo("COMPLETED");
            assertThat(item.path("state").asText()).isEqualTo("COMPLETED");
            assertThat(item.path("inputType").asText()).isEqualTo("json");
            assertThat(item.path("attestationState").asText()).isEqualTo("FAILED");
            assertThat(item.path("attestationTotal").asInt()).isEqualTo(1);
            assertThat(item.path("savedAttestations").asInt()).isZero();
            assertThat(item.path("unsavedAttestations")).hasSize(1);
            assertThat(item.path("unsavedAttestations").get(0).path("id").asText())
                    .isEqualTo("missing-1");
            assertThat(item.path("unsavedAttestations").get(0).path("code").asText())
                    .isEqualTo("MISSING_PARAMETER");

            assertThat(get("texts/" + fileId + "/canonical").readEntity(String.class))
                    .isEqualTo("  Testo  importato.\r\nSeconda\triga. ");
            assertThat(get("texts/" + fileId + "/original")
                    .getMediaType().toString()).startsWith("application/json");
            Model nif = turtle(get("texts/" + fileId + "/nif"));
            assertThat(nif.contains(null, iri(DCTERMS + "title"),
                    SimpleValueFactory.getInstance().createLiteral(
                            "Intervista JSON", "it"))).isTrue();
        } finally {
            deleteQuietly(fileId == null ? null : "texts/" + fileId);
        }
    }

    @Test
    @DisplayName("A JSON-only bulk rejects the corpusId query parameter")
    void rejectsQueryCorpusForJsonOnlyBulk() throws Exception {
        assumeConfigured();
        Path input = write("bulk-json-corpus-" + UUID.randomUUID() + ".json",
                "{\"text\":{\"type\":\"txt\",\"content\":\"Testo.\"}}");
        FormDataMultiPart multipart = new FormDataMultiPart();
        multipart.field("language", "it");
        multipart.bodyPart(new FileDataBodyPart("file", input.toFile()));
        try {
            Response response = request("texts/bulk?corpusId=corpus-a")
                    .post(Entity.entity(multipart, multipart.getMediaType()));
            assertStatus(response, 400);
            assertThat(JSON.readTree(response.readEntity(String.class)).path("code").asText())
                    .isEqualTo("CORPUS_ID_NOT_ALLOWED_FOR_JSON");
        } finally {
            multipart.close();
        }
    }

    @Test
    @DisplayName("A CoNLL-U part rejects the complete bulk before conversion")
    void rejectsConlluInBulk() throws Exception {
        assumeConfigured();
        Path text = write("bulk-text-" + UUID.randomUUID() + ".txt", "Testo.");
        Path conllu = write("bulk-tokens-" + UUID.randomUUID() + ".conllu",
                "# sent_id = s1\n1\tTesto\ttesto\tNOUN\t_\t_\t0\troot\t_\tTokenRange=0:5\n");
        FormDataMultiPart multipart = new FormDataMultiPart();
        multipart.field("language", "it");
        multipart.bodyPart(new FileDataBodyPart("file", text.toFile()));
        multipart.bodyPart(new FileDataBodyPart("conllu", conllu.toFile()));
        try {
            Response response = request("texts/bulk")
                    .post(Entity.entity(multipart, multipart.getMediaType()));
            assertStatus(response, 400);
            JsonNode error = JSON.readTree(response.readEntity(String.class));
            assertThat(error.path("code").asText()).isEqualTo("BULK_CONLLU_NOT_ALLOWED");
        } finally {
            multipart.close();
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

    private static JsonNode findText(JsonNode catalog, String fileId) {
        for (JsonNode item : catalog.path("texts")) {
            if (fileId.equals(item.path("fileId").asText())) {
                return item;
            }
        }
        return null;
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
        if ("texts/upload".equals(endpoint)) {
            multipart.field("language", "it");
        }
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

    private Response uploadMultipartWithLanguage(Path input, String language) {
        FormDataMultiPart multipart = new FormDataMultiPart();
        if (language != null) {
            multipart.field("language", language);
        }
        multipart.bodyPart(new FileDataBodyPart("file", input.toFile()));
        try {
            return request("texts/upload")
                    .post(Entity.entity(multipart, multipart.getMediaType()));
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

    private JsonNode awaitTerminalBulk(String bulkId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = json(get("texts/bulk/" + bulkId + "/status"));
            String state = last.path("state").asText();
            if ("COMPLETED".equals(state) || "PARTIALLY_COMPLETED".equals(state)
                    || "FAILED".equals(state) || "CANCELLED".equals(state)) {
                return last;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("Bulk job did not terminate within " + timeout + "; last=" + last);
    }

    private JsonNode awaitTerminalDeletion(String bulkId, Duration timeout)
            throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = json(get("texts/deletions/" + bulkId + "/status"));
            String state = last.path("state").asText();
            if ("COMPLETED".equals(state)
                    || "PARTIALLY_COMPLETED".equals(state)
                    || "FAILED".equals(state)) {
                return last;
            }
            Thread.sleep(200L);
        }
        throw new AssertionError("Bulk deletion did not terminate within "
                + timeout + "; last=" + last);
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
