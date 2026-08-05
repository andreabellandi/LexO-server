package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptLabel;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptSenseLink;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptCreationResult;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import java.util.Arrays;
import java.util.Collections;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexicalConceptManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexicalConceptManager manager;
    private IRI graph;
    private IRI italianGraph;
    private IRI sense;
    private IRI parent;
    private IRI conceptSet;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new LexicalConceptManager(repository);
        graph = iri(LexiconCrudSupport.lexicalConceptGraphUri());
        italianGraph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        sense = iri("https://example.org/sense/1");
        parent = iri("https://example.org/concept/parent");
        conceptSet = iri("https://example.org/concept-set/1");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(sense, RDF.TYPE, iri(ONTOLEX + "LexicalSense"),
                    italianGraph);
            connection.add(parent, RDF.TYPE, iri(ONTOLEX + "LexicalConcept"), graph);
            connection.add(conceptSet, RDF.TYPE, iri(ONTOLEX + "ConceptSet"), graph);
        }
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void createsAllRequestedTriplesOnlyInTheFixedGraph() {
        LexicalConceptCreationRequest request = request();
        request.alternativeLabel = Arrays.asList(
                new LexicalConceptLabel("dimora", "it"));
        request.hiddenLabel = Arrays.asList(
                new LexicalConceptLabel("house", "en"));
        request.definition = Arrays.asList(
                new LexicalConceptLabel("Edificio destinato ad abitazione", "it"));
        request.senses = Arrays.asList(
                new LexicalConceptSenseLink(sense.stringValue(), "IT"));
        request.parent = parent.stringValue();
        request.conceptSetId = conceptSet.stringValue();
        RdfMetadataProperty metadata = new RdfMetadataProperty();
        metadata.property = "https://example.org/vocabulary/source";
        metadata.values = Arrays.asList(
                new RdfMetadataValue("https://example.org/source/1", "iri", null, null),
                new RdfMetadataValue("fonte", "literal", "it", null));
        request.metadata = Arrays.asList(metadata);

        LexicalConceptCreationResult result = manager.create(request, "editor");

        IRI concept = iri(result.lexicalConcept);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(concept, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, DCTERMS.CREATOR,
                    vf.createLiteral("editor"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, DCTERMS.CREATED,
                    null, false, graph)).isTrue();
            assertThat(created(connection, concept).getDatatype())
                    .isEqualTo(XSD.DATETIME);
            assertThat(connection.hasStatement(concept, iri(SKOS + "prefLabel"),
                    vf.createLiteral("casa", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri(SKOS + "alternativeLabel"),
                    vf.createLiteral("dimora", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, iri(SKOS + "hiddenLabel"),
                    vf.createLiteral("house", "en"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, iri(SKOS + "definition"),
                    vf.createLiteral("Edificio destinato ad abitazione", "it"),
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri(ONTOLEX + "lexicalizedSense"), sense,
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri(ONTOLEX + "isLexicalizedSenseOf"), sense,
                    false, graph)).isFalse();
            assertThat(connection.hasStatement(sense, RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), false, italianGraph)).isTrue();
            assertThat(connection.hasStatement(sense, RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), false, graph)).isFalse();
            assertThat(connection.hasStatement(concept, iri(SKOS + "broader"),
                    parent, false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, iri(SKOS + "inScheme"),
                    conceptSet, false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri("https://example.org/vocabulary/source"),
                    iri("https://example.org/source/1"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, null, null, false,
                    iri(LexiconCrudSupport.lexicalGraphUri("it")))).isFalse();
            assertDefaultGraphEmpty(connection);
        }
        assertThat(result.metadata).hasSize(1);
        assertThat(result.metadata.get(0).values).hasSize(2);
    }

    @Test
    void defaultsBlankAuthorAndSupportsOnlyRequiredLabels() {
        LexicalConceptCreationResult result = manager.create(request(), "  ");

        assertThat(result.author).isEqualTo("anonymous");
        assertThat(result.senseId).isEmpty();
        assertThat(result.parent).isNull();
        assertThat(result.conceptSetId).isNull();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(iri(result.lexicalConcept),
                    DCTERMS.CREATOR, vf.createLiteral("anonymous"),
                    false, graph)).isTrue();
        }
    }

    @Test
    void acceptsAnExplicitlyEmptyMetadataList() {
        LexicalConceptCreationRequest request = request();
        request.metadata = Collections.emptyList();

        LexicalConceptCreationResult result = manager.create(request, "editor");

        assertThat(result.metadata).isEmpty();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(iri(result.lexicalConcept),
                    RDF.TYPE, iri(ONTOLEX + "LexicalConcept"), false, graph))
                    .isTrue();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void rejectsMetadataFromAnyProtectedNamespaceBeforeWriting() {
        LexicalConceptCreationRequest request = request();
        RdfMetadataProperty metadata = new RdfMetadataProperty();
        metadata.property = "http://www.w3.org/ns/lemon/vartrans#futureProperty";
        metadata.values = Arrays.asList(
                new RdfMetadataValue("forbidden", "literal", null, null));
        request.metadata = Arrays.asList(metadata);

        assertThatThrownBy(() -> manager.create(request, "editor"))
                .hasMessageStartingWith("RESERVED_METADATA_PROPERTY:");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(null,
                    iri("http://www.w3.org/ns/lemon/vartrans#futureProperty"),
                    null, false, graph)).isFalse();
        }
    }

    @Test
    void validatesRequestShapeBeforeOpeningAWriteTransaction() {
        assertThatThrownBy(() -> manager.create(null, "editor"))
                .hasMessageStartingWith("MISSING_LEXICAL_CONCEPT:");
        LexicalConceptCreationRequest missing = new LexicalConceptCreationRequest();
        assertThatThrownBy(() -> manager.create(missing, "editor"))
                .hasMessageStartingWith("MISSING_LABEL:");
        LexicalConceptCreationRequest blank = request();
        blank.label.get(0).label = " ";
        assertThatThrownBy(() -> manager.create(blank, "editor"))
                .hasMessageStartingWith("MISSING_LABEL_VALUE:");
        LexicalConceptCreationRequest invalidLanguage = request();
        invalidLanguage.label.get(0).language = "zz";
        assertThatThrownBy(() -> manager.create(invalidLanguage, "editor"))
                .hasMessageStartingWith("INVALID_LABEL_LANGUAGE:");
        LexicalConceptCreationRequest invalidIri = request();
        invalidIri.parent = "not an IRI";
        assertThatThrownBy(() -> manager.create(invalidIri, "editor"))
                .hasMessageStartingWith("INVALID_PARENT_IRI:");

        LexicalConceptCreationRequest legacySenses = request();
        legacySenses.senseId = Arrays.asList(sense.stringValue());
        assertThatThrownBy(() -> manager.create(legacySenses, "editor"))
                .hasMessageStartingWith("SENSE_LANGUAGE_REQUIRED:");

        LexicalConceptCreationRequest invalidSenseLanguage = request();
        invalidSenseLanguage.senses = Arrays.asList(
                new LexicalConceptSenseLink(sense.stringValue(), "zz"));
        assertThatThrownBy(() -> manager.create(invalidSenseLanguage, "editor"))
                .hasMessageStartingWith("INVALID_SENSE_LANGUAGE:");
    }

    @Test
    void rejectsMissingAndMistypedLinksWithoutWritingTheConcept() {
        LexicalConceptCreationRequest missingSense = request();
        missingSense.senses = Arrays.asList(new LexicalConceptSenseLink(
                "https://example.org/sense/missing", "it"));
        assertThatThrownBy(() -> manager.create(missingSense, "editor"))
                .isInstanceOf(LexicalConceptManager.LexicalConceptCreationException.class)
                .hasMessageStartingWith("SENSE_NOT_FOUND:");

        LexicalConceptCreationRequest wrongSense = request();
        IRI wrongSenseType = iri("https://example.org/sense/wrong-type");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(wrongSenseType, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), italianGraph);
        }
        wrongSense.senses = Arrays.asList(new LexicalConceptSenseLink(
                wrongSenseType.stringValue(), "it"));
        assertThatThrownBy(() -> manager.create(wrongSense, "editor"))
                .hasMessageStartingWith("INVALID_SENSE_TYPE:");

        LexicalConceptCreationRequest wrongLanguageGraph = request();
        wrongLanguageGraph.senses = Arrays.asList(new LexicalConceptSenseLink(
                sense.stringValue(), "en"));
        assertThatThrownBy(() -> manager.create(wrongLanguageGraph, "editor"))
                .hasMessageStartingWith("SENSE_NOT_FOUND:");

        IRI fixedGraphSense = iri("https://example.org/sense/fixed-graph-only");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(fixedGraphSense, RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), graph);
        }
        LexicalConceptCreationRequest misplacedSense = request();
        misplacedSense.senses = Arrays.asList(new LexicalConceptSenseLink(
                fixedGraphSense.stringValue(), "it"));
        assertThatThrownBy(() -> manager.create(misplacedSense, "editor"))
                .hasMessageStartingWith("SENSE_NOT_FOUND:");

        LexicalConceptCreationRequest missingParent = request();
        missingParent.parent = "https://example.org/concept/missing";
        assertThatThrownBy(() -> manager.create(missingParent, "editor"))
                .hasMessageStartingWith("PARENT_NOT_FOUND:");

        IRI wrong = iri("https://example.org/wrong");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(wrong, RDF.TYPE, iri(ONTOLEX + "LexicalSense"), graph);
        }
        LexicalConceptCreationRequest wrongParent = request();
        wrongParent.parent = wrong.stringValue();
        assertThatThrownBy(() -> manager.create(wrongParent, "editor"))
                .isInstanceOf(LexicalConceptManager.LexicalConceptCreationException.class)
                .hasMessageStartingWith("INVALID_PARENT_TYPE:");

        LexicalConceptCreationRequest missingSet = request();
        missingSet.conceptSetId = "https://example.org/concept-set/missing";
        assertThatThrownBy(() -> manager.create(missingSet, "editor"))
                .hasMessageStartingWith("CONCEPT_SET_NOT_FOUND:");

        LexicalConceptCreationRequest wrongSet = request();
        wrongSet.conceptSetId = parent.stringValue();
        assertThatThrownBy(() -> manager.create(wrongSet, "editor"))
                .hasMessageStartingWith("INVALID_CONCEPT_SET_TYPE:");

        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(count(connection, null, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), graph)).isEqualTo(1);
            assertDefaultGraphEmpty(connection);
        }
    }

    private LexicalConceptCreationRequest request() {
        LexicalConceptCreationRequest request = new LexicalConceptCreationRequest();
        request.label = Arrays.asList(new LexicalConceptLabel("casa", "IT"));
        return request;
    }

    private Literal created(RepositoryConnection connection, IRI concept) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                concept, DCTERMS.CREATED, null, false, graph)) {
            return (Literal) statements.next().getObject();
        }
    }

    private int count(RepositoryConnection connection, Resource subject,
                      IRI predicate, IRI object, Resource targetGraph) {
        int count = 0;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, object, false, targetGraph)) {
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
