package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

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
            model.add(vf.createIRI("https://lexo.test/doc"), RDF.TYPE,
                    vf.createIRI("https://lexo.test/Document"));

            repository.saveDocument("initialization-test", model,
                    "https://lexo.test/doc", null, null);

            assertThat(repository.containsDocument("initialization-test")).isTrue();
            repository.deleteDocument("initialization-test",
                    "https://lexo.test/doc", null, null);
            assertThat(repository.containsDocument("initialization-test")).isFalse();
        } finally {
            System.clearProperty("lexo.text.nifRepository.memory");
        }
    }
}
