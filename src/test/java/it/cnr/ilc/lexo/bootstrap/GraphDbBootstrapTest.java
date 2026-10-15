package it.cnr.ilc.lexo.bootstrap;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GraphDbBootstrapTest {
    private IRI iri(String s) { return SimpleValueFactory.getInstance().createIRI(s); }

    @Test
    void importsMixedFormatsIdempotentlyIntoSchemaOnly() {
        SailRepository memory = new SailRepository(new MemoryStore());
        memory.init();
        try {
            IRI graph = iri("https://lexo.ilc.cnr.it/graphs/lexical/schema");
            IRI app = iri("urn:test:application");
            GraphDbBootstrap.importSchema(memory);
            long size;
            try (RepositoryConnection c = memory.getConnection()) {
                size = c.size(graph);
                assertThat(size).isGreaterThan(1601);
                for (String name : new String[]{"extractCollocations", "node", "sortBy"}) {
                    assertThat(c.hasStatement(iri("https://ilc.cnr.it/klab/ontology/cpp#" + name),
                            RDF.TYPE, null, false, graph)).isTrue();
                }
                assertThat(c.hasStatement(iri("http://www.w3.org/ns/lemon/ontolex#LexicalEntry"),
                        RDF.TYPE, null, false, graph)).isTrue();
                c.add(app, RDF.TYPE, app, app);
                assertThat(c.size((org.eclipse.rdf4j.model.Resource) null)).isZero();
            }
            GraphDbBootstrap.importSchema(memory);
            try (RepositoryConnection c = memory.getConnection()) {
                assertThat(c.size(graph)).isEqualTo(size);
                assertThat(c.size(app)).isEqualTo(1);
                assertThat(c.size((org.eclipse.rdf4j.model.Resource) null)).isZero();
            }
        } finally { memory.shutDown(); }
    }

    @Test
    void invalidRdfRollsBackSchemaLegacyCleanupAndChecksum() {
        SailRepository memory = new SailRepository(new MemoryStore()); memory.init();
        String previous = it.cnr.ilc.lexo.LexOProperties.getProperty("Bootstrap.schema.manifest");
        IRI schema = iri("https://lexo.ilc.cnr.it/graphs/lexical/schema");
        IRI legacy = iri("https://lexo.ilc.cnr.it/graphs/schema");
        IRI sentinel = iri("urn:test:sentinel");
        try {
            try (RepositoryConnection c = memory.getConnection()) {
                c.add(sentinel, RDF.TYPE, sentinel, schema);
                c.add(sentinel, RDF.TYPE, sentinel, legacy);
            }
            it.cnr.ilc.lexo.LexOProperties.setProperty("Bootstrap.schema.manifest", "bootstrap/cpp-invalid-manifest.json");
            assertThatThrownBy(() -> GraphDbBootstrap.importSchema(memory)).isInstanceOf(IllegalStateException.class);
            try (RepositoryConnection c = memory.getConnection()) {
                assertThat(c.size(schema)).isEqualTo(1); assertThat(c.size(legacy)).isEqualTo(1);
                assertThat(c.size(iri("https://lexo.ilc.cnr.it/graphs/bootstrap"))).isZero();
                assertThat(c.size((org.eclipse.rdf4j.model.Resource) null)).isZero();
            }
        } finally {
            it.cnr.ilc.lexo.LexOProperties.setProperty("Bootstrap.schema.manifest", previous);
            memory.shutDown();
        }
    }

    @Test
    void selectsParserDeterministicallyAndRejectsUnknownExtensions() {
        assertThat(GraphDbBootstrap.schemaFormat("cpp.ttl")).isEqualTo(RDFFormat.TURTLE);
        assertThat(GraphDbBootstrap.schemaFormat("old.owl")).isEqualTo(RDFFormat.RDFXML);
        assertThat(GraphDbBootstrap.schemaFormat("old.rdf")).isEqualTo(RDFFormat.RDFXML);
        assertThatThrownBy(() -> GraphDbBootstrap.schemaFormat("unknown.json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
