package it.cnr.ilc.lexo.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/** Verifies that lexical and attestation updates cannot mix their named graphs. */
class LexicalNamedGraphsTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    @Test
    void writesLexicalDataOnlyToLexiconGraph() {
        IRI subject = VF.createIRI("https://example.org/lexicon/one");
        IRI predicate = VF.createIRI("https://example.org/property");

        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            execute(connection, "INSERT DATA { <" + subject + "> <" + predicate
                    + "> \"lexical\" }", LexicalNamedGraphs.Kind.LEXICON);

            assertThat(connection.hasStatement(subject, predicate, null, false,
                    VF.createIRI(LexicalNamedGraphs.lexiconGraphUri()))).isTrue();
            assertThat(connection.hasStatement(subject, predicate, null, false,
                    VF.createIRI(LexicalNamedGraphs.attestationGraphUri()))).isFalse();
            assertDefaultGraphEmpty(connection);
        } finally {
            repository.shutDown();
        }
    }

    @Test
    void lexicalCrudNeverWritesToDefaultGraph() {
        IRI subject = VF.createIRI("https://example.org/entry/one");
        IRI predicate = VF.createIRI("https://example.org/status");
        IRI graph = VF.createIRI(LexicalNamedGraphs.lexiconGraphUri());

        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            execute(connection, "INSERT DATA { <" + subject + "> <" + predicate
                    + "> \"created\" }", LexicalNamedGraphs.Kind.LEXICON);
            assertThat(connection.hasStatement(subject, predicate,
                    VF.createLiteral("created"), false, graph)).isTrue();
            assertDefaultGraphEmpty(connection);

            execute(connection,
                    "DELETE { <" + subject + "> <" + predicate + "> ?value } "
                    + "INSERT { <" + subject + "> <" + predicate + "> \"updated\" } "
                    + "WHERE { <" + subject + "> <" + predicate + "> ?value }",
                    LexicalNamedGraphs.Kind.LEXICON);
            assertThat(connection.hasStatement(subject, predicate,
                    VF.createLiteral("created"), false, graph)).isFalse();
            assertThat(connection.hasStatement(subject, predicate,
                    VF.createLiteral("updated"), false, graph)).isTrue();
            assertDefaultGraphEmpty(connection);

            execute(connection, "DELETE WHERE { <" + subject + "> <" + predicate
                    + "> ?value }", LexicalNamedGraphs.Kind.LEXICON);
            assertThat(connection.hasStatement(subject, predicate, null, false,
                    graph)).isFalse();
            assertDefaultGraphEmpty(connection);
        } finally {
            repository.shutDown();
        }
    }

    @Test
    void writesAttestationsOnlyToAttestationGraph() {
        IRI subject = VF.createIRI("https://example.org/attestation/one");
        IRI rdfType = VF.createIRI(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#type");

        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            execute(connection, "INSERT DATA { <" + subject + "> a "
                    + "<http://www.w3.org/ns/lemon/frac#Attestation> }",
                    LexicalNamedGraphs.Kind.ATTESTATION);

            assertThat(connection.hasStatement(subject, rdfType, null, false,
                    VF.createIRI(LexicalNamedGraphs.attestationGraphUri()))).isTrue();
            assertThat(connection.hasStatement(subject, rdfType, null, false,
                    VF.createIRI(LexicalNamedGraphs.lexiconGraphUri()))).isFalse();
            assertDefaultGraphEmpty(connection);
        } finally {
            repository.shutDown();
        }
    }

    private void execute(RepositoryConnection connection, String sparql,
                         LexicalNamedGraphs.Kind kind) {
        Update update = connection.prepareUpdate(QueryLanguage.SPARQL, sparql);
        LexicalNamedGraphs.configure(update, kind);
        update.execute();
    }

    private void assertDefaultGraphEmpty(RepositoryConnection connection) {
        try (org.eclipse.rdf4j.repository.RepositoryResult<org.eclipse.rdf4j.model.Statement>
                statements = connection.getStatements(null, null, null, false,
                        (Resource) null)) {
            assertThat(statements.hasNext())
                    .as("the RDF default graph must remain empty")
                    .isFalse();
        }
    }
}
