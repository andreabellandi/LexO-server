package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import java.util.Arrays;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Semantic RDF tests for NIF generation.
 *
 * <p>The tests inspect RDF values rather than serialized Turtle. Turtle prefix,
 * whitespace and statement order are presentation details and must not make a
 * semantically correct test fail.</p>
 */
class NifModelWriterTest {

    private static final String BASE = "https://example.org/texts/";
    private static final String STRUCTURE = "https://example.org/nif-structure#";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";

    private final SimpleValueFactory values = SimpleValueFactory.getInstance();
    private ControlledCommonMarkParser parser;
    private NifModelWriter writer;

    @BeforeEach
    void setUp() {
        parser = new ControlledCommonMarkParser();
        writer = new NifModelWriter(BASE, STRUCTURE);
    }

    @Test
    @DisplayName("Every supported metadata key is mapped to its dcterms predicate")
    void mapsSupportedMetadata() throws Exception {
        ParsedTextDocument document = parser.parsePlainText(
                "---\n"
                        + "id: doc-001\n"
                        + "title: Storia della lessicografia\n"
                        + "author: Mario Rossi\n"
                        + "date: 2026\n"
                        + "language: it\n"
                        + "format: text/plain\n"
                        + "corpus: Corpus storico\n"
                        + "---\nTesto.");
        Model model = writer.build("file-1", "example.txt", document);
        IRI context = iri(BASE + "file-1#context");

        assertLiteral(model, context, "identifier", "doc-001");
        assertLiteral(model, context, "title", "Storia della lessicografia");
        assertLiteral(model, context, "creator", "Mario Rossi");
        assertLiteral(model, context, "created", "2026");
        assertLiteral(model, context, "language", "it");
        assertLiteral(model, context, "format", "text/plain");
        assertLiteral(model, context, "isPartOf", "Corpus storico");
    }

    @Test
    @DisplayName("Metadata lists may mix literals and all accepted URI spellings")
    void mapsMixedLiteralAndUriLists() throws Exception {
        String raw = "https://example.org/people/raw";
        String bracketed = "https://example.org/people/bracketed";
        String markdown = "https://example.org/people/markdown";
        String wrappedMarkdown = "https://example.org/people/wrapped-markdown";
        ParsedTextDocument document = parser.parsePlainText(
                "---\nauthor:\n"
                        + "  - Mario Rossi\n"
                        + "  - " + raw + "\n"
                        + "  - <" + bracketed + ">\n"
                        + "  - [" + markdown + "](" + markdown + ")\n"
                        + "  - <[" + wrappedMarkdown + "](" + wrappedMarkdown + ")>\n"
                        + "---\nTesto.");

        Model model = writer.build("file-2", "example.txt", document);
        IRI context = iri(BASE + "file-2#context");
        IRI creator = iri(DCTERMS + "creator");

        assertThat(model.filter(context, creator, null).objects())
                .containsExactlyInAnyOrder(
                        values.createLiteral("Mario Rossi"), iri(raw), iri(bracketed),
                        iri(markdown), iri(wrappedMarkdown));
    }

    @Test
    @DisplayName("A URI used as metadata id is emitted as an IRI, not a quoted literal")
    void mapsUriIdentifierAsIri() throws Exception {
        ParsedTextDocument document = parser.parsePlainText(
                "---\nid: <https://example.org/documents/001>\n---\nTesto.");
        Model model = writer.build("file-3", "example.txt", document);
        IRI context = iri(BASE + "file-3#context");

        assertThat(model.filter(context, iri(DCTERMS + "identifier"), null).objects())
                .contains(iri("https://example.org/documents/001"));
    }

    @Test
    @DisplayName("Document membership is represented with dcterms:isPartOf")
    void linksDocumentToSelectedCorpus() throws Exception {
        ParsedTextDocument document = parser.parsePlainText("Testo.");
        String corpusUri = BASE + "corpora/corpus-1";
        Model model = writer.build("file-4", "example.txt", document, corpusUri);

        assertThat(model.contains(iri(BASE + "file-4#context"),
                iri(DCTERMS + "isPartOf"), iri(corpusUri))).isTrue();
    }

    @Test
    @DisplayName("Corpus NIF has metadata and members but no textual nif:isString")
    void buildsTextlessCorpusModel() throws Exception {
        ParsedTextDocument metadata = parser.parseMetadataOnly(
                "---\ntitle: Corpus di prova\nlanguage: it\n---\n");
        Model model = writer.buildCorpus("corpus-1", "corpus.txt", metadata,
                Arrays.asList(BASE + "file-a#context", BASE + "file-b#context"));
        IRI corpus = iri(BASE + "corpora/corpus-1");

        assertLiteral(model, corpus, "title", "Corpus di prova");
        assertThat(model.filter(corpus, iri(DCTERMS + "hasPart"), null).objects())
                .containsExactlyInAnyOrder(
                        iri(BASE + "file-a#context"), iri(BASE + "file-b#context"));
        assertThat(model.contains(null, iri(NIF + "isString"), null)).isFalse();
    }

    @Test
    @DisplayName("NIF endIndex counts Unicode code points rather than UTF-16 code units")
    void usesCodePointOffsetsForEmoji() throws Exception {
        ParsedTextDocument document = parser.parsePlainText("A😀B");
        Model model = writer.build("unicode", "unicode.txt", document);
        IRI context = iri(BASE + "unicode#context");
        Value endIndex = model.filter(context, iri(NIF + "endIndex"), null)
                .objects().iterator().next();

        assertThat(((Literal) endIndex).intValue()).isEqualTo(3);
    }

    private void assertLiteral(Model model, IRI subject, String predicate, String lexicalValue) {
        assertThat(model.filter(subject, iri(DCTERMS + predicate), null).objects())
                .anySatisfy(value -> {
                    assertThat(value).isInstanceOf(Literal.class);
                    assertThat(value.stringValue()).isEqualTo(lexicalValue);
                });
    }

    private IRI iri(String value) {
        return values.createIRI(value);
    }
}
