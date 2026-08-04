package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryListItem;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexicalEntryListManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LIME = "http://www.w3.org/ns/lemon/lime#";
    private static final String LEXINFO =
            "http://www.lexinfo.net/ontology/3.0/lexinfo#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String EX = "https://example.org/";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexicalEntryListManager manager;
    private IRI italianGraph;
    private IRI schemaGraph;
    private IRI word;
    private IRI affix;
    private IRI noun;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new LexicalEntryListManager(repository);
        italianGraph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        schemaGraph = iri(LexicalNamedGraphs.schemaGraphUri());
        word = iri(ONTOLEX + "Word");
        affix = iri(ONTOLEX + "Affix");
        noun = iri(LEXINFO + "noun");
        seedSchema();
        seedItalianLexicon();
        seedOtherLanguage();
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void listsEveryEntryInOnlyTheSelectedLanguageGraph() {
        List<LexicalEntryListItem> entries = manager.list(
                "IT", null, null, null, null, null, null, null, null);

        assertThat(entries).extracting(item -> item.entry)
                .containsExactlyInAnyOrder(EX + "entry/direct", EX + "entry/canonical",
                        EX + "entry/other", EX + "entry/canonical-without-rep",
                        EX + "entry/no-label");
        LexicalEntryListItem direct = entry(entries, EX + "entry/direct");
        assertThat(direct.label).isEqualTo("Casa");
        assertThat(direct.type).isEqualTo(word.stringValue());
        assertThat(direct.pos).isEqualTo(noun.stringValue());
        assertThat(direct.author).isEqualTo("alice");
        assertThat(direct.status).isEqualTo("working");
        assertThat(direct.senseNumber).isEqualTo(2);
        assertThat(direct.senses).containsExactly(
                EX + "sense/direct/0", EX + "sense/direct/1");
        assertThat(direct.canonicalFormNumber).isEqualTo(2);
        assertThat(direct.canonicalForm).isEqualTo(EX + "form/direct-canonical");
        assertThat(direct.otherFormNumber).isEqualTo(2);
        assertThat(direct.otherForms).containsExactly(
                EX + "form/direct-other", EX + "form/z-direct-other");
        RdfMetadataProperty source = metadataProperty(direct,
                EX + "vocabulary/source");
        assertThat(source.values).extracting(value -> value.type)
                .containsExactly("iri", "literal");
        assertThat(source.values).extracting(value -> value.value)
                .containsExactly(EX + "source/1", "fonte primaria");
        assertThat(source.values.get(1).language).isEqualTo("it");
        assertThat(direct.metadata).extracting(property -> property.property)
                .noneMatch(property -> property.startsWith(ONTOLEX))
                .contains(SKOS + "note")
                .doesNotContain(SKOS + "definition")
                .doesNotContain(DCTERMS.CREATOR.stringValue());
        assertThat(entry(entries, EX + "entry/no-label").label).isNull();
        assertThat(entry(entries, EX + "entry/no-label").senses).isEmpty();
        assertThat(entry(entries, EX + "entry/no-label").canonicalFormNumber)
                .isZero();
        assertThat(entry(entries, EX + "entry/no-label").canonicalForm).isNull();
        assertThat(entry(entries, EX + "entry/no-label").otherForms).isEmpty();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void appliesTheExclusiveLabelFallbackAndKeyModes() {
        assertThat(entries("cas", null, null)).extracting(item -> item.entry)
                .containsExactly(EX + "entry/canonical");
        assertThat(entries("cas", null, "insensitive"))
                .extracting(item -> item.entry)
                .containsExactlyInAnyOrder(EX + "entry/direct",
                        EX + "entry/canonical");
        assertThat(entries("CAS", "contains", "insensitive"))
                .extracting(item -> item.entry)
                .containsExactlyInAnyOrder(EX + "entry/direct",
                        EX + "entry/canonical", EX + "entry/other");
        assertThat(entries("ARE", "endsWith", "insensitive"))
                .extracting(item -> item.entry)
                .containsExactly(EX + "entry/other");

        assertThat(entries("canonical ignored", "contains", "sensitive")).isEmpty();
        assertThat(entries("invisibile", "contains", "sensitive")).isEmpty();
    }

    @Test
    void combinesEveryPopulatedFilterWithAnd() {
        List<LexicalEntryListItem> entries = manager.list(
                "it", "cas", "contains", "insensitive", word.stringValue(),
                noun.stringValue(), "alice", "working", "2");

        assertThat(entries).extracting(item -> item.entry)
                .containsExactly(EX + "entry/direct");
        assertThat(manager.list("it", null, null, null, affix.stringValue(),
                null, null, null, null)).extracting(item -> item.entry)
                .containsExactly(EX + "entry/other");
        assertThat(manager.list("it", " ", " ", " ", " ", " ", " ",
                " ", " ")).hasSize(5);
    }

    @Test
    void filtersByAnExactNonNegativeSenseNumber() {
        assertThat(manager.list("it", null, null, null, null, null, null,
                null, "0")).extracting(item -> item.entry)
                .containsExactlyInAnyOrder(EX + "entry/canonical",
                        EX + "entry/canonical-without-rep", EX + "entry/no-label");
    }

    @Test
    void validatesEveryFilterBeforeQuerying() {
        assertThatThrownBy(() -> manager.list("zz", null, null, null, null,
                null, null, null, null))
                .hasMessageStartingWith("INVALID_LANGUAGE:");
        assertThatThrownBy(() -> manager.list("it", null, "equals", null,
                null, null, null, null, null))
                .hasMessageStartingWith("INVALID_SEARCH_MODE:");
        assertThatThrownBy(() -> manager.list("it", null, null, "folded",
                null, null, null, null, null))
                .hasMessageStartingWith("INVALID_CASE:");
        assertThatThrownBy(() -> manager.list("it", null, null, null,
                "not an iri", null, null, null, null))
                .hasMessageStartingWith("INVALID_TYPE_IRI:");
        assertThatThrownBy(() -> manager.list("it", null, null, null,
                null, null, null, "draft", null))
                .hasMessageStartingWith("INVALID_STATUS:");
        assertThatThrownBy(() -> manager.list("it", null, null, null,
                null, null, null, null, "-1"))
                .hasMessageStartingWith("INVALID_SENSE_NUMBER:");
        assertThatThrownBy(() -> manager.list("it", null, null, null,
                null, null, null, null, "one"))
                .hasMessageStartingWith("INVALID_SENSE_NUMBER:");
    }

    @Test
    void reportsMissingTypesAndInvalidPartOfSpeechIndividuals() {
        assertThatThrownBy(() -> manager.list("it", null, null, null,
                EX + "MissingType", null, null, null, null))
                .isInstanceOf(LexicalEntryListManager.EntryListException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404)
                .hasMessageStartingWith("TYPE_NOT_FOUND:");
        assertThatThrownBy(() -> manager.list("it", null, null, null,
                null, EX + "not-a-pos", null, null, null))
                .isInstanceOf(LexicalEntryListManager.EntryListException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422)
                .hasMessageStartingWith("INVALID_PART_OF_SPEECH:");
    }

    private List<LexicalEntryListItem> entries(
            String key, String searchMode, String matchCase) {
        return manager.list("it", key, searchMode, matchCase, null, null,
                null, null, null);
    }

    private LexicalEntryListItem entry(List<LexicalEntryListItem> entries,
                                       String iri) {
        return entries.stream().filter(item -> iri.equals(item.entry))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private void seedSchema() {
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(word, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
            connection.add(affix, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
            connection.add(noun, RDF.TYPE,
                    iri(LEXINFO + "PartOfSpeech"), schemaGraph);
            connection.add(iri(EX + "not-a-pos"), RDF.TYPE,
                    RDFS.RESOURCE, schemaGraph);
        }
    }

    private void seedItalianLexicon() {
        try (RepositoryConnection connection = repository.getConnection()) {
            IRI lexicon = iri(EX + "lexicon/it");
            connection.add(lexicon, RDF.TYPE, iri(LIME + "Lexicon"), italianGraph);
            connection.add(lexicon, iri(LIME + "language"),
                    vf.createLiteral("it"), italianGraph);
            addEntry(connection, lexicon, "direct", word, "Casa", "alice",
                    "working", 2);
            IRI direct = iri(EX + "entry/direct");
            addForm(connection, direct, "direct-canonical", "canonical ignored",
                    "canonicalForm");
            addForm(connection, direct, "z-direct-canonical", "second canonical",
                    "canonicalForm");
            addForm(connection, direct, "direct-other", "other ignored",
                    "otherForm");
            addForm(connection, direct, "z-direct-other", "second other",
                    "otherForm");
            IRI source = iri(EX + "vocabulary/source");
            connection.add(direct, source, iri(EX + "source/1"), italianGraph);
            connection.add(direct, source,
                    vf.createLiteral("fonte primaria", "it"), italianGraph);
            connection.add(direct,
                    iri(SKOS + "note"),
                    vf.createLiteral("nota lessicale"), italianGraph);

            addEntry(connection, lexicon, "canonical", word, null, "bob",
                    "completed", 0);
            addForm(connection, iri(EX + "entry/canonical"), "canonical-form",
                    "casale", "canonicalForm");

            addEntry(connection, lexicon, "other", affix, null, "alice",
                    "revised", 1);
            addForm(connection, iri(EX + "entry/other"), "other-form",
                    "INCASARE", "otherForm");

            addEntry(connection, lexicon, "canonical-without-rep", word, null,
                    null, null, 0);
            IRI blocked = iri(EX + "entry/canonical-without-rep");
            connection.add(blocked, iri(ONTOLEX + "canonicalForm"),
                    iri(EX + "form/empty-canonical"), italianGraph);
            addForm(connection, blocked, "blocked-other", "invisibile", "otherForm");

            addEntry(connection, lexicon, "no-label", word, null, null, null, 0);

            IRI foreignLexicon = iri(EX + "lexicon/wrong-language");
            IRI foreignEntry = iri(EX + "entry/wrong-language");
            connection.add(foreignLexicon, RDF.TYPE, iri(LIME + "Lexicon"),
                    italianGraph);
            connection.add(foreignLexicon, iri(LIME + "language"),
                    vf.createLiteral("en"), italianGraph);
            connection.add(foreignLexicon, iri(LIME + "entry"), foreignEntry,
                    italianGraph);
            connection.add(foreignEntry, RDF.TYPE, word, italianGraph);
            connection.add(foreignEntry, RDFS.LABEL,
                    vf.createLiteral("wrong", "en"), italianGraph);
        }
    }

    private void seedOtherLanguage() {
        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("en"));
        try (RepositoryConnection connection = repository.getConnection()) {
            IRI lexicon = iri(EX + "lexicon/en");
            IRI entry = iri(EX + "entry/english");
            connection.add(lexicon, RDF.TYPE, iri(LIME + "Lexicon"), graph);
            connection.add(lexicon, iri(LIME + "entry"), entry, graph);
            connection.add(entry, RDF.TYPE, word, graph);
            connection.add(entry, RDFS.LABEL, vf.createLiteral("case", "en"), graph);
        }
    }

    private void addEntry(RepositoryConnection connection, IRI lexicon,
                          String localName, IRI type, String label, String author,
                          String status, int senses) {
        IRI entry = iri(EX + "entry/" + localName);
        connection.add(lexicon, iri(LIME + "entry"), entry, italianGraph);
        connection.add(entry, RDF.TYPE, type, italianGraph);
        connection.add(entry, iri(LEXINFO + "partOfSpeech"), noun, italianGraph);
        if (label != null) {
            connection.add(entry, RDFS.LABEL, vf.createLiteral(label, "it"),
                    italianGraph);
        }
        if (author != null) {
            connection.add(entry, DCTERMS.CREATOR, vf.createLiteral(author),
                    italianGraph);
        }
        if (status != null) {
            connection.add(entry, iri(LEXO + "status"), vf.createLiteral(status),
                    italianGraph);
        }
        for (int index = 0; index < senses; index++) {
            connection.add(entry, iri(ONTOLEX + "sense"),
                    iri(EX + "sense/" + localName + "/" + index), italianGraph);
        }
    }

    private void addForm(RepositoryConnection connection, IRI entry,
                         String formName, String writtenRep, String relation) {
        IRI form = iri(EX + "form/" + formName);
        connection.add(entry, iri(ONTOLEX + relation), form, italianGraph);
        connection.add(form, iri(ONTOLEX + "writtenRep"),
                vf.createLiteral(writtenRep, "it"), italianGraph);
    }

    private void assertDefaultGraphEmpty(RepositoryConnection connection) {
        try (RepositoryResult<org.eclipse.rdf4j.model.Statement> statements =
                     connection.getStatements(null, null, null, false,
                             (Resource) null)) {
            assertThat(statements.hasNext()).isFalse();
        }
    }

    private RdfMetadataProperty metadataProperty(LexicalEntryListItem item,
                                                 String property) {
        return item.metadata.stream()
                .filter(candidate -> property.equals(candidate.property))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }
}
