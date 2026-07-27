package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.service.data.text.output.TextRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Guards singleton initialization and the in-memory repository test mode. */
class TextNifRepositoryTest {

    @Test
    @DisplayName("Text NIF repository initializes before its first operation")
    void initializesSingletonAndPersistsAGraph() throws Exception {
        System.setProperty("lexo.text.nifRepository.memory", "true");
        try {
            TextNifRepository repository = TextNifRepository.get();
            Model model = new LinkedHashModel();
            org.eclipse.rdf4j.model.ValueFactory vf = SimpleValueFactory.getInstance();
            org.eclipse.rdf4j.model.IRI context =
                    vf.createIRI("https://lexo.test/doc#context");
            model.add(context, RDF.TYPE,
                    vf.createIRI("https://lexo.test/Document"));
            model.add(context,
                    vf.createIRI("http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#isString"),
                    vf.createLiteral("Testo canonico"));
            model.add(context,
                    vf.createIRI("http://purl.org/dc/terms/description"),
                    vf.createLiteral("Descrizione del testo"));

            TextRecord saved = new TextRecord();
            saved.fileId = "initialization-test";
            saved.documentUri = "https://lexo.test/doc";
            saved.originalFileName = "original.txt";
            saved.originalPath = "documents/initialization-test/original/original.txt";
            saved.metadata.put("description", "Descrizione del testo");
            saved.metadataValues.put("description",
                    Arrays.asList("Descrizione del testo"));

            repository.saveDocument("initialization-test", model,
                    context.stringValue(), null, null, saved);

            assertThat(repository.containsDocument("initialization-test")).isTrue();
            assertThat(repository.getCanonicalText("initialization-test"))
                    .isEqualTo("Testo canonico");
            assertThat(repository.getDocumentRecord("initialization-test")
                    .metadataValues.get("description"))
                    .containsExactly("Descrizione del testo");
            assertThat(repository.getDocumentRecord("initialization-test"))
                    .extracting(record -> record.fileId,
                            record -> record.originalFileName,
                            record -> record.originalPath)
                    .containsExactly("initialization-test", "original.txt",
                            "documents/initialization-test/original/original.txt");
            ByteArrayOutputStream exported = new ByteArrayOutputStream();
            repository.writeDocument("initialization-test", exported);
            assertThat(new String(exported.toByteArray(), StandardCharsets.UTF_8))
                    .doesNotContain("recordJson", "originalPath");
            repository.deleteDocument("initialization-test",
                    context.stringValue(), null, null);
            assertThat(repository.containsDocument("initialization-test")).isFalse();

            // Recreating only the semantic graph must not resurrect the deleted
            // operational record from the dedicated records graph.
            repository.saveDocument("initialization-test", model,
                    context.stringValue(), null, null);
            assertThat(repository.getDocumentRecord("initialization-test").originalFileName)
                    .isNull();
            assertThat(repository.getDocumentRecord("initialization-test")
                    .metadataValues.get("description"))
                    .containsExactly("Descrizione del testo");
            repository.deleteDocument("initialization-test",
                    context.stringValue(), null, null);

            ControlledCommonMarkParser parser = new ControlledCommonMarkParser();
            NifModelWriter writer = new NifModelWriter(
                    "https://lexo.ilc.cnr.it/resources/texts/",
                    "https://lexo.ilc.cnr.it/vocabulary/nif-structure#");
            repository.saveCorpus("description-corpus", writer.buildCorpus(
                    "description-corpus", "corpus.txt", parser.parseMetadataOnly(
                            "---\ndescription: Descrizione del corpus\n---\n"), null));
            assertThat(repository.getCorpusRecord("description-corpus")
                    .metadataValues.get("description"))
                    .containsExactly("Descrizione del corpus");
            repository.deleteCorpus("description-corpus",
                    writer.corpusUri("description-corpus"));
        } finally {
            System.clearProperty("lexo.text.nifRepository.memory");
        }
    }
}
