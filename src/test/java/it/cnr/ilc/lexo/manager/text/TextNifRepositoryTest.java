package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.service.data.text.output.TextRecord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
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
    void initializesSingletonAndPersistsAGraph() {
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

            TextRecord saved = new TextRecord();
            saved.fileId = "initialization-test";
            saved.documentUri = "https://lexo.test/doc";
            saved.originalFileName = "original.txt";
            saved.originalPath = "documents/initialization-test/original/original.txt";

            repository.saveDocument("initialization-test", model,
                    context.stringValue(), null, null, saved);

            assertThat(repository.containsDocument("initialization-test")).isTrue();
            assertThat(repository.getCanonicalText("initialization-test"))
                    .isEqualTo("Testo canonico");
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
            repository.deleteDocument("initialization-test",
                    context.stringValue(), null, null);
        } finally {
            System.clearProperty("lexo.text.nifRepository.memory");
        }
    }
}
