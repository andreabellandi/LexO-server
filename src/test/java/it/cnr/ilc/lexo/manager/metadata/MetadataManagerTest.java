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
        protectedPatch.properties = Arrays.asList(property(ONTOLEX + "usage",
                new RdfMetadataValue("x", "literal", null, null)));
        assertThatThrownBy(() -> manager.patch(protectedPatch))
                .hasMessageStartingWith("RESERVED_METADATA_PROPERTY:");

        MetadataDeleteRequest protectedDeletion = new MetadataDeleteRequest();
        protectedDeletion.entityType = "lexicalEntry";
        protectedDeletion.resource = entry.stringValue();
        protectedDeletion.language = "it";
        protectedDeletion.properties = Arrays.asList(
                "http://www.w3.org/ns/lemon/decomp#subterm");
        assertThatThrownBy(() -> manager.delete(protectedDeletion))
                .hasMessageStartingWith("RESERVED_METADATA_PROPERTY:");

        MetadataPatchRequest permittedPatch = patch("lexicalEntry",
                entry.stringValue());
        permittedPatch.language = "it";
        permittedPatch.properties = Arrays.asList(
                property(RDFS.LABEL.stringValue(),
                        new RdfMetadataValue("etichetta", "literal", "it", null)),
                property("http://www.w3.org/2004/02/skos/core#note",
                        new RdfMetadataValue("nota SKOS", "literal", "it", null)));
        assertThat(manager.patch(permittedPatch).metadata)
                .extracting(item -> item.property)
                .contains(RDFS.LABEL.stringValue(),
                        "http://www.w3.org/2004/02/skos/core#note");
    }

    @Test
    void supportsLexicalSenseAndFormMetadataInTheirLanguageGraph() {
        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));
        IRI otherLanguageGraph = iri(LexiconCrudSupport.lexicalGraphUri("en"));
        IRI schema = iri(LexicalNamedGraphs.schemaGraphUri());
        IRI sense = iri("https://example.org/sense/1");
        IRI form = iri("https://example.org/form/1");
        IRI inflectedForm = iri("https://example.org/ontology/InflectedForm");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(sense, RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), graph);
            connection.add(inflectedForm, RDFS.SUBCLASSOF,
                    iri(ONTOLEX + "Form"), schema);
            connection.add(form, RDF.TYPE, inflectedForm, graph);
        }

        MetadataPatchRequest sensePatch = patch("lexicalSense",
                sense.stringValue());
        sensePatch.language = "IT";
        sensePatch.properties = Arrays.asList(property(DCTERMS.TITLE.stringValue(),
                new RdfMetadataValue("accezione", "literal", "it", null)));
        MetadataResult senseResult = manager.patch(sensePatch);

        MetadataPatchRequest formPatch = patch("form", form.stringValue());
        formPatch.language = "it";
        formPatch.properties = Arrays.asList(property(DCTERMS.SOURCE.stringValue(),
                new RdfMetadataValue("https://example.org/source/1", "iri",
                        null, null)));
        MetadataResult formResult = manager.patch(formPatch);

        assertThat(senseResult.entityType).isEqualTo("lexicalSense");
        assertThat(senseResult.metadata).extracting(item -> item.property)
                .containsExactly(DCTERMS.TITLE.stringValue());
        assertThat(formResult.entityType).isEqualTo("form");
        assertThat(formResult.metadata).extracting(item -> item.property)
                .containsExactly(DCTERMS.SOURCE.stringValue());
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(sense, DCTERMS.TITLE,
                    vf.createLiteral("accezione", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(form, DCTERMS.SOURCE,
                    iri("https://example.org/source/1"), false, graph)).isTrue();
            assertThat(connection.hasStatement(sense, DCTERMS.TITLE,
                    null, false, otherLanguageGraph)).isFalse();
            assertThat(connection.hasStatement(form, DCTERMS.SOURCE,
                    null, false, otherLanguageGraph)).isFalse();
            assertDefaultGraphEmpty(connection);
        }

        MetadataTarget senseRead = new MetadataTarget();
        senseRead.entityType = "lexicalSense";
        senseRead.resource = sense.stringValue();
        senseRead.language = "it";
        assertThat(manager.read(senseRead).metadata).hasSize(1);

        MetadataDeleteRequest formDelete = new MetadataDeleteRequest();
        formDelete.entityType = "form";
        formDelete.resource = form.stringValue();
        formDelete.language = "it";
        formDelete.properties = Arrays.asList(DCTERMS.SOURCE.stringValue());
        assertThat(manager.delete(formDelete).metadata).isEmpty();

        MetadataTarget wrongLanguage = new MetadataTarget();
        wrongLanguage.entityType = "form";
        wrongLanguage.resource = form.stringValue();
        wrongLanguage.language = "en";
        assertThatThrownBy(() -> manager.read(wrongLanguage))
                .isInstanceOf(MetadataManager.MetadataException.class)
                .hasMessageStartingWith("METADATA_RESOURCE_NOT_FOUND:");
    }

    @Test
    void neverReturnsPreexistingProtectedPredicatesAsMetadata() {
        IRI graph = iri(LexiconCrudSupport.lexicalConceptGraphUri());
        IRI concept = iri("https://example.org/concept/protected-output");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(concept, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), graph);
            connection.add(concept, iri("http://www.w3.org/ns/lemon/vartrans#category"),
                    vf.createLiteral("hidden"), graph);
            connection.add(concept, DCTERMS.TITLE,
                    vf.createLiteral("visible"), graph);
        }

        MetadataTarget target = new MetadataTarget();
        target.entityType = "lexicalConcept";
        target.resource = concept.stringValue();

        assertThat(manager.read(target).metadata)
                .extracting(item -> item.property)
                .containsExactly(DCTERMS.TITLE.stringValue());
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
