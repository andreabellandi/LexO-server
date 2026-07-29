package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConcept;
import it.cnr.ilc.lexo.service.helper.LexicalConceptHelper;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SKOSManagerTest {

    private static final String ONTOLEX =
            "http://www.w3.org/ns/lemon/ontolex#";
    private static final String SKOS =
            "http://www.w3.org/2004/02/skos/core#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();

    @Test
    @DisplayName("A lexical concept label requires a language")
    void rejectsLabelWithoutLanguage() {
        assertThatThrownBy(() -> new SKOSManager().createLexicalConcept(
                "user", "test", "https://example.test/", "concept", "Animal", null))
                .isInstanceOf(ManagerException.class)
                .hasMessageStartingWith("MISSING_LANGUAGE:");
    }

    @Test
    @DisplayName("A lexical concept language must occur in the bundled ISO list")
    void rejectsLanguageOutsideBundledIsoList() {
        assertThatThrownBy(() -> new SKOSManager().createLexicalConcept(
                "user", "test", "https://example.test/", "concept",
                "Animal", "not-a-language"))
                .isInstanceOf(ManagerException.class)
                .hasMessageStartingWith("INVALID_LANGUAGE:");
    }

    @Test
    @DisplayName("The preferred label is written only to the lexical named graph")
    void writesLanguageTaggedPrefLabelToLexicalGraph() {
        Repository repository = new SailRepository(new MemoryStore());
        repository.init();
        IRI concept = vf.createIRI("https://example.test/concept");
        IRI graph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
        Literal label = vf.createLiteral("Animal \"quoted\"", "en");
        try (RepositoryConnection connection = repository.getConnection()) {
            String query = SKOSManager.buildCreateLexicalConceptQuery(
                    concept.stringValue(), "user", "PREFIX test: <https://example.test/>",
                    "2026-07-28", label.getLabel(), "en");
            Update update = connection.prepareUpdate(QueryLanguage.SPARQL, query);
            LexicalNamedGraphs.configure(update, LexicalNamedGraphs.Kind.LEXICON);
            update.execute();

            assertThat(connection.hasStatement(concept, RDF.TYPE,
                    vf.createIRI(ONTOLEX + "LexicalConcept"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, vf.createIRI(SKOS + "prefLabel"),
                    label, false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, vf.createIRI(SKOS + "prefLabel"),
                    label, false, (Resource) null)).isFalse();
        } finally {
            repository.shutDown();
        }
    }

    @Test
    @DisplayName("The creation response exposes the preferred label field")
    void returnsLabelInCreationOutput() throws Exception {
        LexicalConcept result = SKOSManager.setLexicalConcept(
                "https://example.test/concept", "Animale", "it",
                "2026-07-28", "user");

        assertThat(result.getLabel()).isEqualTo("Animale");
        assertThat(result.getLanguage()).isEqualTo("it");
        assertThat(new ObjectMapper().readTree(
                new LexicalConceptHelper().toJson(result)).get("label").asText())
                .isEqualTo("Animale");
    }
}
