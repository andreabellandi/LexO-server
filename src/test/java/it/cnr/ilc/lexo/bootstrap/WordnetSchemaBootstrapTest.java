package it.cnr.ilc.lexo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.manager.wordnet.WordnetSchemaManager;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

class WordnetSchemaBootstrapTest {

    private static final String EXPECTED_SHA256 =
            "deecbb23eb84ff9f9a3d40adb6077810f2a0bd6ab9ccedd7d0b3b26af3f422bb";
    private static final IRI SCHEMA = iri(
            "https://lexo.ilc.cnr.it/graphs/wordnet/schema");
    private static final IRI APPLICATION = iri(
            "https://lexo.ilc.cnr.it/graphs/wordnet/it/italwordnet");

    @Test
    void pinnedTurtleAndRuntimeRdfXmlAreLossless() throws Exception {
        byte[] turtle = BootstrapResources.readBytes(
                "bootstrap/schema/wn-lemon-1.4.ttl");
        byte[] rdfXml = BootstrapResources.readBytes(
                "bootstrap/schema/wn-lemon-1.4.rdf");

        assertThat(hex(MessageDigest.getInstance("SHA-256").digest(turtle)))
                .isEqualTo(EXPECTED_SHA256);
        Model turtleModel = Rio.parse(new ByteArrayInputStream(turtle),
                "https://globalwordnet.github.io/schemas/wn#", RDFFormat.TURTLE);
        Model rdfXmlModel = Rio.parse(new ByteArrayInputStream(rdfXml),
                "https://globalwordnet.github.io/schemas/wn#", RDFFormat.RDFXML);
        assertThat(Models.isomorphic(turtleModel, rdfXmlModel)).isTrue();
        assertThat(turtleModel).hasSize(646);
    }

    @Test
    void wordnetBootstrapIsIdempotentAndPreservesApplicationGraphs() {
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try {
            try (RepositoryConnection connection = repository.getConnection()) {
                connection.add(iri("https://example.org/application"),
                        iri("https://example.org/predicate"),
                        SimpleValueFactory.getInstance().createLiteral("kept"),
                        APPLICATION);
            }

            GraphDbBootstrap.importWordnetSchema(repository);
            long firstSize;
            try (RepositoryConnection connection = repository.getConnection()) {
                firstSize = connection.size(SCHEMA);
                assertThat(firstSize).isGreaterThan(646L);
                assertThat(connection.size(APPLICATION)).isEqualTo(1L);
                assertThat(connection.size((org.eclipse.rdf4j.model.Resource) null))
                        .isZero();
            }

            GraphDbBootstrap.importWordnetSchema(repository);
            try (RepositoryConnection connection = repository.getConnection()) {
                assertThat(connection.size(SCHEMA)).isEqualTo(firstSize);
                assertThat(connection.size(APPLICATION)).isEqualTo(1L);
                assertThat(connection.hasStatement(
                        iri("https://lexo.ilc.cnr.it/profiles/wordnet/1.4"),
                        iri("https://lexo.ilc.cnr.it/vocabulary/wordnet/profileVersion"),
                        SimpleValueFactory.getInstance().createLiteral("1.4"),
                        false, SCHEMA)).isTrue();
            }
            assertThat(new WordnetSchemaManager(repository).get())
                    .containsEntry("version", "1.4")
                    .containsEntry("namespace",
                            "https://globalwordnet.github.io/schemas/wn#")
                    .containsEntry("sha256", EXPECTED_SHA256);
        } finally {
            repository.shutDown();
        }
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }
}
