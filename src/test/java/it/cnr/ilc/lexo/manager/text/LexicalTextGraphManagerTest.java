package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/** Guards cascade cleanup of the two LexOLexica graph families. */
class LexicalTextGraphManagerTest {

    private final ValueFactory vf = SimpleValueFactory.getInstance();

    @Test
    void deletesOnlyTheAttestationsAndAnnotationsOfTheSelectedText() {
        Repository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            IRI fileAAttestations = graph(LexicalNamedGraphs.attestationGraphUri("file-a"));
            IRI fileAAnnotations = graph(LexicalNamedGraphs.annotationGraphUri("file-a"));
            IRI fileBAttestations = graph(LexicalNamedGraphs.attestationGraphUri("file-b"));
            IRI attestation = graph("https://example.org/attestation/a");
            IRI annotation = graph("https://example.org/annotation/a");
            IRI other = graph("https://example.org/attestation/b");
            connection.add(attestation, RDF.TYPE,
                    graph("http://www.w3.org/ns/lemon/frac#Attestation"),
                    fileAAttestations);
            connection.add(annotation, RDF.TYPE,
                    graph("http://www.w3.org/ns/oa#Annotation"), fileAAnnotations);
            connection.add(other, RDF.TYPE,
                    graph("http://www.w3.org/ns/lemon/frac#Attestation"),
                    fileBAttestations);

            assertThat(LexicalTextGraphManager.get()
                    .deleteDocumentGraphs(connection, "file-a")).isTrue();
            assertThat(connection.hasStatement(null, null, null, false,
                    fileAAttestations)).isFalse();
            assertThat(connection.hasStatement(null, null, null, false,
                    fileAAnnotations)).isFalse();
            assertThat(connection.hasStatement(other, RDF.TYPE, null, false,
                    fileBAttestations)).isTrue();
            assertThat(LexicalTextGraphManager.get()
                    .deleteDocumentGraphs(connection, "file-a")).isFalse();
        } finally {
            repository.shutDown();
        }
    }

    private IRI graph(String value) {
        return vf.createIRI(value);
    }
}
