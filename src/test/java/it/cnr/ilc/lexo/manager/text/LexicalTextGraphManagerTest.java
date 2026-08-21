package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/** Guards cascade cleanup of the two LexOLexica graph families. */
class LexicalTextGraphManagerTest {

    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";

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
            IRI linkedOnly = graph("https://example.org/attestation/linked-only");
            IRI externalGraph = graph("https://example.org/graphs/external");
            IRI externalSubject = graph("https://example.org/resource/incoming");
            IRI externalObject = graph("https://example.org/resource/outgoing");
            IRI observableB = graph("https://example.org/observable/b");
            IRI observableWithoutRemainingAttestations =
                    graph("https://example.org/observable/empty");
            IRI frequencyB = graph("https://example.org/frequency/b");
            IRI frequencyToDelete = graph("https://example.org/frequency/empty");
            IRI contextB = graph("https://example.org/text/b#context");
            connection.add(attestation, RDF.TYPE,
                    graph(FRAC + "Attestation"),
                    fileAAttestations);
            connection.add(graph("https://example.org/observable/a"),
                    graph(FRAC + "attestation"), attestation, fileAAttestations);
            connection.add(graph("https://example.org/observable/linked-only"),
                    graph(FRAC + "attestation"), linkedOnly, fileAAttestations);
            connection.add(annotation, RDF.TYPE,
                    graph("http://www.w3.org/ns/oa#Annotation"), fileAAnnotations);
            connection.add(other, RDF.TYPE,
                    graph(FRAC + "Attestation"),
                    fileBAttestations);
            connection.add(observableB, graph(FRAC + "attestation"), other,
                    fileBAttestations);
            connection.add(observableB, graph(FRAC + "attestation"), attestation,
                    fileBAttestations);
            connection.add(observableWithoutRemainingAttestations,
                    graph(FRAC + "attestation"), attestation, fileBAttestations);
            addFrequency(connection, observableB, frequencyB, contextB, 2,
                    fileBAttestations);
            addFrequency(connection, observableWithoutRemainingAttestations,
                    frequencyToDelete, contextB, 1, fileBAttestations);
            connection.add(externalSubject, graph("https://example.org/refersTo"),
                    attestation, externalGraph);
            connection.add(attestation, graph("https://example.org/refersTo"),
                    externalObject, externalGraph);
            connection.add(linkedOnly, graph("https://example.org/refersTo"),
                    externalObject, externalGraph);
            connection.add(externalSubject, graph("https://example.org/refersTo"),
                    other, externalGraph);

            assertThat(LexicalTextGraphManager.get()
                    .deleteDocumentGraphs(connection, "file-a")).isTrue();
            assertThat(connection.hasStatement(null, null, null, false,
                    fileAAttestations)).isFalse();
            assertThat(connection.hasStatement(null, null, null, false,
                    fileAAnnotations)).isFalse();
            assertThat(connection.hasStatement(other, RDF.TYPE, null, false,
                    fileBAttestations)).isTrue();
            assertThat(connection.hasStatement(attestation, null, null, false))
                    .isFalse();
            assertThat(connection.hasStatement(null, null, attestation, false))
                    .isFalse();
            assertThat(connection.hasStatement(linkedOnly, null, null, false))
                    .isFalse();
            assertThat(connection.hasStatement(externalSubject, null, other, false,
                    externalGraph)).isTrue();
            assertThat(connection.hasStatement(observableB,
                    graph(FRAC + "attestation"), other, false,
                    fileBAttestations)).isTrue();
            assertThat(connection.hasStatement(frequencyB, RDF.VALUE,
                    vf.createLiteral("1", XSD.INT), false,
                    fileBAttestations)).isTrue();
            assertThat(connection.hasStatement(observableWithoutRemainingAttestations,
                    graph(FRAC + "frequency"), null, false,
                    fileBAttestations)).isFalse();
            assertThat(connection.hasStatement(frequencyToDelete, null, null,
                    false, fileBAttestations)).isFalse();
            assertThat(LexicalTextGraphManager.get()
                    .deleteDocumentGraphs(connection, "file-a")).isFalse();
        } finally {
            repository.shutDown();
        }
    }

    private IRI graph(String value) {
        return vf.createIRI(value);
    }

    private void addFrequency(RepositoryConnection connection, IRI observable,
                              IRI frequency, IRI observedIn, int value,
                              IRI contextGraph) {
        connection.add(observable, graph(FRAC + "frequency"), frequency,
                contextGraph);
        connection.add(frequency, RDF.TYPE, graph(FRAC + "Frequency"),
                contextGraph);
        connection.add(frequency, graph(FRAC + "observedIn"), observedIn,
                contextGraph);
        connection.add(frequency, RDF.VALUE,
                vf.createLiteral(Integer.toString(value), XSD.INT), contextGraph);
    }
}
