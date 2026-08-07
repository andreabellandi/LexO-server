package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptDetailsResult;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptLinkedResource;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptRelation;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexicalConceptDetailsManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private LexicalConceptDetailsManager manager;
    private IRI concept;
    private IRI conceptGraph;
    private IRI italianGraph;
    private IRI englishGraph;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new LexicalConceptDetailsManager(repository);
        concept = iri("https://example.org/concept/main");
        conceptGraph = iri(LexiconCrudSupport.lexicalConceptGraphUri());
        italianGraph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        englishGraph = iri(LexiconCrudSupport.lexicalGraphUri("en"));
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(concept, RDF.TYPE, iri(ONTOLEX + "LexicalConcept"),
                    conceptGraph);
        }
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void returnsCompleteMultilingualDataAndDistinguishedRelations() {
        IRI entryDirect = iri("https://example.org/entry/direct");
        IRI entryCanonical = iri("https://example.org/entry/canonical");
        IRI entryOther = iri("https://example.org/entry/other");
        IRI canonicalForm = iri("https://example.org/form/canonical");
        IRI otherForm = iri("https://example.org/form/other");
        IRI senseDefinition = iri("https://example.org/sense/definition");
        IRI senseLabel = iri("https://example.org/sense/label");
        IRI senseEntry = iri("https://example.org/sense/entry");
        IRI set = iri("https://example.org/set/1");
        IRI child = iri("https://example.org/concept/child");
        IRI transitiveChild = iri("https://example.org/concept/transitive-child");
        IRI parent = iri("https://example.org/concept/parent");
        IRI transitiveParent = iri("https://example.org/concept/transitive-parent");

        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(concept, RDFS.LABEL, vf.createLiteral("general"),
                    conceptGraph);
            connection.add(concept, iri(SKOS + "prefLabel"),
                    vf.createLiteral("casa", "it"), conceptGraph);
            connection.add(concept, iri(SKOS + "alternativeLabel"),
                    vf.createLiteral("house", "en"), conceptGraph);
            connection.add(concept, iri(SKOS + "altLabel"),
                    vf.createLiteral("home", "en"), conceptGraph);
            connection.add(concept, iri(SKOS + "hiddenLabel"),
                    vf.createLiteral("dimora", "it"), conceptGraph);
            connection.add(concept, iri(SKOS + "definition"),
                    vf.createLiteral("abitazione", "it"), conceptGraph);
            connection.add(concept, iri(SKOS + "definition"),
                    vf.createLiteral("dwelling", "en"), conceptGraph);
            connection.add(concept, iri(SKOS + "inScheme"), set, conceptGraph);

            lexicalEntry(connection, entryDirect, italianGraph);
            connection.add(entryDirect, RDFS.LABEL,
                    vf.createLiteral("casa", "it"), italianGraph);
            connection.add(concept, iri(ONTOLEX + "isEvokedBy"), entryDirect,
                    conceptGraph);

            lexicalEntry(connection, entryCanonical, englishGraph);
            connection.add(entryCanonical, iri(ONTOLEX + "canonicalForm"),
                    canonicalForm, englishGraph);
            connection.add(canonicalForm, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("house", "en"), englishGraph);
            connection.add(entryCanonical, iri(ONTOLEX + "evokes"), concept,
                    englishGraph);

            lexicalEntry(connection, entryOther, italianGraph);
            connection.add(entryOther, iri(ONTOLEX + "otherForm"), otherForm,
                    italianGraph);
            connection.add(otherForm, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("casetta", "it"), italianGraph);
            connection.add(entryOther, iri(ONTOLEX + "evokes"), concept,
                    italianGraph);

            lexicalSense(connection, senseDefinition, italianGraph);
            connection.add(senseDefinition, iri(SKOS + "definition"),
                    vf.createLiteral("edificio", "it"), italianGraph);
            connection.add(concept, iri(ONTOLEX + "lexicalizedSense"),
                    senseDefinition, conceptGraph);

            lexicalSense(connection, senseLabel, englishGraph);
            connection.add(senseLabel, RDFS.LABEL,
                    vf.createLiteral("home sense", "en"), englishGraph);
            connection.add(senseLabel,
                    iri(ONTOLEX + "isLexicalizedSenseOf"), concept,
                    englishGraph);

            lexicalSense(connection, senseEntry, italianGraph);
            connection.add(entryOther, iri(ONTOLEX + "sense"), senseEntry,
                    italianGraph);
            connection.add(senseEntry,
                    iri(ONTOLEX + "isLexicalizedSenseOf"), concept,
                    italianGraph);

            relatedConcept(connection, child, "figlio", "it");
            relatedConcept(connection, transitiveChild, "descendant", "en");
            relatedConcept(connection, parent, "padre", "it");
            relatedConcept(connection, transitiveParent, "ancestor", "en");
            connection.add(child, iri(SKOS + "broader"), concept, conceptGraph);
            connection.add(transitiveChild, iri(SKOS + "broaderTransitive"),
                    concept, conceptGraph);
            connection.add(concept, iri(SKOS + "broader"), parent, conceptGraph);
            connection.add(concept, iri(SKOS + "broaderTransitive"),
                    transitiveParent, conceptGraph);

            connection.add(concept, iri("https://example.org/source"),
                    iri("https://example.org/source/1"), conceptGraph);
            connection.add(concept, iri(SKOS + "note"),
                    vf.createLiteral("nota", "it"), conceptGraph);
            connection.add(concept, DCTERMS.CREATOR,
                    vf.createLiteral("editor"), conceptGraph);
        }

        LexicalConceptDetailsResult result = manager.get(concept.stringValue());

        assertThat(result.labels).hasSize(5);
        assertThat(result.definitions).extracting(value -> value.value)
                .containsExactly("dwelling", "abitazione");
        assertThat(result.lexicalEntries).extracting(item -> item.iri)
                .containsExactly(entryCanonical.stringValue(),
                        entryDirect.stringValue(), entryOther.stringValue());
        assertThat(labels(result.lexicalEntries, entryDirect)).containsExactly("casa");
        assertThat(labels(result.lexicalEntries, entryCanonical))
                .containsExactly("house");
        assertThat(labels(result.lexicalEntries, entryOther))
                .containsExactly("casetta");
        assertThat(result.lexicalSenses).extracting(item -> item.iri)
                .containsExactly(senseDefinition.stringValue(),
                        senseEntry.stringValue(), senseLabel.stringValue());
        assertThat(labels(result.lexicalSenses, senseDefinition))
                .containsExactly("edificio");
        assertThat(labels(result.lexicalSenses, senseLabel))
                .containsExactly("home sense");
        assertThat(labels(result.lexicalSenses, senseEntry))
                .containsExactly("casetta");
        assertThat(result.conceptSets).containsExactly(set.stringValue());
        assertRelation(result.children.direct, child);
        assertRelation(result.children.transitive, transitiveChild);
        assertRelation(result.parents.direct, parent);
        assertRelation(result.parents.transitive, transitiveParent);
        assertThat(result.metadata).extracting(property -> property.property)
                .containsExactly(RDFS.LABEL.stringValue(), SKOS + "note",
                        "https://example.org/source");
        assertThat(result.metadata).allSatisfy(property ->
                assertThat(property.property).isNotEqualTo(DCTERMS.CREATOR.stringValue()));
    }

    @Test
    void ignoresLegacyDefaultAndInvalidLanguageGraphs() {
        IRI accepted = iri("https://example.org/entry/accepted");
        IRI legacy = iri("https://example.org/entry/legacy");
        IRI defaultEntry = iri("https://example.org/entry/default");
        IRI invalidGraphEntry = iri("https://example.org/entry/invalid-graph");
        Resource legacyGraph = iri("https://lexo.ilc.cnr.it/graphs/lexical/lexica");
        Resource invalidGraph = iri(LexiconCrudSupport.LEXICAL_GRAPH_BASE_URI
                + "not-a-language");
        try (RepositoryConnection connection = repository.getConnection()) {
            lexicalEntry(connection, accepted, italianGraph);
            connection.add(accepted, iri(ONTOLEX + "evokes"), concept,
                    italianGraph);
            lexicalEntry(connection, legacy, legacyGraph);
            connection.add(legacy, iri(ONTOLEX + "evokes"), concept, legacyGraph);
            connection.add(defaultEntry, RDF.TYPE,
                    iri(ONTOLEX + "LexicalEntry"));
            connection.add(defaultEntry, iri(ONTOLEX + "evokes"), concept);
            lexicalEntry(connection, invalidGraphEntry, invalidGraph);
            connection.add(invalidGraphEntry, iri(ONTOLEX + "evokes"), concept,
                    invalidGraph);
        }

        LexicalConceptDetailsResult result = manager.get(concept.stringValue());

        assertThat(result.lexicalEntries).extracting(item -> item.iri)
                .containsExactly(accepted.stringValue());
    }

    @Test
    void rejectsMissingWrongGraphAndWrongTypeConcepts() {
        assertThatThrownBy(() -> manager.get("not an iri"))
                .hasMessageStartingWith("INVALID_LEXICAL_CONCEPT_IRI:");
        assertThatThrownBy(() -> manager.get("https://example.org/missing"))
                .isInstanceOf(LexicalConceptDetailsManager.DetailsException.class)
                .hasMessageStartingWith("LEXICAL_CONCEPT_NOT_FOUND:");

        IRI wrongType = iri("https://example.org/concept/wrong-type");
        IRI wrongGraph = iri("https://example.org/concept/wrong-graph");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(wrongType, RDF.TYPE,
                    iri(ONTOLEX + "LexicalEntry"), conceptGraph);
            connection.add(wrongGraph, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), italianGraph);
        }
        assertThatThrownBy(() -> manager.get(wrongType.stringValue()))
                .hasMessageStartingWith("INVALID_LEXICAL_CONCEPT_TYPE:");
        assertThatThrownBy(() -> manager.get(wrongGraph.stringValue()))
                .hasMessageStartingWith("LEXICAL_CONCEPT_NOT_FOUND:");
    }

    private void lexicalEntry(RepositoryConnection connection, IRI entry,
                              Resource graph) {
        connection.add(entry, RDF.TYPE, iri(ONTOLEX + "LexicalEntry"), graph);
    }

    private void lexicalSense(RepositoryConnection connection, IRI sense,
                              Resource graph) {
        connection.add(sense, RDF.TYPE, iri(ONTOLEX + "LexicalSense"), graph);
    }

    private void relatedConcept(RepositoryConnection connection, IRI related,
                                String label, String language) {
        connection.add(related, RDF.TYPE, iri(ONTOLEX + "LexicalConcept"),
                conceptGraph);
        connection.add(related, iri(SKOS + "prefLabel"),
                vf.createLiteral(label, language), conceptGraph);
        connection.add(related, iri(SKOS + "hiddenLabel"),
                vf.createLiteral(label + " hidden", language), conceptGraph);
    }

    private List<String> labels(List<LexicalConceptLinkedResource> resources,
                                IRI resource) {
        return resources.stream()
                .filter(item -> resource.stringValue().equals(item.iri))
                .findFirst().get().labels.stream()
                .map(value -> value.value).collect(java.util.stream.Collectors.toList());
    }

    private void assertRelation(List<LexicalConceptRelation> relations, IRI iri) {
        assertThat(relations).hasSize(1);
        assertThat(relations.get(0).iri).isEqualTo(iri.stringValue());
        assertThat(relations.get(0).labels).hasSize(2);
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }
}
