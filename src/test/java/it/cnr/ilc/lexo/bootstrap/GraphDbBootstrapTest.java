package it.cnr.ilc.lexo.bootstrap;

import it.cnr.ilc.lexo.manager.lexint.LexIntRepository;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.OWL;
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
                assertThat(c.hasStatement(iri("https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#InterpretiveClaim"),
                        RDF.TYPE, OWL.CLASS, false, graph)).isTrue();
                assertThat(c.hasStatement(iri("https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#targetAssertion"),
                        RDF.TYPE, OWL.OBJECTPROPERTY, false, graph)).isTrue();
                assertThat(c.hasStatement(iri("https://ilc.cnr.it/klab/ontology/lexint/publication#Publication"),
                        RDF.TYPE, OWL.CLASS, false, graph)).isTrue();
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
    void importsLexIntShapesIdempotentlyWithoutTouchingApplicationGraphs() {
        SailRepository memory = new SailRepository(new MemoryStore());
        memory.init();
        IRI shapes = iri("https://lexo.ilc.cnr.it/graphs/lexical/lexint/shapes");
        IRI records = iri("https://lexo.ilc.cnr.it/graphs/lexical/lexint/records");
        IRI audit = iri("https://lexo.ilc.cnr.it/graphs/lexical/lexint/audit");
        IRI sentinel = iri("urn:test:lexint:sentinel");
        try {
            try (RepositoryConnection c = memory.getConnection()) {
                c.add(sentinel, RDF.TYPE, sentinel, records);
                c.add(sentinel, RDF.TYPE, sentinel, audit);
            }
            GraphDbBootstrap.importLexIntShapes(memory);
            long size;
            try (RepositoryConnection c = memory.getConnection()) {
                size = c.size(shapes);
                assertThat(size).isGreaterThan(80);
                assertThat(c.hasStatement(iri("https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation/shapes#ClaimShape"),
                        RDF.TYPE, iri("http://www.w3.org/ns/shacl#NodeShape"), false, shapes)).isTrue();
                assertThat(c.hasStatement(iri("https://ilc.cnr.it/klab/ontology/lexint/publication#PublicationShape"),
                        RDF.TYPE, iri("http://www.w3.org/ns/shacl#NodeShape"), false, shapes)).isTrue();
                assertThat(c.hasStatement(null, iri("http://www.w3.org/ns/shacl#path"),
                        iri("https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#targetAssertion"), false, shapes)).isTrue();
                assertThat(c.size(records)).isEqualTo(1);
                assertThat(c.size(audit)).isEqualTo(1);
                assertThat(c.size((org.eclipse.rdf4j.model.Resource) null)).isZero();
            }
            GraphDbBootstrap.importLexIntShapes(memory);
            try (RepositoryConnection c = memory.getConnection()) {
                assertThat(c.size(shapes)).isEqualTo(size);
                assertThat(c.size(records)).isEqualTo(1);
                assertThat(c.size(audit)).isEqualTo(1);
            }
        } finally {
            memory.shutDown();
        }
    }

    @Test
    void malformedLexIntShapeRollsBackGraphAndChecksum() {
        SailRepository memory = new SailRepository(new MemoryStore());
        memory.init();
        String previous = it.cnr.ilc.lexo.LexOProperties.getProperty(
                "Bootstrap.lexint.shapes.manifest");
        IRI shapes = iri("https://lexo.ilc.cnr.it/graphs/lexical/lexint/shapes");
        IRI sentinel = iri("urn:test:shape-sentinel");
        try {
            try (RepositoryConnection c = memory.getConnection()) {
                c.add(sentinel, RDF.TYPE, sentinel, shapes);
            }
            it.cnr.ilc.lexo.LexOProperties.setProperty(
                    "Bootstrap.lexint.shapes.manifest",
                    "bootstrap/lexint-invalid-shapes-manifest.json");
            assertThatThrownBy(() -> GraphDbBootstrap.importLexIntShapes(memory))
                    .isInstanceOf(IllegalStateException.class);
            try (RepositoryConnection c = memory.getConnection()) {
                assertThat(c.size(shapes)).isEqualTo(1);
                assertThat(c.hasStatement(sentinel, RDF.TYPE, sentinel, false,
                        shapes)).isTrue();
                assertThat(c.size(iri("https://lexo.ilc.cnr.it/graphs/bootstrap")))
                        .isZero();
                assertThat(c.size((org.eclipse.rdf4j.model.Resource) null)).isZero();
            }
        } finally {
            it.cnr.ilc.lexo.LexOProperties.setProperty(
                    "Bootstrap.lexint.shapes.manifest", previous);
            memory.shutDown();
        }
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
    void rejectsLexIntGraphsInsideAnyApplicationGraphFamily() {
        SailRepository memory = new SailRepository(new MemoryStore());
        memory.init();
        String previous = it.cnr.ilc.lexo.LexOProperties.getProperty(
                "GraphDb.lexintRecordsNamedGraph");
        try {
            it.cnr.ilc.lexo.LexOProperties.setProperty(
                    "GraphDb.lexintRecordsNamedGraph",
                    "https://lexo.ilc.cnr.it/graphs/lexical/lexica/it");
            assertThatThrownBy(() -> GraphDbBootstrap.importLexIntShapes(memory))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("application graph family");
            assertThatThrownBy(() -> new LexIntRepository(memory))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("application graph family");
        } finally {
            it.cnr.ilc.lexo.LexOProperties.setProperty(
                    "GraphDb.lexintRecordsNamedGraph", previous);
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
