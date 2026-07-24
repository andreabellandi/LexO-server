package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.service.data.text.output.TextCatalog;
import it.cnr.ilc.lexo.service.data.text.output.TextCatalogItem;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.nio.charset.StandardCharsets;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies the repository-backed catalog used by text-list user interfaces. */
class TextCatalogManagerTest {

    private static final String PUBLIC_BASE =
            "https://lexo.ilc.cnr.it/resources/texts/";
    private static final String STRUCTURE =
            "https://lexo.ilc.cnr.it/vocabulary/nif-structure#";
    private static final String GRAPH_BASE =
            "https://lexo.ilc.cnr.it/graphs/nif/";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String OA = "http://www.w3.org/ns/oa#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private Repository textRepository;
    private Repository lexicalRepository;

    @BeforeEach
    void setUp() throws Exception {
        textRepository = new SailRepository(new MemoryStore());
        lexicalRepository = new SailRepository(new MemoryStore());
        textRepository.init();
        lexicalRepository.init();
        populateTexts();
        populateAttestations();
    }

    @AfterEach
    void tearDown() {
        lexicalRepository.shutDown();
        textRepository.shutDown();
    }

    @Test
    @DisplayName("Catalog lists every text with metadata, counts and attestations")
    void listsAllTexts() {
        try (RepositoryConnection texts = textRepository.getConnection();
             RepositoryConnection lexica = lexicalRepository.getConnection()) {
            TextCatalog result = TextCatalogManager.get().list(texts, lexica, null);

            assertThat(result.total).isEqualTo(2);
            assertThat(result.corpusId).isNull();
            assertThat(result.texts).extracting(item -> item.name)
                    .containsExactly("intervista.txt", "secondo.txt");

            TextCatalogItem interview = result.texts.get(0);
            assertThat(interview.fileId).isEqualTo("file-a");
            assertThat(interview.sizeBytes).isEqualTo(Long.valueOf(
                    "Una frase.".getBytes(StandardCharsets.UTF_8).length));
            assertThat(interview.sentenceCount).isEqualTo(1);
            assertThat(interview.tokenCount).isPositive();
            assertThat(interview.attestationCount).isEqualTo(2L);
            assertThat(interview.annotationCount).isEqualTo(1L);
            assertThat(interview.corpusId).isEqualTo("corpus-one");
            assertThat(interview.metadataValues.get("title"))
                    .containsExactly("Intervista di prova");
            assertThat(interview.metadataValues.get("author"))
                    .containsExactly("Mario Rossi");
            assertThat(interview.metadataValues.get("description"))
                    .containsExactly("Conversazione di prova");
            assertThat(result.texts.get(1).attestationCount).isZero();
            assertThat(result.texts.get(1).annotationCount).isZero();
        }
    }

    @Test
    @DisplayName("Catalog can be restricted to one corpus")
    void filtersByCorpus() {
        try (RepositoryConnection texts = textRepository.getConnection();
             RepositoryConnection lexica = lexicalRepository.getConnection()) {
            TextCatalog result = TextCatalogManager.get()
                    .list(texts, lexica, "corpus-one");

            assertThat(result.corpusId).isEqualTo("corpus-one");
            assertThat(result.total).isEqualTo(1);
            assertThat(result.texts.get(0).fileId).isEqualTo("file-a");
        }
    }

    @Test
    @DisplayName("Catalog rejects a corpus that is not present in LexOTexts")
    void rejectsUnknownCorpus() {
        try (RepositoryConnection texts = textRepository.getConnection();
             RepositoryConnection lexica = lexicalRepository.getConnection()) {
            assertThatThrownBy(() -> TextCatalogManager.get()
                    .list(texts, lexica, "missing-corpus"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Corpus not found");
        }
    }

    private void populateTexts() throws Exception {
        ControlledCommonMarkParser parser = new ControlledCommonMarkParser();
        NifModelWriter writer = new NifModelWriter(PUBLIC_BASE, STRUCTURE);
        String corpusUri = writer.corpusUri("corpus-one");

        ParsedTextDocument corpusMetadata = parser.parseMetadataOnly(
                "---\ntitle: Corpus uno\nlanguage: it\n---\n");
        Model corpus = writer.buildCorpus("corpus-one", "corpus.txt",
                corpusMetadata, null);

        ParsedTextDocument interview = parser.parsePlainText(
                "---\ntitle: Intervista di prova\nauthor: Mario Rossi\n"
                        + "description: Conversazione di prova\nlanguage: it\n---\nUna frase.");
        ParsedTextDocument second = parser.parsePlainText("Secondo documento.");

        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(corpus, iri(GRAPH_BASE + "corpora/corpus-one"));
            connection.add(writer.build("file-a", "intervista.txt", interview, corpusUri),
                    iri(GRAPH_BASE + "documents/file-a"));
            connection.add(writer.build("file-b", "secondo.txt", second),
                    iri(GRAPH_BASE + "documents/file-b"));
        }
    }

    private void populateAttestations() {
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        IRI attestationType = iri(FRAC + "Attestation");
        IRI locus = iri(FRAC + "locus");
        String document = PUBLIC_BASE + "file-a";
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            IRI direct = iri("https://example.org/attestation/direct");
            connection.add(direct, RDF.TYPE, attestationType, graph);
            connection.add(direct, locus, iri(document + "#char=0,3"), graph);

            IRI wrapped = iri("https://example.org/attestation/wrapped");
            BNode target = vf.createBNode();
            connection.add(wrapped, RDF.TYPE, attestationType, graph);
            connection.add(wrapped, locus, target, graph);
            connection.add(target, iri(OA + "hasSource"),
                    iri(document + "#context"), graph);

            IRI annotationGraph = iri(LexicalNamedGraphs.annotationGraphUri("file-a"));
            connection.add(iri("https://example.org/annotation/one"), RDF.TYPE,
                    iri(OA + "Annotation"), annotationGraph);

            // Same class outside the configured attestation graph must be ignored.
            IRI unrelatedGraph = iri("https://example.org/graph/unrelated");
            IRI ignored = iri("https://example.org/attestation/ignored");
            connection.add(ignored, RDF.TYPE, attestationType, unrelatedGraph);
            connection.add(ignored, locus, iri(document + "#context"), unrelatedGraph);
        }
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }
}
