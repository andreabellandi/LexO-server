package it.cnr.ilc.lexo.manager;

import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.ENTITY;
import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.LANGUAGE;
import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.VERSION_STATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.lexint.LexIntGraphGuard;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryCreationRequest;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexIntGraphGuardIntegrationTest {
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private SailRepository repository;
    private String enabled;
    private String managed;

    @BeforeEach
    void setUp() {
        enabled = LexOProperties.getProperty("LexInt.enabled");
        managed = LexOProperties.getProperty(
                "LexInt.managedPublication.enabled");
        LexOProperties.setProperty("LexInt.enabled", "true");
        LexOProperties.setProperty("LexInt.managedPublication.enabled", "true");
        repository = new SailRepository(new MemoryStore());
        repository.init();
        IRI audit = iri(LexOProperties.getProperty(
                "GraphDb.lexintAuditNamedGraph"));
        IRI version = iri("https://example.org/version/current-it");
        IRI schema = iri(LexicalNamedGraphs.schemaGraphUri());
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(version, RDF.TYPE, ENTITY, audit);
            connection.add(version, LANGUAGE, vf.createLiteral("it"), audit);
            connection.add(version, VERSION_STATE,
                    vf.createLiteral("CURRENT"), audit);
            connection.add(iri("http://www.w3.org/ns/lemon/ontolex#Word"),
                    RDFS.SUBCLASSOF,
                    iri("http://www.w3.org/ns/lemon/ontolex#LexicalEntry"),
                    schema);
            connection.add(iri(
                    "http://www.lexinfo.net/ontology/3.0/lexinfo#noun"),
                    RDF.TYPE, iri(
                            "http://www.lexinfo.net/ontology/3.0/lexinfo#PartOfSpeech"),
                    schema);
        }
    }

    @AfterEach
    void tearDown() {
        LexOProperties.setProperty("LexInt.enabled", enabled);
        LexOProperties.setProperty("LexInt.managedPublication.enabled", managed);
        repository.shutDown();
    }

    @Test
    void lexicalEntryWriterCannotBypassManagedScopeAndStillWorksWhenDisabled() {
        LexicalEntryManager manager = new LexicalEntryManager(repository);
        LexicalEntryCreationRequest request = request();
        IRI graph = iri(LexiconCrudSupport.lexicalGraphUri("it"));

        assertThatThrownBy(() -> manager.create(request, "editor"))
                .isInstanceOf(LexIntGraphGuard.ManagedWriteException.class);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(graph)).isZero();
            assertThat(connection.size((Resource) null)).isZero();
        }

        LexOProperties.setProperty("LexInt.managedPublication.enabled", "false");
        assertThat(manager.create(request, "editor").entry).isNotBlank();
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(graph)).isPositive();
            assertThat(connection.size((Resource) null)).isZero();
        }
    }

    private LexicalEntryCreationRequest request() {
        LexicalEntryCreationRequest request = new LexicalEntryCreationRequest();
        request.label = "casa";
        request.type = "ontolex:Word";
        request.pos = "lexinfo:noun";
        request.language = "it";
        return request;
    }

    private IRI iri(String value) { return vf.createIRI(value); }
}
