package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
                "---\ntitle: Documento autonomo\nlanguage: it\n---\n"
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
                            + "title: Corpus con due documenti\nlanguage: it\n---\n"));
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
            assertThat(firstDetached.path("corpusId").isNull()).isTrue();
            assertThat(secondDetached.path("corpusId").isNull()).isTrue();
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

    @Test
    @DisplayName("UC-03 TXT + CoNLL-U + metadati multipli: persistenza linguistica completa")
    void conlluAndMixedMetadataWorkflow() throws Exception {
        workflow.assumeFullyConfigured();
        String textName = "uc03-" + UUID.randomUUID() + ".txt";
        String conlluName = textName.replace(".txt", ".conllu");
        Path text = write(textName,
                "---\nauthor:\n  - Mario Rossi\n"
                        + "  - <https://example.org/people/bianchi>\n"
                        + "language: it\n---\nMario corre.");
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
}
