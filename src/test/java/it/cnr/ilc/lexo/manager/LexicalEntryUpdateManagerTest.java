package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryUpdateRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryUpdateResult;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
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

class LexicalEntryUpdateManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LEXINFO =
            "http://www.lexinfo.net/ontology/3.0/lexinfo#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexicalEntryUpdateManager manager;
    private IRI graph;
    private IRI schemaGraph;
    private IRI entry;
    private IRI word;
    private IRI multiword;
    private IRI noun;
    private IRI adjective;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new LexicalEntryUpdateManager(repository);
        graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        schemaGraph = iri(LexicalNamedGraphs.schemaGraphUri());
        entry = iri("https://example.org/entry/1");
        word = iri(ONTOLEX + "Word");
        multiword = iri(ONTOLEX + "MultiWordExpression");
        noun = iri(LEXINFO + "noun");
        adjective = iri(LEXINFO + "adjective");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(word, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
            connection.add(multiword, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
            connection.add(noun, RDF.TYPE,
                    iri(LEXINFO + "PartOfSpeech"), schemaGraph);
            connection.add(adjective, RDF.TYPE,
                    iri(LEXINFO + "PartOfSpeech"), schemaGraph);
            connection.add(entry, RDF.TYPE, word, graph);
            connection.add(entry, RDFS.LABEL, vf.createLiteral("casa", "it"), graph);
            connection.add(entry, iri(LEXINFO + "partOfSpeech"), noun, graph);
            connection.add(entry, DCTERMS.CREATOR, vf.createLiteral("creator"), graph);
            connection.add(entry, DCTERMS.CREATED,
                    vf.createLiteral("2026-08-01T10:00:00.000+02:00", XSD.DATETIME), graph);
            connection.add(entry, DCTERMS.MODIFIED,
                    vf.createLiteral("2026-08-02T10:00:00.000+02:00", XSD.DATETIME), graph);
            connection.add(entry, iri(LEXO + "status"), vf.createLiteral("working"), graph);
            connection.add(entry, iri("https://example.org/source"),
                    vf.createLiteral("preserved metadata"), graph);
        }
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void updatesCorePropertiesOnlyInTheLanguageGraph() throws Exception {
        LexicalEntryUpdateRequest request = baseRequest();
        request.setExpectedModified("2026-08-02T10:00:00.000+02:00");
        request.setLabel("casa editrice");
        request.setType("ontolex:MultiWordExpression");
        request.setPos("lexinfo:adjective");

        LexicalEntryUpdateResult result = manager.update(request, "editor");

        assertThat(result.entry).isEqualTo(entry.stringValue());
        assertThat(result.language).isEqualTo("it");
        assertThat(result.author).isEqualTo("editor");
        assertThat(result.label).isEqualTo("casa editrice");
        assertThat(result.type).isEqualTo(multiword.stringValue());
        assertThat(result.pos).isEqualTo(adjective.stringValue());
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("metadata");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(entry, RDFS.LABEL,
                    vf.createLiteral("casa editrice", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, RDFS.LABEL,
                    vf.createLiteral("casa", "it"), false, graph)).isFalse();
            assertThat(connection.hasStatement(entry, RDF.TYPE, multiword,
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(entry,
                    iri(LEXINFO + "partOfSpeech"), adjective,
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, DCTERMS.CREATOR,
                    vf.createLiteral("creator"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, DCTERMS.CREATED,
                    null, false, graph)).isTrue();
            assertThat(connection.hasStatement(entry,
                    iri("https://example.org/source"),
                    vf.createLiteral("preserved metadata"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, iri(LEXO + "status"),
                    vf.createLiteral("working"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, DCTERMS.MODIFIED,
                    vf.createLiteral(result.modified, XSD.DATETIME),
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, null, null, false,
                    iri(LexiconCrudSupport.lexicalGraphUri("en")))).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void explicitNullRemovesPosWhileOmittedFieldsRemainUnchanged() throws Exception {
        String json = "{\"entry\":\"" + entry.stringValue()
                + "\",\"language\":\"IT\",\"pos\":null}";
        LexicalEntryUpdateRequest request = new ObjectMapper().readValue(
                json, LexicalEntryUpdateRequest.class);

        assertThat(request.hasPos()).isTrue();
        LexicalEntryUpdateResult result = manager.update(request, " ");

        assertThat(result.author).isEqualTo("anonymous");
        assertThat(result.label).isEqualTo("casa");
        assertThat(result.type).isEqualTo(word.stringValue());
        assertThat(result.pos).isNull();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(entry,
                    iri(LEXINFO + "partOfSpeech"), null, false, graph)).isFalse();
            assertThat(connection.hasStatement(entry, RDFS.LABEL,
                    vf.createLiteral("casa", "it"), false, graph)).isTrue();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void staleExpectedModifiedRollsBackTheWholeUpdate() {
        LexicalEntryUpdateRequest request = baseRequest();
        request.setLabel("changed");
        request.setExpectedModified("2020-01-01T00:00:00.000Z");

        assertThatThrownBy(() -> manager.update(request, "editor"))
                .isInstanceOf(LexicalEntryUpdateManager.EntryUpdateException.class)
                .hasMessageStartingWith("MODIFIED_MISMATCH:");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(entry, RDFS.LABEL,
                    vf.createLiteral("casa", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, RDFS.LABEL,
                    vf.createLiteral("changed", "it"), false, graph)).isFalse();
        }
    }

    @Test
    void rejectsInvalidTargetsAndChangesWithoutSideEffects() {
        LexicalEntryUpdateRequest missing = baseRequest();
        missing.setEntry("https://example.org/entry/missing");
        missing.setLabel("changed");
        assertThatThrownBy(() -> manager.update(missing, "editor"))
                .hasMessageStartingWith("ENTRY_NOT_FOUND:");

        LexicalEntryUpdateRequest invalidType = baseRequest();
        invalidType.setType("https://example.org/NotAnEntry");
        assertThatThrownBy(() -> manager.update(invalidType, "editor"))
                .hasMessageStartingWith("INVALID_ENTRY_TYPE:");

        LexicalEntryUpdateRequest invalidPos = baseRequest();
        invalidPos.setPos("https://example.org/not-a-pos");
        assertThatThrownBy(() -> manager.update(invalidPos, "editor"))
                .hasMessageStartingWith("INVALID_PART_OF_SPEECH:");

        assertThatThrownBy(() -> manager.update(baseRequest(), "editor"))
                .hasMessageStartingWith("MISSING_ENTRY_CHANGES:");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(entry, RDFS.LABEL,
                    vf.createLiteral("casa", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, RDF.TYPE, word,
                    false, graph)).isTrue();
            assertDefaultGraphEmpty(connection);
        }
    }

    private LexicalEntryUpdateRequest baseRequest() {
        LexicalEntryUpdateRequest request = new LexicalEntryUpdateRequest();
        request.setEntry(entry.stringValue());
        request.setLanguage("it");
        return request;
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
