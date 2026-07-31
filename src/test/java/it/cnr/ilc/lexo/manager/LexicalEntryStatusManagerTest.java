package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryStatusChange;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryStatusChangeRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryStatusChangeResult;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.Arrays;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexicalEntryStatusManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexicalEntryStatusManager manager;
    private IRI italianGraph;
    private IRI englishGraph;
    private IRI schemaGraph;
    private IRI word;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new LexicalEntryStatusManager(repository);
        italianGraph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        englishGraph = iri(LexiconCrudSupport.lexicalGraphUri("en"));
        schemaGraph = iri(LexicalNamedGraphs.schemaGraphUri());
        word = iri(ONTOLEX + "Word");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(word, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
        }
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void atomicallyChangesMoreThanOneEntryAndRecordsTheAuthor() {
        IRI first = addEntry("first", "working", italianGraph);
        IRI second = addEntry("second", "completed", italianGraph);
        LexicalEntryStatusChangeRequest request = request("IT",
                change(first, "working", "completed"),
                change(second, "completed", "revised"));

        LexicalEntryStatusChangeResult result = manager.change(request, "editor");

        assertThat(result.language).isEqualTo("it");
        assertThat(result.author).isEqualTo("editor");
        assertThat(result.entries).hasSize(2);
        assertThat(result.entries.get(0).previousStatus).isEqualTo("working");
        assertThat(result.entries.get(0).status).isEqualTo("completed");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertStatus(connection, first, "completed", italianGraph);
            assertStatus(connection, second, "revised", italianGraph);
            assertThat(connection.hasStatement(first,
                    iri(LEXO + "statusChangedBy"), vf.createLiteral("editor"),
                    false, italianGraph)).isTrue();
            assertThat(modified(connection, first, italianGraph).getDatatype())
                    .isEqualTo(XSD.DATETIME);
            assertThat(connection.hasStatement(first, null, null,
                    false, englishGraph)).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void supportsBothAllowedBackwardTransitionsAndAnonymousAuthor() {
        IRI completed = addEntry("completed", "completed", italianGraph);
        IRI revised = addEntry("revised", "revised", italianGraph);

        manager.change(request("it",
                change(completed, "completed", "working"),
                change(revised, "revised", "completed")), "   ");

        try (RepositoryConnection connection = repository.getConnection()) {
            assertStatus(connection, completed, "working", italianGraph);
            assertStatus(connection, revised, "completed", italianGraph);
            assertThat(connection.hasStatement(completed,
                    iri(LEXO + "statusChangedBy"), vf.createLiteral("anonymous"),
                    false, italianGraph)).isTrue();
        }
    }

    @Test
    void rejectsForbiddenJumpAndRollsBackTheWholeBatch() {
        IRI first = addEntry("first", "working", italianGraph);
        IRI second = addEntry("second", "working", italianGraph);
        LexicalEntryStatusChangeRequest request = request("it",
                change(first, "working", "completed"),
                change(second, "working", "revised"));

        assertThatThrownBy(() -> manager.change(request, "editor"))
                .isInstanceOf(LexicalEntryStatusManager.StatusChangeException.class)
                .hasMessageStartingWith("STATUS_TRANSITION_NOT_ALLOWED:");

        try (RepositoryConnection connection = repository.getConnection()) {
            assertStatus(connection, first, "working", italianGraph);
            assertStatus(connection, second, "working", italianGraph);
            assertThat(connection.hasStatement(null,
                    iri(LEXO + "statusChangedBy"), null, false,
                    italianGraph)).isFalse();
        }
    }

    @Test
    void rejectsStaleExpectedStatusAndMissingOrAmbiguousCurrentStatus() {
        IRI stale = addEntry("stale", "completed", italianGraph);
        assertThatThrownBy(() -> manager.change(request("it",
                change(stale, "working", "completed")), "editor"))
                .hasMessageStartingWith("STATUS_MISMATCH:");

        IRI missing = addEntry("missing", null, italianGraph);
        assertThatThrownBy(() -> manager.change(request("it",
                change(missing, "working", "completed")), "editor"))
                .hasMessageStartingWith("INVALID_STATUS_CARDINALITY:");

        IRI multiple = addEntry("multiple", "working", italianGraph);
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(multiple, iri(LEXO + "status"),
                    vf.createLiteral("completed"), italianGraph);
        }
        assertThatThrownBy(() -> manager.change(request("it",
                change(multiple, "working", "completed")), "editor"))
                .hasMessageStartingWith("INVALID_STATUS_CARDINALITY:");
    }

    @Test
    void rejectsResourcesOutsideTheLanguageGraphAndNonEntries() {
        IRI englishEntry = addEntry("english", "working", englishGraph);
        assertThatThrownBy(() -> manager.change(request("it",
                change(englishEntry, "working", "completed")), "editor"))
                .isInstanceOf(LexicalEntryStatusManager.StatusChangeException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404)
                .hasMessageStartingWith("ENTRY_NOT_FOUND:");

        IRI lexicon = iri("https://example.org/lexicon");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(lexicon, RDF.TYPE,
                    iri("http://www.w3.org/ns/lemon/lime#Lexicon"), italianGraph);
            connection.add(lexicon, iri(LEXO + "status"),
                    vf.createLiteral("working"), italianGraph);
        }
        assertThatThrownBy(() -> manager.change(request("it",
                change(lexicon, "working", "completed")), "editor"))
                .hasFieldOrPropertyWithValue("httpStatus", 422)
                .hasMessageStartingWith("UNSUPPORTED_STATUS_RESOURCE_TYPE:");
    }

    @Test
    void validatesRequestStatusValuesAndDuplicateEntriesBeforeWriting() {
        IRI entry = addEntry("entry", "working", italianGraph);
        assertThatThrownBy(() -> manager.change(null, "editor"))
                .hasMessageStartingWith("MISSING_STATUS_CHANGE:");
        assertThatThrownBy(() -> manager.change(request("zz",
                change(entry, "working", "completed")), "editor"))
                .hasMessageStartingWith("INVALID_LANGUAGE:");
        assertThatThrownBy(() -> manager.change(request("it",
                change(entry, "working", "reviewed")), "editor"))
                .hasMessageStartingWith("INVALID_STATUS:");
        assertThatThrownBy(() -> manager.change(request("it",
                change(entry, "working", "completed"),
                change(entry, "working", "completed")), "editor"))
                .hasMessageStartingWith("DUPLICATE_STATUS_ENTRY:");

        try (RepositoryConnection connection = repository.getConnection()) {
            assertStatus(connection, entry, "working", italianGraph);
        }
    }

    private IRI addEntry(String localName, String status, Resource graph) {
        IRI entry = iri("https://example.org/entry/" + localName);
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(entry, RDF.TYPE, word, graph);
            connection.add(entry, DCTERMS.MODIFIED,
                    vf.createLiteral("2026-07-31T10:00:00", XSD.DATETIME), graph);
            if (status != null) {
                connection.add(entry, iri(LEXO + "status"),
                        vf.createLiteral(status), graph);
            }
        }
        return entry;
    }

    private LexicalEntryStatusChange change(IRI entry, String from, String to) {
        return new LexicalEntryStatusChange(entry.stringValue(), from, to);
    }

    private LexicalEntryStatusChangeRequest request(
            String language, LexicalEntryStatusChange... changes) {
        LexicalEntryStatusChangeRequest request =
                new LexicalEntryStatusChangeRequest();
        request.language = language;
        request.entries = Arrays.asList(changes);
        return request;
    }

    private void assertStatus(RepositoryConnection connection, IRI entry,
                              String status, Resource graph) {
        assertThat(connection.hasStatement(entry, iri(LEXO + "status"),
                vf.createLiteral(status), false, graph)).isTrue();
        assertThat(count(connection, entry, iri(LEXO + "status"), graph))
                .isEqualTo(1);
    }

    private org.eclipse.rdf4j.model.Literal modified(
            RepositoryConnection connection, IRI entry, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                entry, DCTERMS.MODIFIED, null, false, graph)) {
            return (org.eclipse.rdf4j.model.Literal) statements.next().getObject();
        }
    }

    private int count(RepositoryConnection connection, Resource subject,
                      IRI predicate, Resource graph) {
        int count = 0;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                statements.next();
                count++;
            }
        }
        return count;
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
