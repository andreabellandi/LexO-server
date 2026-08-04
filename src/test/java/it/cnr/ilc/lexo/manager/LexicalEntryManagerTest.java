package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalRdfProperty;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalRdfValue;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalSenseCreation;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalSenseProperty;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryCreationResult;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.Arrays;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
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

class LexicalEntryManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LIME = "http://www.w3.org/ns/lemon/lime#";
    private static final String LEXINFO =
            "http://www.lexinfo.net/ontology/3.0/lexinfo#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexicalEntryManager manager;
    private IRI schemaGraph;
    private IRI word;
    private IRI noun;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new LexicalEntryManager(repository);
        schemaGraph = iri(LexicalNamedGraphs.schemaGraphUri());
        word = iri(ONTOLEX + "Word");
        noun = iri(LEXINFO + "noun");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(word, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
            connection.add(noun, RDF.TYPE,
                    iri(LEXINFO + "PartOfSpeech"), schemaGraph);
        }
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void createsLexiconEntryCanonicalFormAndSenseInTheLanguageGraph() throws Exception {
        LexicalEntryCreationRequest request = request("IT");
        request.lemma = Boolean.TRUE;
        request.senses = Arrays.asList(senseWithDefinitionAndConfidence());

        LexicalEntryCreationResult result = manager.create(request, "editor");

        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI lexicon = iri(result.lexicon);
        IRI entry = iri(result.entry);
        IRI form = iri(result.canonicalForm);
        IRI sense = iri(result.senses.get(0));
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(result.lexiconCreated).isTrue();
            assertThat(result.language).isEqualTo("it");
            assertThat(result.status).isEqualTo("working");
            assertThat(connection.hasStatement(lexicon, RDF.TYPE,
                    iri(LIME + "Lexicon"), false, graph)).isTrue();
            assertThat(connection.hasStatement(lexicon, iri(LIME + "language"),
                    vf.createLiteral("it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(lexicon, iri(LIME + "entry"),
                    entry, false, graph)).isTrue();
            assertThat(connection.hasStatement(lexicon, iri(LEXO + "status"),
                    null, false, graph)).isFalse();

            assertThat(connection.hasStatement(entry, RDF.TYPE, word,
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, RDFS.LABEL,
                    vf.createLiteral("casa", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry,
                    iri(LEXINFO + "partOfSpeech"), noun, false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, DCTERMS.CREATOR,
                    vf.createLiteral("editor"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, DCTERMS.CREATED,
                    null, false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, DCTERMS.MODIFIED,
                    null, false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, iri(LEXO + "status"),
                    vf.createLiteral("working"), false, graph)).isTrue();
            assertThat(createdLiteral(connection, entry, graph).getDatatype())
                    .isEqualTo(XSD.DATETIME);

            assertThat(connection.hasStatement(entry,
                    iri(ONTOLEX + "canonicalForm"), form, false, graph)).isTrue();
            assertThat(connection.hasStatement(form, RDF.TYPE,
                    iri(ONTOLEX + "Form"), false, graph)).isTrue();
            assertThat(connection.hasStatement(form, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("casa", "it"), false, graph)).isTrue();

            assertThat(connection.hasStatement(entry, iri(ONTOLEX + "sense"),
                    sense, false, graph)).isTrue();
            assertThat(connection.hasStatement(sense,
                    iri(ONTOLEX + "isSenseOf"), entry, false, graph)).isTrue();
            assertThat(connection.hasStatement(sense, RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), false, graph)).isTrue();
            assertThat(connection.hasStatement(sense, iri(SKOS + "definition"),
                    vf.createLiteral("edificio adibito ad abitazione", "it"),
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(sense,
                    iri("https://example.org/vocabulary/confidence"),
                    vf.createLiteral("0.92", XSD.DECIMAL), false, graph)).isTrue();
            assertDefaultGraphEmpty(connection);
            assertThat(connection.hasStatement(null, null, null, false,
                    iri(LexiconCrudSupport.lexicalGraphUri("en")))).isFalse();
        }

        assertThat(new ObjectMapper().writeValueAsString(result))
                .contains("\"entry\":\"" + result.entry + "\"")
                .contains("\"lexiconCreated\":true")
                .contains("\"canonicalForm\":\"" + result.canonicalForm + "\"");
    }

    @Test
    void reusesLexiconWhoseDctLanguageMatchesAndDefaultsBlankAuthor() {
        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI existing = iri("https://example.org/lexicon/it");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(existing, RDF.TYPE, iri(LIME + "Lexicon"), graph);
            connection.add(existing, DCTERMS.LANGUAGE,
                    vf.createLiteral("IT"), graph);
        }

        LexicalEntryCreationResult result = manager.create(request("it"), "  ");

        assertThat(result.lexicon).isEqualTo(existing.stringValue());
        assertThat(result.lexiconCreated).isFalse();
        assertThat(result.canonicalForm).isNull();
        assertThat(result.senses).isEmpty();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(existing, iri(LIME + "entry"),
                    iri(result.entry), false, graph)).isTrue();
            assertThat(connection.hasStatement(iri(result.entry), DCTERMS.CREATOR,
                    vf.createLiteral("anonymous"), false, graph)).isTrue();
            assertThat(count(connection, null, RDF.TYPE,
                    iri(LIME + "Lexicon"), graph)).isEqualTo(1);
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void createsANewLexiconWhenExistingLexiconHasAnotherLanguage() {
        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI existing = iri("https://example.org/lexicon/en");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(existing, RDF.TYPE, iri(LIME + "Lexicon"), graph);
            connection.add(existing, iri(LIME + "language"),
                    vf.createLiteral("en"), graph);
        }

        LexicalEntryCreationResult result = manager.create(request("it"), "editor");

        assertThat(result.lexiconCreated).isTrue();
        assertThat(result.lexicon).isNotEqualTo(existing.stringValue());
    }

    @Test
    void validatesTheWholeRequestBeforeWritingAnything() {
        LexicalEntryCreationRequest invalidType = request("it");
        invalidType.type = "https://example.org/NotAnEntry";

        assertThatThrownBy(() -> manager.create(invalidType, "editor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_ENTRY_TYPE:");

        LexicalEntryCreationRequest invalidPos = request("it");
        invalidPos.pos = "https://example.org/not-a-pos";
        assertThatThrownBy(() -> manager.create(invalidPos, "editor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_PART_OF_SPEECH:");

        LexicalEntryCreationRequest invalidMetadata = request("it");
        LexicalSenseCreation sense = new LexicalSenseCreation();
        LexicalSenseProperty metadata = new LexicalSenseProperty();
        metadata.property = SKOS + "definition";
        metadata.values = Arrays.asList(
                new LexicalRdfValue("not metadata", "literal", "it", null));
        sense.metadata = Arrays.asList(metadata);
        invalidMetadata.senses = Arrays.asList(sense);
        assertThatThrownBy(() -> manager.create(invalidMetadata, "editor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("RESERVED_SENSE_METADATA_PROPERTY:");

        try (RepositoryConnection connection = repository.getConnection()) {
            IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
            assertThat(connection.hasStatement(null, null, null, false, graph)).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void rejectsMissingFieldsInvalidLanguageAndInvalidRdfValues() {
        assertThatThrownBy(() -> manager.create(null, "editor"))
                .hasMessageStartingWith("MISSING_ENTRY:");
        LexicalEntryCreationRequest missingLabel = request("it");
        missingLabel.label = " ";
        assertThatThrownBy(() -> manager.create(missingLabel, "editor"))
                .hasMessageStartingWith("MISSING_LABEL:");
        LexicalEntryCreationRequest invalidLanguage = request("zz");
        assertThatThrownBy(() -> manager.create(invalidLanguage, "editor"))
                .hasMessageStartingWith("INVALID_LANGUAGE:");

        LexicalEntryCreationRequest invalidValue = request("it");
        LexicalSenseCreation sense = new LexicalSenseCreation();
        LexicalSenseProperty property = new LexicalSenseProperty();
        property.property = SKOS + "definition";
        property.values = Arrays.asList(new LexicalRdfValue(
                "definition", "literal", "it", XSD.STRING.stringValue()));
        sense.properties = Arrays.asList(property);
        invalidValue.senses = Arrays.asList(sense);
        assertThatThrownBy(() -> manager.create(invalidValue, "editor"))
                .hasMessageStartingWith("INVALID_LITERAL_VALUE:");
    }

    @Test
    void readsMetadataAsAPropertyListAndPersistsMultipleValues() throws Exception {
        String metadataProperty = "https://example.org/vocabulary/source";
        String json = "{\"label\":\"casa\","
                + "\"type\":\"ontolex:LexicalEntry\",\"language\":\"it\","
                + "\"senses\":[{\"metadata\":[{\"property\":\""
                + metadataProperty + "\",\"values\":["
                + "{\"value\":\"fonte primaria\",\"type\":\"literal\",\"language\":\"it\"},"
                + "{\"value\":\"https://example.org/source/1\",\"type\":\"iri\"}]}]}]}";
        LexicalEntryCreationRequest request = new ObjectMapper().readValue(
                json, LexicalEntryCreationRequest.class);

        assertThat(request.senses.get(0).metadata).hasSize(1);
        assertThat(request.senses.get(0).metadata.get(0).values).hasSize(2);
        LexicalEntryCreationResult result = manager.create(request, "editor");

        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI sense = iri(result.senses.get(0));
        IRI property = iri(metadataProperty);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(sense, property,
                    vf.createLiteral("fonte primaria", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(sense, property,
                    iri("https://example.org/source/1"), false, graph)).isTrue();
            assertThat(count(connection, sense, property, null, graph)).isEqualTo(2);
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void readsEntryMetadataAsAPropertyListAndPersistsMultipleValues()
            throws Exception {
        String metadataProperty = "https://example.org/vocabulary/source";
        String json = "{\"label\":\"casa\","
                + "\"type\":\"ontolex:LexicalEntry\",\"language\":\"it\","
                + "\"metadata\":[{\"property\":\"" + metadataProperty
                + "\",\"values\":["
                + "{\"value\":\"fonte primaria\",\"type\":\"literal\",\"language\":\"it\"},"
                + "{\"value\":\"https://example.org/source/1\",\"type\":\"iri\"}]}]}";
        LexicalEntryCreationRequest request = new ObjectMapper().readValue(
                json, LexicalEntryCreationRequest.class);

        assertThat(request.metadata).hasSize(1);
        assertThat(request.metadata.get(0).values).hasSize(2);
        LexicalEntryCreationResult result = manager.create(request, "editor");

        assertThat(result.metadata).hasSize(1);
        assertThat(result.metadata.get(0).property).isEqualTo(metadataProperty);
        assertThat(result.metadata.get(0).values).hasSize(2);

        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI entry = iri(result.entry);
        IRI property = iri(metadataProperty);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(entry, property,
                    vf.createLiteral("fonte primaria", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(entry, property,
                    iri("https://example.org/source/1"), false, graph)).isTrue();
            assertThat(count(connection, entry, property, null, graph)).isEqualTo(2);
            assertThat(connection.hasStatement(entry, property, null, false,
                    iri(LexiconCrudSupport.lexicalGraphUri("en")))).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void rejectsEveryStructuralEntryMetadataPropertyBeforeWriting() {
        String[] reserved = {
            RDF.TYPE.stringValue(),
            RDF.VALUE.stringValue(),
            DCTERMS.CREATOR.stringValue(),
            DCTERMS.CREATED.stringValue(),
            DCTERMS.MODIFIED.stringValue(),
            ONTOLEX + "anyFutureProperty",
            "http://www.w3.org/ns/lemon/frac#anyFutureProperty",
            "http://www.w3.org/ns/lemon/lime#anyFutureProperty",
            "http://www.w3.org/ns/lemon/vartrans#anyFutureProperty",
            "http://www.w3.org/ns/lemon/synsem#anyFutureProperty",
            "http://www.w3.org/2004/02/skos/core#anyFutureProperty",
            "http://www.w3.org/ns/lemon/decomp#anyFutureProperty"
        };

        for (String predicate : reserved) {
            LexicalEntryCreationRequest invalid = request("it");
            LexicalRdfProperty metadata = new LexicalRdfProperty();
            metadata.property = predicate;
            metadata.values = Arrays.asList(new LexicalRdfValue(
                    "structural value", "literal", null, null));
            invalid.metadata = Arrays.asList(metadata);

            assertThatThrownBy(() -> manager.create(invalid, "editor"))
                    .as(predicate)
                    .hasMessageStartingWith("RESERVED_ENTRY_METADATA_PROPERTY:");
        }

        try (RepositoryConnection connection = repository.getConnection()) {
            IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
            assertThat(connection.hasStatement(null, null, null, false, graph)).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void rejectsMalformedEntryMetadata() {
        LexicalEntryCreationRequest nullItem = request("it");
        nullItem.metadata = Arrays.asList((LexicalRdfProperty) null);
        assertThatThrownBy(() -> manager.create(nullItem, "editor"))
                .hasMessageStartingWith("INVALID_ENTRY_METADATA:");

        LexicalEntryCreationRequest missingValues = request("it");
        LexicalRdfProperty metadata = new LexicalRdfProperty();
        metadata.property = "https://example.org/vocabulary/source";
        missingValues.metadata = Arrays.asList(metadata);
        assertThatThrownBy(() -> manager.create(missingValues, "editor"))
                .hasMessageStartingWith("MISSING_ENTRY_METADATA_VALUES:");
    }

    private LexicalEntryCreationRequest request(String language) {
        LexicalEntryCreationRequest request = new LexicalEntryCreationRequest();
        request.label = "casa";
        request.type = "ontolex:Word";
        request.pos = "lexinfo:noun";
        request.language = language;
        return request;
    }

    private LexicalSenseCreation senseWithDefinitionAndConfidence() {
        LexicalSenseProperty definition = new LexicalSenseProperty();
        definition.property = SKOS + "definition";
        definition.values = Arrays.asList(new LexicalRdfValue(
                "edificio adibito ad abitazione", "literal", "it", null));
        LexicalSenseCreation sense = new LexicalSenseCreation();
        sense.properties = Arrays.asList(definition);
        LexicalSenseProperty confidence = new LexicalSenseProperty();
        confidence.property = "https://example.org/vocabulary/confidence";
        confidence.values = Arrays.asList(new LexicalRdfValue(
                "0.92", "literal", null, XSD.DECIMAL.stringValue()));
        sense.metadata = Arrays.asList(confidence);
        return sense;
    }

    private int count(RepositoryConnection connection, Resource subject,
                      IRI predicate, org.eclipse.rdf4j.model.Value object,
                      Resource graph) {
        int count = 0;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, object, false, graph)) {
            while (statements.hasNext()) {
                statements.next();
                count++;
            }
        }
        return count;
    }

    private Literal createdLiteral(RepositoryConnection connection,
                                   Resource subject, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, DCTERMS.CREATED, null, false, graph)) {
            return (Literal) statements.next().getObject();
        }
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
