package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Business use-case tests composed of complete multi-call workflows.
 *
 * <p>Unlike endpoint tests, every scenario verifies the observable REST result,
 * the actual named graph through SPARQL, and server-side filesystem state. These
 * tests therefore require a dedicated local test deployment.</p>
 */
class TextServiceUseCasesIT {

    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static TextWorkflowTestSupport workflow;

    @TempDir
    Path inputs;

    @BeforeAll
    static void configure() {
        workflow = new TextWorkflowTestSupport();
    }

    @AfterAll
    static void close() {
        if (workflow != null) {
            workflow.close();
        }
    }

    @Test
    @DisplayName("UC-01 Documento autonomo: lifecycle completo e pulizia totale")
    void standaloneDocumentFullLifecycle() throws Exception {
        workflow.assumeFullyConfigured();
        String originalName = "uc01-" + UUID.randomUUID() + ".txt";
        Path input = write(originalName,
                "---\ntitle: Documento autonomo\n---\n"
                        + "Prima frase. Seconda frase.");
        String fileId = null;
        try {
            // 1. Upload: il file deve esistere soltanto nell'area temporanea.
            fileId = workflow.upload(input);
            workflow.assertUploadedFilesExist(fileId);
            assertThat(workflow.graphExists(workflow.documentGraph(fileId))).isFalse();

            // 2-3. Conversione asincrona e polling fino allo stato terminale.
            workflow.startConversion(fileId, null);
            JsonNode terminal = workflow.awaitTerminalJob(fileId, Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("COMPLETED");

            // 4. Il NIF deve essere realmente scaricabile e non vuoto.
            byte[] nif = workflow.download("texts/" + fileId + "/nif");
            assertThat(new String(nif, StandardCharsets.UTF_8))
                    .contains("nif-core#", "Documento autonomo");

            // 5. Una ASK diretta prova che i dati sono nel repository testi.
            String graph = workflow.documentGraph(fileId);
            assertThat(workflow.ask("ASK WHERE { GRAPH <" + graph + "> "
                    + "{ ?context <" + NIF + "isString> ?text } }"))
                    .isTrue();

            // 6. Dopo il commit spariscono upload/work e restano gli artefatti finali.
            workflow.assertCompletedDocumentFiles(fileId, originalName, false, null);

            // 7-8. Delete seguito dalla verifica completa su tutti i livelli.
            assertThat(workflow.deleteText(fileId)).isTrue();
            workflow.assertServiceStatus("texts/" + fileId, 404);
            workflow.assertServiceStatus("texts/" + fileId + "/nif", 404);
            assertThat(workflow.graphExists(graph)).isFalse();
            workflow.assertNoDocumentArtifacts(fileId);
            fileId = null;
        } finally {
            workflow.deleteTextQuietly(fileId);
        }
    }

    @Test
    @DisplayName("UC-02 Corpus: due documenti, cancellazione corpus e documenti preservati")
    void corpusWithTwoDocumentsThenDetach() throws Exception {
        workflow.assumeFullyConfigured();
        String descriptorName = "uc02-corpus-" + UUID.randomUUID() + ".txt";
        String corpusId = null;
        String firstId = null;
        String secondId = null;
        try {
            // 1. Creazione del corpus vuoto.
            JsonNode corpus = workflow.createCorpus(write(descriptorName,
                    "---\nid: https://example.org/corpora/uc02\n"
                            + "title: Corpus con due documenti\n---\n"));
            corpusId = corpus.path("corpusId").asText();
            String corpusUri = corpus.path("corpusUri").asText();
            workflow.assertCorpusFilesExist(corpusId, descriptorName);
            assertThat(workflow.graphExists(workflow.corpusGraph(corpusId))).isTrue();

            // 2. Due conversioni separate aggiungono progressivamente membri.
            firstId = convertIntoCorpus("uc02-first.txt", "Primo documento.", corpusId);
            secondId = convertIntoCorpus("uc02-second.txt", "Secondo documento.", corpusId);
            JsonNode first = workflow.getJson("texts/" + firstId, 200);
            JsonNode second = workflow.getJson("texts/" + secondId, 200);
            String firstContext = first.path("documentUri").asText() + "#context";
            String secondContext = second.path("documentUri").asText() + "#context";

            // 3. SPARQL verifica entrambi i lati della relazione corpus/documento.
            String corpusGraph = workflow.corpusGraph(corpusId);
            assertThat(workflow.ask(hasPart(corpusGraph, corpusUri, firstContext))).isTrue();
            assertThat(workflow.ask(hasPart(corpusGraph, corpusUri, secondContext))).isTrue();
            assertThat(workflow.ask(isPartOf(workflow.documentGraph(firstId),
                    firstContext, corpusUri))).isTrue();
            assertThat(workflow.ask(isPartOf(workflow.documentGraph(secondId),
                    secondContext, corpusUri))).isTrue();

            // 4. Eliminare il corpus non deve eliminare i documenti.
            assertThat(workflow.deleteCorpus(corpusId)).isTrue();
            workflow.assertServiceStatus("texts/corpora/" + corpusId, 404);
            assertThat(workflow.graphExists(corpusGraph)).isFalse();
            workflow.assertNoCorpusArtifacts(corpusId);
            corpusId = null;

            JsonNode firstDetached = workflow.getJson("texts/" + firstId, 200);
            JsonNode secondDetached = workflow.getJson("texts/" + secondId, 200);
            // TextRecord omette intenzionalmente i campi null (NON_NULL): dopo
            // il detach corpusId deve quindi essere assente oppure JSON null.
            assertThat(isNullOrMissing(firstDetached, "corpusId")).isTrue();
            assertThat(isNullOrMissing(secondDetached, "corpusId")).isTrue();
            assertThat(workflow.ask(isPartOf(workflow.documentGraph(firstId),
                    firstContext, corpusUri))).isFalse();
            assertThat(workflow.ask(isPartOf(workflow.documentGraph(secondId),
                    secondContext, corpusUri))).isFalse();

            // 5. Cleanup esplicito dei documenti rimasti autonomi.
            String firstGraph = workflow.documentGraph(firstId);
            String secondGraph = workflow.documentGraph(secondId);
            assertThat(workflow.deleteText(firstId)).isTrue();
            assertThat(workflow.deleteText(secondId)).isTrue();
            workflow.assertNoDocumentArtifacts(firstId);
            workflow.assertNoDocumentArtifacts(secondId);
            assertThat(workflow.graphExists(firstGraph)).isFalse();
            assertThat(workflow.graphExists(secondGraph)).isFalse();
            firstId = null;
            secondId = null;
        } finally {
            workflow.deleteTextQuietly(firstId);
            workflow.deleteTextQuietly(secondId);
            workflow.deleteCorpusQuietly(corpusId);
        }
    }

    private static boolean isNullOrMissing(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value == null || value.isNull();
    }

    @Test
    @DisplayName("UC-03 TXT + CoNLL-U + metadati multipli: persistenza linguistica completa")
    void conlluAndMixedMetadataWorkflow() throws Exception {
        workflow.assumeFullyConfigured();
        String textName = "uc03-" + UUID.randomUUID() + ".txt";
        String conlluName = textName.replace(".txt", ".conllu");
        Path text = write(textName,
                "---\nauthor:\n  - Mario Rossi\n"
                        + "  - <https://example.org/people/bianchi>\n"
                        + "---\nMario corre.");
        Path conllu = write(conlluName,
                "# sent_id = s1\n# text = Mario corre.\n"
                        + "# start_char = 0\n# end_char = 12\n"
                        + "1\tMario\tMario\tPROPN\t_\t_\t2\tnsubj\t_\tTokenRange=0:5\n"
                        + "2\tcorre\tcorrere\tVERB\t_\t_\t0\troot\t_\tTokenRange=6:11\n"
                        + "3\t.\t.\tPUNCT\t_\t_\t2\tpunct\t_\tTokenRange=11:12\n");
        String fileId = null;
        try {
            fileId = workflow.upload(text, conllu);
            workflow.startConversion(fileId, null);
            assertThat(workflow.awaitTerminalJob(fileId, Duration.ofSeconds(30))
                    .path("state").asText()).isEqualTo("COMPLETED");
            workflow.assertCompletedDocumentFiles(fileId, textName, true, conlluName);
            assertThat(workflow.download("texts/" + fileId + "/conllu")).isNotEmpty();

            String graph = workflow.documentGraph(fileId);
            // Un singolo ASK controlla lemma, autore letterale e autore URI.
            assertThat(workflow.ask("ASK WHERE { GRAPH <" + graph + "> { "
                    + "?token <" + NIF + "lemma> ?lemma . "
                    + "?context <" + DCTERMS + "creator> \"Mario Rossi\" ; "
                    + "<" + DCTERMS + "creator> <https://example.org/people/bianchi> . "
                    + "FILTER(STR(?lemma) = \"correre\") } }"))
                    .isTrue();

            JsonNode record = workflow.getJson("texts/" + fileId, 200);
            assertThat(record.path("lemmaSearchAvailable").asBoolean()).isTrue();
            assertThat(record.path("lemmaTokenCount").asInt()).isEqualTo(3);
            assertThat(record.path("lemmaCoverage").asDouble()).isEqualTo(1.0d);

            Map<String, Object> lemmaQuery = new LinkedHashMap<String, Object>();
            lemmaQuery.put("query", "correre");
            lemmaQuery.put("queryType", "TERM");
            lemmaQuery.put("searchField", "LEMMA");
            lemmaQuery.put("contextIRI", record.path("documentUri").asText()
                    + "#context");
            JsonNode lemmaSearch = workflow.postJson("texts/search/fulltext",
                    lemmaQuery, 200);
            assertThat(lemmaSearch.path("totalOccurrences").asLong()).isEqualTo(1L);
            assertThat(lemmaSearch.path("occurrences").get(0).path("match")
                    .path("text").asText()).isEqualTo("corre");

            assertThat(workflow.deleteText(fileId)).isTrue();
            assertThat(workflow.graphExists(graph)).isFalse();
            workflow.assertNoDocumentArtifacts(fileId);
            fileId = null;
        } finally {
            workflow.deleteTextQuietly(fileId);
        }
    }

    @Test
    @DisplayName("UC-04 CoNLL-U non allineato: rollback completo dopo job FAILED")
    void invalidConlluRollsBackEveryPersistenceLayer() throws Exception {
        workflow.assumeFullyConfigured();
        String textName = "uc04-" + UUID.randomUUID() + ".txt";
        String conlluName = textName.replace(".txt", ".conllu");
        Path text = write(textName, "Mario corre.");
        Path invalidConllu = write(conlluName,
                "# start_char = 0\n# end_char = 12\n"
                        + "1\tLuigi\t_\tPROPN\t_\t_\t0\troot\t_\tTokenRange=0:5\n");
        String fileId = null;
        try {
            fileId = workflow.upload(text, invalidConllu);
            workflow.assertUploadedFilesExist(fileId);
            workflow.startConversion(fileId, null);
            JsonNode terminal = workflow.awaitTerminalJob(fileId, Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("FAILED");
            assertThat(terminal.path("issues").toString()).contains("TOKEN_FORM_MISMATCH");

            workflow.assertServiceStatus("texts/" + fileId, 404);
            workflow.assertServiceStatus("texts/" + fileId + "/nif", 404);
            assertThat(workflow.graphExists(workflow.documentGraph(fileId))).isFalse();
            workflow.assertNoDocumentArtifacts(fileId);
        } finally {
            workflow.deleteTextQuietly(fileId);
        }
    }

    @Test
    @DisplayName("UC-05 Cartelle testuali: gerarchia, move, unfiled e cleanup su delete")
    void textFolderOrganizationWorkflow() throws Exception {
        workflow.assumeFullyConfigured();
        String firstId = null;
        String secondId = null;
        String rootId = null;
        String childId = null;
        try {
            firstId = convertIntoCorpus("uc05-first.txt", "Primo testo.", null);
            secondId = convertIntoCorpus("uc05-second.txt", "Secondo testo.", null);
            JsonNode first = workflow.getJson("texts/" + firstId, 200);
            JsonNode second = workflow.getJson("texts/" + secondId, 200);
            String firstContext = first.path("documentUri").asText() + "#context";
            String secondContext = second.path("documentUri").asText() + "#context";
            long indexedBefore = workflow.getJson("texts/index/status", 200)
                    .path("documents").asLong();

            Map<String, Object> rootRequest = new LinkedHashMap<String, Object>();
            rootRequest.put("name", "UC-05 A");
            JsonNode root = workflow.postJson("text-folders", rootRequest, 201);
            rootId = root.path("folderId").asText();
            String rootUri = root.path("uri").asText();

            Map<String, Object> childRequest = new LinkedHashMap<String, Object>();
            childRequest.put("name", "UC-05 B");
            childRequest.put("parentFolderId", rootId);
            JsonNode child = workflow.postJson("text-folders", childRequest, 201);
            childId = child.path("folderId").asText();
            String childUri = child.path("uri").asText();

            workflow.put("text-folders/" + rootId + "/texts/" + firstId, 200);
            workflow.put("text-folders/" + childId + "/texts/" + secondId, 200);
            assertThat(workflow.ask(folderContains(workflow.organizationGraph,
                    rootUri, firstContext))).isTrue();
            assertThat(workflow.ask(folderContains(workflow.organizationGraph,
                    childUri, secondContext))).isTrue();

            Map<String, Object> moveToRoot = new LinkedHashMap<String, Object>();
            moveToRoot.put("parentFolderId", null);
            workflow.postJson("text-folders/" + childId + "/move",
                    moveToRoot, 200);
            workflow.put("text-folders/" + childId + "/texts/" + firstId, 200);
            assertThat(workflow.ask(folderContains(workflow.organizationGraph,
                    rootUri, firstContext))).isFalse();
            assertThat(workflow.ask(folderContains(workflow.organizationGraph,
                    childUri, firstContext))).isTrue();
            assertThat(workflow.getJson("text-folders?includeTexts=true", 200)
                    .toString()).contains(rootId, childId, firstId, secondId);
            assertThat(workflow.getJson("texts/index/status", 200)
                    .path("documents").asLong()).isEqualTo(indexedBefore);

            assertThat(workflow.deleteText(firstId)).isTrue();
            assertThat(workflow.ask(folderContains(workflow.organizationGraph,
                    childUri, firstContext))).isFalse();
            firstId = null;
            workflow.deleteJson("text-folders/" + childId + "/texts/"
                    + secondId, 200);
            assertThat(workflow.getJson("text-folders/unfiled", 200)
                    .toString()).contains(secondId);

            assertThat(workflow.deleteJson("text-folders/" + childId, 200)
                    .path("deleted").asBoolean()).isTrue();
            childId = null;
            assertThat(workflow.deleteJson("text-folders/" + rootId, 200)
                    .path("deleted").asBoolean()).isTrue();
            rootId = null;
            assertThat(workflow.deleteText(secondId)).isTrue();
            secondId = null;
        } finally {
            workflow.deleteTextQuietly(firstId);
            workflow.deleteTextQuietly(secondId);
            workflow.deleteFolderQuietly(childId);
            workflow.deleteFolderQuietly(rootId);
        }
    }

    private String convertIntoCorpus(String name, String content, String corpusId) throws Exception {
        String uniqueName = UUID.randomUUID() + "-" + name;
        String fileId = workflow.upload(write(uniqueName, content));
        try {
            workflow.startConversion(fileId, corpusId);
            JsonNode terminal = workflow.awaitTerminalJob(fileId, Duration.ofSeconds(30));
            assertThat(terminal.path("state").asText()).isEqualTo("COMPLETED");
            workflow.assertCompletedDocumentFiles(fileId, uniqueName, false, null);
            return fileId;
        } catch (Exception | AssertionError error) {
            workflow.deleteTextQuietly(fileId);
            throw error;
        }
    }

    private Path write(String name, String content) throws Exception {
        Path file = inputs.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String hasPart(String graph, String corpusUri, String contextUri) {
        return "ASK WHERE { GRAPH <" + graph + "> { <" + corpusUri + "> <"
                + DCTERMS + "hasPart> <" + contextUri + "> } }";
    }

    private static String isPartOf(String graph, String contextUri, String corpusUri) {
        return "ASK WHERE { GRAPH <" + graph + "> { <" + contextUri + "> <"
                + DCTERMS + "isPartOf> <" + corpusUri + "> } }";
    }

    private static String folderContains(String graph, String folderUri,
                                         String contextUri) {
        return "ASK WHERE { GRAPH <" + graph + "> { <"
                + folderUri + "> <https://lexo.ilc.cnr.it/vocabulary/text-organization#containsText> <"
                + contextUri + "> } }";
    }
}
