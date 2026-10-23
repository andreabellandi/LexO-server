package it.cnr.ilc.lexo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

class TextOrganizationBootstrapTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI DATA_GRAPH = VF.createIRI(
            "https://lexo.ilc.cnr.it/graphs/text-organization");
    private static final IRI SCHEMA_GRAPH = VF.createIRI(
            "https://lexo.ilc.cnr.it/graphs/text-organization/schema");
    private static final IRI FOLDER = VF.createIRI(
            "https://lexo.ilc.cnr.it/vocabulary/text-organization#Folder");

    @Test
    void packagedVocabularyIsRdfXmlAndBootstrapNeverTouchesFolderData() throws Exception {
        byte[] resource = BootstrapResources.readBytes(
                "bootstrap/text-organization/textOrganization.rdf");
        assertThat(Rio.parse(new ByteArrayInputStream(resource),
                "https://lexo.ilc.cnr.it/resources/", RDFFormat.RDFXML))
                .contains(VF.createStatement(FOLDER, RDF.TYPE, OWL.CLASS));

        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        IRI existingFolder = VF.createIRI(
                "https://lexo.ilc.cnr.it/resources/text-folders/existing");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(existingFolder, RDF.TYPE, FOLDER, DATA_GRAPH);
        }

        GraphDbBootstrap.importTextOrganizationVocabulary(repository);
        IRI unchangedSentinel = VF.createIRI("https://lexo.test/schema/sentinel");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(unchangedSentinel, RDF.TYPE, OWL.CLASS, SCHEMA_GRAPH);
        }
        GraphDbBootstrap.importTextOrganizationVocabulary(repository);

        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(FOLDER, RDF.TYPE, OWL.CLASS,
                    false, SCHEMA_GRAPH)).isTrue();
            assertThat(connection.hasStatement(FOLDER, RDF.TYPE, OWL.CLASS,
                    false, (org.eclipse.rdf4j.model.Resource) null)).isFalse();
            assertThat(connection.hasStatement(existingFolder, RDF.TYPE, FOLDER,
                    false, DATA_GRAPH)).isTrue();
            assertThat(connection.hasStatement(unchangedSentinel, RDF.TYPE,
                    OWL.CLASS, false, SCHEMA_GRAPH)).isTrue();

            IRI metadataGraph = VF.createIRI(
                    "https://lexo.ilc.cnr.it/graphs/bootstrap");
            IRI marker = VF.createIRI(metadataGraph.stringValue()
                    + "/text-organization-schema");
            connection.remove(marker, null, null, metadataGraph);
        }

        GraphDbBootstrap.importTextOrganizationVocabulary(repository);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.hasStatement(existingFolder, RDF.TYPE, FOLDER,
                    false, DATA_GRAPH)).isTrue();
            assertThat(connection.hasStatement(unchangedSentinel, RDF.TYPE,
                    OWL.CLASS, false, SCHEMA_GRAPH)).isFalse();
            assertThat(connection.hasStatement(null, null, null, false,
                    (org.eclipse.rdf4j.model.Resource) null)).isFalse();
        } finally {
            repository.shutDown();
        }
    }
}
