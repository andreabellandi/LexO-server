package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptLabel;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptUpdateRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptUpdateResult;
import java.util.Arrays;
import java.util.Collections;
import org.eclipse.rdf4j.model.IRI;
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

class LexicalConceptUpdateManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexicalConceptUpdateManager manager;
    private IRI graph;
    private IRI concept;
    private IRI oldSense;
    private IRI newSense;
    private IRI oldParent;
    private IRI newParent;
    private IRI conceptSet;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new LexicalConceptUpdateManager(repository);
        graph = iri(LexiconCrudSupport.lexicalConceptGraphUri());
        concept = iri("https://example.org/concept/1");
        oldSense = iri("https://example.org/sense/old");
        newSense = iri("https://example.org/sense/new");
        oldParent = iri("https://example.org/concept/old-parent");
        newParent = iri("https://example.org/concept/new-parent");
        conceptSet = iri("https://example.org/concept-set/1");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(concept, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), graph);
            connection.add(concept, iri(SKOS + "prefLabel"),
                    vf.createLiteral("casa", "it"), graph);
            connection.add(concept, iri(SKOS + "alternativeLabel"),
                    vf.createLiteral("dimora", "it"), graph);
            connection.add(concept, iri(SKOS + "definition"),
                    vf.createLiteral("edificio", "it"), graph);
            connection.add(concept, iri(ONTOLEX + "isLexicalizedSenseOf"),
                    oldSense, graph);
            connection.add(concept, iri(SKOS + "broader"), oldParent, graph);
            connection.add(concept, iri(SKOS + "inScheme"), conceptSet, graph);
            connection.add(concept, DCTERMS.CREATOR,
                    vf.createLiteral("creator"), graph);
            connection.add(concept, DCTERMS.CREATED,
                    vf.createLiteral("2026-08-01T10:00:00.000+02:00", XSD.DATETIME), graph);
            connection.add(concept, DCTERMS.MODIFIED,
                    vf.createLiteral("2026-08-02T10:00:00.000+02:00", XSD.DATETIME), graph);
            connection.add(concept, iri("https://example.org/source"),
                    vf.createLiteral("preserved metadata"), graph);
            connection.add(oldSense, RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), graph);
            connection.add(newSense, RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), graph);
            connection.add(oldParent, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), graph);
            connection.add(newParent, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), graph);
            connection.add(conceptSet, RDF.TYPE,
                    iri(ONTOLEX + "ConceptSet"), graph);
        }
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void replacesSuppliedCollectionsAndPreservesMetadataInTheFixedGraph()
            throws Exception {
        LexicalConceptUpdateRequest request = baseRequest();
        request.setExpectedModified("2026-08-02T10:00:00.000+02:00");
        request.setLabel(Arrays.asList(
                new LexicalConceptLabel("house", "EN"),
                new LexicalConceptLabel("abitazione", "it")));
        request.setAlternativeLabel(Collections.<LexicalConceptLabel>emptyList());
        request.setHiddenLabel(Arrays.asList(
                new LexicalConceptLabel("domicilio", "it")));
        request.setSenseId(Arrays.asList(newSense.stringValue()));
        request.setParent(newParent.stringValue());

        LexicalConceptUpdateResult result = manager.update(request, "editor");

        assertThat(result.author).isEqualTo("editor");
        assertThat(result.label).hasSize(2);
        assertThat(result.alternativeLabel).isEmpty();
        assertThat(result.hiddenLabel).hasSize(1);
        assertThat(result.definition).hasSize(1);
        assertThat(result.senseId).containsExactly(newSense.stringValue());
        assertThat(result.parent).isEqualTo(newParent.stringValue());
        assertThat(result.conceptSetId).isEqualTo(conceptSet.stringValue());
        assertThat(new ObjectMapper().writeValueAsString(result))
                .doesNotContain("metadata");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(concept, iri(SKOS + "prefLabel"),
                    vf.createLiteral("house", "en"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri(SKOS + "alternativeLabel"), null, false, graph)).isFalse();
            assertThat(connection.hasStatement(concept, iri(SKOS + "definition"),
                    vf.createLiteral("edificio", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri(ONTOLEX + "isLexicalizedSenseOf"), oldSense,
                    false, graph)).isFalse();
            assertThat(connection.hasStatement(concept,
                    iri(ONTOLEX + "isLexicalizedSenseOf"), newSense,
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, DCTERMS.CREATOR,
                    vf.createLiteral("creator"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri("https://example.org/source"),
                    vf.createLiteral("preserved metadata"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, DCTERMS.MODIFIED,
                    vf.createLiteral(result.modified, XSD.DATETIME),
                    false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, null, null, false,
                    iri(LexiconCrudSupport.lexicalGraphUri("it")))).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void explicitNullRemovesScalarLinksAndOmittedFieldsRemainUnchanged()
            throws Exception {
        String json = "{\"lexicalConcept\":\"" + concept.stringValue()
                + "\",\"parent\":null,\"conceptSetId\":null}";
        LexicalConceptUpdateRequest request = new ObjectMapper().readValue(
                json, LexicalConceptUpdateRequest.class);

        assertThat(request.hasParent()).isTrue();
        assertThat(request.hasConceptSetId()).isTrue();
        LexicalConceptUpdateResult result = manager.update(request, " ");

        assertThat(result.author).isEqualTo("anonymous");
        assertThat(result.parent).isNull();
        assertThat(result.conceptSetId).isNull();
        assertThat(result.label).hasSize(1);
        assertThat(result.senseId).containsExactly(oldSense.stringValue());
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(concept, iri(SKOS + "broader"),
                    null, false, graph)).isFalse();
            assertThat(connection.hasStatement(concept, iri(SKOS + "inScheme"),
                    null, false, graph)).isFalse();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void rejectsStaleOrInvalidRequestsAndRollsBack() {
        LexicalConceptUpdateRequest stale = baseRequest();
        stale.setDefinition(Collections.<LexicalConceptLabel>emptyList());
        stale.setExpectedModified("2020-01-01T00:00:00.000Z");
        assertThatThrownBy(() -> manager.update(stale, "editor"))
                .isInstanceOf(LexicalConceptUpdateManager.ConceptUpdateException.class)
                .hasMessageStartingWith("MODIFIED_MISMATCH:");

        LexicalConceptUpdateRequest missingLink = baseRequest();
        missingLink.setSenseId(Arrays.asList("https://example.org/sense/missing"));
        assertThatThrownBy(() -> manager.update(missingLink, "editor"))
                .hasMessageStartingWith("SENSE_NOT_FOUND:");

        LexicalConceptUpdateRequest missingLabels = baseRequest();
        missingLabels.setLabel(Collections.<LexicalConceptLabel>emptyList());
        assertThatThrownBy(() -> manager.update(missingLabels, "editor"))
                .hasMessageStartingWith("MISSING_LABEL:");

        assertThatThrownBy(() -> manager.update(baseRequest(), "editor"))
                .hasMessageStartingWith("MISSING_LEXICAL_CONCEPT_CHANGES:");
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(concept, iri(SKOS + "definition"),
                    vf.createLiteral("edificio", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept,
                    iri(ONTOLEX + "isLexicalizedSenseOf"), oldSense,
                    false, graph)).isTrue();
            assertDefaultGraphEmpty(connection);
        }
    }

    private LexicalConceptUpdateRequest baseRequest() {
        LexicalConceptUpdateRequest request = new LexicalConceptUpdateRequest();
        request.setLexicalConcept(concept.stringValue());
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
