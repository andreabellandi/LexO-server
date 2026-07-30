package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.text.input.TextTotalInput;
import it.cnr.ilc.lexo.service.data.text.output.TextTotalResult;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextTotalManagerTest {

    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";
    private static final String NIFS =
            "https://lexo.ilc.cnr.it/vocabulary/nif-structure#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository memory;
    private TextNifRepository repository;
    private TextTotalManager manager;

    @BeforeEach
    void setUp() {
        memory = new SailRepository(new MemoryStore());
        memory.init();
        repository = new TextNifRepository(memory);
        manager = new TextTotalManager(repository);
    }

    @AfterEach
    void tearDown() {
        memory.shutDown();
    }

    @Test
    void createsAndOverwritesOneDocumentTotalWithoutRemovingOtherUnits() {
        IRI context = iri("https://example.org/text#context");
        Model model = new LinkedHashModel();
        model.add(context, iri(NIFS + "fileId"), vf.createLiteral("file-a"));
        repository.saveDocument("file-a", model, context.stringValue(), null,
                null);

        TextTotalResult created = manager.replaceDocumentTotal("file-a",
                new TextTotalInput(Integer.valueOf(2312), "lexo:tokens"));
        manager.replaceDocumentTotal("file-a",
                new TextTotalInput(Integer.valueOf(12),
                        LEXO + "sentences"));
        TextTotalResult replaced = manager.replaceDocumentTotal("file-a",
                new TextTotalInput(Integer.valueOf(99), "tokens"));

        IRI graph = iri(repository.documentGraphUri("file-a"));
        assertThat(created.resource).isEqualTo(context.stringValue());
        assertThat(created.unit).isEqualTo(LEXO + "tokens");
        assertThat(created.value).isEqualTo(2312);
        assertThat(replaced.value).isEqualTo(99);
        try (RepositoryConnection connection = memory.getConnection()) {
            assertTotal(connection, context, graph, iri(LEXO + "tokens"), 99);
            assertTotal(connection, context, graph, iri(LEXO + "sentences"), 12);
            assertThat(connection.hasStatement(null, RDF.VALUE,
                    vf.createLiteral("2312", XSD.INT), false, graph)).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void createsAndOverwritesACorpusTotalInTheCorpusGraph() {
        IRI corpus = iri("https://example.org/corpus");
        Model model = new LinkedHashModel();
        model.add(corpus, iri(NIFS + "corpusId"), vf.createLiteral("corpus-a"));
        repository.saveCorpus("corpus-a", model);

        TextTotalResult created = manager.replaceCorpusTotal("corpus-a",
                new TextTotalInput(Integer.valueOf(18), "types"));
        manager.replaceCorpusTotal("corpus-a",
                new TextTotalInput(Integer.valueOf(20), "lexo:types"));

        IRI graph = iri(repository.corpusGraphUri("corpus-a"));
        assertThat(created.resource).isEqualTo(corpus.stringValue());
        try (RepositoryConnection connection = memory.getConnection()) {
            assertTotal(connection, corpus, graph, iri(LEXO + "types"), 20);
            assertThat(connection.hasStatement(null, null, null, false,
                    iri(repository.documentGraphUri("corpus-a")))).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void rejectsInvalidTotalsAndReturnsNullForMissingResources() {
        assertThatThrownBy(() -> manager.replaceDocumentTotal("file-a",
                new TextTotalInput(Integer.valueOf(-1), "tokens")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_TOTAL_VALUE");
        assertThatThrownBy(() -> manager.replaceDocumentTotal("file-a",
                new TextTotalInput(Integer.valueOf(1), "words")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_TOTAL_UNIT");
        assertThatThrownBy(() -> manager.replaceDocumentTotal("file/a",
                new TextTotalInput(Integer.valueOf(1), "lemmas")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_FILE_ID");
        assertThat(manager.replaceDocumentTotal("missing",
                new TextTotalInput(Integer.valueOf(1), "lemmas"))).isNull();
        assertThat(manager.replaceCorpusTotal("missing",
                new TextTotalInput(Integer.valueOf(1), "sentences"))).isNull();
    }

    private void assertTotal(RepositoryConnection connection, IRI subject,
                             IRI graph, IRI unit, int expected) {
        List<Resource> totals = totals(connection, subject, graph, unit);
        assertThat(totals).hasSize(1);
        Resource total = totals.get(0);
        assertThat(connection.hasStatement(total, RDF.TYPE,
                iri(FRAC + "Frequency"), false, graph)).isTrue();
        assertThat(connection.hasStatement(total, RDF.VALUE,
                vf.createLiteral(Integer.toString(expected), XSD.INT), false,
                graph)).isTrue();
        assertThat(connection.hasStatement(total, iri(FRAC + "unit"), unit,
                false, graph)).isTrue();
    }

    private List<Resource> totals(RepositoryConnection connection, IRI subject,
                                  IRI graph, IRI unit) {
        List<Resource> result = new ArrayList<Resource>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, iri(FRAC + "total"), null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof Resource
                        && connection.hasStatement((Resource) value,
                                iri(FRAC + "unit"), unit, false, graph)) {
                    result.add((Resource) value);
                }
            }
        }
        return result;
    }

    private void assertDefaultGraphEmpty(RepositoryConnection connection) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, null, null, false, (Resource) null)) {
            assertThat(statements.hasNext()).isFalse();
        }
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }
}
