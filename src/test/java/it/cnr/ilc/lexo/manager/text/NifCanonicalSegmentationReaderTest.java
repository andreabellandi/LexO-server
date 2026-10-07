package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.manager.text.NifCanonicalSegmentationReader.ReadResult;
import it.cnr.ilc.lexo.manager.text.model.JsonTextImport;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.SegmentationSource;
import java.util.stream.Collectors;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NifCanonicalSegmentationReaderTest {

    private static final String BASE = "https://example.org/texts/";
    private static final String STRUCTURE = "https://example.org/structure#";
    private final ControlledCommonMarkParser parser = new ControlledCommonMarkParser();

    @Test
    @DisplayName("Reindex reconstruction preserves CoNLL-U positions and source")
    void reconstructsConlluWithoutStandardRetokenization() throws Exception {
        ParsedTextDocument original = parser.parsePlainTextStructure("Mario corre.");
        new ConlluSegmenter().apply(original,
                "# sent_id = s1\n# text = Mario corre.\n"
                        + "# start_char = 0\n# end_char = 12\n"
                        + "1\tMario\tMario\tPROPN\t_\t_\t2\tnsubj\t_\tTokenRange=0:5\n"
                        + "2\tcorre\tcorrere\tVERB\t_\t_\t0\troot\t_\tTokenRange=6:11\n"
                        + "3\t.\t.\tPUNCT\t_\t_\t2\tpunct\t_\tTokenRange=11:12\n",
                "mario.conllu");

        ReadResult rebuilt = roundTrip("conllu", original);
        assertEquivalent(original, rebuilt.document, SegmentationSource.CONLLU);
    }

    @Test
    @DisplayName("Reindex reconstruction preserves imported annotation positions and source")
    void reconstructsAnnotatedSegmentationWithoutStandardRetokenization() throws Exception {
        JsonTextImport imported = new TextJsonImportParser().parse("{"
                + "\"text\":{\"type\":\"txt\",\"content\":\"New-York\"},"
                + "\"segmentation\":{"
                + "\"sentences\":[{\"start_char\":0,\"end_char\":8}],"
                + "\"tokens\":[{\"start_char\":0,\"end_char\":8,"
                + "\"sentence\":0,\"text\":\"New-York\"}]}}"
        );
        ParsedTextDocument original = parser.parseJsonTextStructure(imported.content);
        new AnnotatedSegmentationImporter().apply(original, imported);

        ReadResult rebuilt = roundTrip("annotated", original);
        assertEquivalent(original, rebuilt.document,
                SegmentationSource.ANNOTATED_IMPORT);
        assertThat(rebuilt.document.tokens).extracting(token -> token.text)
                .containsExactly("New-York");
    }

    @Test
    @DisplayName("Legacy NIF without provenance keeps persisted spans")
    void reconstructsLegacyPersistedNifWithoutRetokenization() throws Exception {
        ParsedTextDocument original = parser.parsePlainText("New-York resta.");
        Model nif = new NifModelWriter(BASE, STRUCTURE)
                .build("legacy", "legacy.txt", original);
        remove(nif, "segmentationSource");
        remove(nif, "segmentationMethod");
        remove(nif, "tokenizerProfile");
        remove(nif, "sentenceSplitterProfile");
        remove(nif, "segmentationSchemaVersion");
        remove(nif, "contentHash");
        remove(nif, "segmentationHash");

        ReadResult rebuilt = new NifCanonicalSegmentationReader(STRUCTURE).read(
                "legacy", "https://example.org/graphs/legacy", nif);

        assertThat(rebuilt.document.segmentationSource)
                .isEqualTo(SegmentationSource.PERSISTED_NIF);
        assertThat(rebuilt.document.tokens).extracting(token -> token.text)
                .containsExactlyElementsOf(original.tokens.stream()
                        .map(token -> token.text).collect(Collectors.toList()));
        assertThat(rebuilt.document.contentHash).isNotBlank();
        assertThat(rebuilt.document.segmentationHash).isNotBlank();
    }

    private static ReadResult roundTrip(String id, ParsedTextDocument original) {
        Model nif = new NifModelWriter(BASE, STRUCTURE)
                .build(id, id + ".txt", original);
        return new NifCanonicalSegmentationReader(STRUCTURE).read(id,
                "https://example.org/graphs/" + id, nif);
    }

    private static void remove(Model model, String localName) {
        IRI predicate = SimpleValueFactory.getInstance()
                .createIRI(STRUCTURE + localName);
        model.removeIf(statement -> statement.getPredicate().equals(predicate));
    }

    private static void assertEquivalent(ParsedTextDocument original,
                                         ParsedTextDocument rebuilt,
                                         SegmentationSource source) {
        assertThat(rebuilt.segmentationSource).isEqualTo(source);
        assertThat(rebuilt.contentHash).isEqualTo(original.contentHash);
        assertThat(rebuilt.segmentationHash).isEqualTo(original.segmentationHash);
        assertThat(rebuilt.tokens.stream().map(token -> token.text)
                .collect(Collectors.toList()))
                .containsExactlyElementsOf(original.tokens.stream()
                        .map(token -> token.text).collect(Collectors.toList()));
        assertThat(rebuilt.tokens).extracting(token -> token.beginChar)
                .containsExactlyElementsOf(original.tokens.stream()
                        .map(token -> token.beginChar).collect(Collectors.toList()));
    }
}
