package it.cnr.ilc.lexo.manager.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.service.data.metadata.MetadataDeleteRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataResult;
import it.cnr.ilc.lexo.service.data.metadata.MetadataTarget;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
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
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MetadataManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private MetadataManager manager;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        manager = new MetadataManager(repository);
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void patchesReadsAndDeletesConceptMetadataOnlyInTheFixedGraph() {
        IRI graph = iri(LexiconCrudSupport.lexicalConceptGraphUri());
        IRI concept = iri("https://example.org/concept/1");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(concept, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), graph);
        }
        MetadataPatchRequest patch = patch("lexicalConcept", concept.stringValue());
        patch.properties = Arrays.asList(property("https://example.org/source",
                new RdfMetadataValue("https://example.org/source/1", "iri", null, null),
                new RdfMetadataValue("fonte", "literal", "it", null)));

        MetadataResult result = manager.patch(patch);

        assertThat(result.metadata).hasSize(1);
        assertThat(result.metadata.get(0).values).hasSize(2);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(concept, iri("https://example.org/source"),
                    iri("https://example.org/source/1"), false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, DCTERMS.MODIFIED,
                    null, false, graph)).isTrue();
            assertThat(connection.hasStatement(concept, null, null, false,
                    iri(LexiconCrudSupport.lexicalGraphUri("it")))).isFalse();
            assertDefaultGraphEmpty(connection);
        }

        MetadataDeleteRequest deletion = new MetadataDeleteRequest();
        deletion.entityType = "lexicalConcept";
        deletion.resource = concept.stringValue();
        deletion.properties = Arrays.asList("https://example.org/source");
        assertThat(manager.delete(deletion).metadata).isEmpty();
    }

    @Test
    void resolvesLanguageAndDocumentGraphsAndRejectsProtectedProperties() {
        IRI entryGraph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI schema = iri(LexicalNamedGraphs.schemaGraphUri());
        IRI word = iri(ONTOLEX + "Word");
        IRI entry = iri("https://example.org/entry/1");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        IRI attestation = iri("https://example.org/attestation/1");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(word, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "LexicalEntry"), schema);
            connection.add(entry, RDF.TYPE, word, entryGraph);
            connection.add(attestation, RDF.TYPE,
                    iri(FRAC + "Attestation"), attestationGraph);
        }

        MetadataPatchRequest entryPatch = patch("lexicalEntry", entry.stringValue());
        entryPatch.language = "IT";
        entryPatch.properties = Arrays.asList(property("https://example.org/note",
                new RdfMetadataValue("nota", "literal", "it", null)));
        assertThat(manager.patch(entryPatch).metadata).hasSize(1);

        MetadataPatchRequest attestationPatch = patch("attestation",
                attestation.stringValue());
        attestationPatch.fileId = "file-a";
        attestationPatch.properties = Arrays.asList(property("https://example.org/score",
                new RdfMetadataValue("2", "literal", null,
                        "http://www.w3.org/2001/XMLSchema#integer")));
        assertThat(manager.patch(attestationPatch).metadata).hasSize(1);

        MetadataPatchRequest protectedPatch = patch("lexicalEntry",
                entry.stringValue());
        protectedPatch.language = "it";
        protectedPatch.properties = Arrays.asList(property(RDFS.LABEL.stringValue(),
                new RdfMetadataValue("x", "literal", null, null)));
        assertThatThrownBy(() -> manager.patch(protectedPatch))
                .hasMessageStartingWith("RESERVED_METADATA_PROPERTY:");
    }

    @Test
    void rejectsWrongGraphWrongTypeAndInvalidRdfValuesWithoutWriting() {
        IRI concept = iri("https://example.org/concept/wrong");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(concept, RDF.TYPE, iri(ONTOLEX + "LexicalConcept"),
                    iri(LexiconCrudSupport.lexicalGraphUri("it")));
        }
        MetadataTarget missing = new MetadataTarget();
        missing.entityType = "lexicalConcept";
        missing.resource = concept.stringValue();
        assertThatThrownBy(() -> manager.read(missing))
                .isInstanceOf(MetadataManager.MetadataException.class)
                .hasMessageStartingWith("METADATA_RESOURCE_NOT_FOUND:");

        MetadataPatchRequest invalid = patch("lexicalConcept", concept.stringValue());
        invalid.properties = Arrays.asList(property("https://example.org/p",
                new RdfMetadataValue("x", "literal", "it",
                        "http://www.w3.org/2001/XMLSchema#string")));
        assertThatThrownBy(() -> manager.patch(invalid))
                .hasMessageStartingWith("INVALID_METADATA_VALUE:");
    }

    private MetadataPatchRequest patch(String type, String resource) {
        MetadataPatchRequest result = new MetadataPatchRequest();
        result.entityType = type;
        result.resource = resource;
        return result;
    }

    private RdfMetadataProperty property(String iri, RdfMetadataValue... values) {
        RdfMetadataProperty result = new RdfMetadataProperty();
        result.property = iri;
        result.values = Arrays.asList(values);
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
