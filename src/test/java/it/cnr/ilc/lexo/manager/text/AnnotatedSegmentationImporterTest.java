package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.manager.text.model.JsonTextImport;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.SegmentationSource;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnnotatedSegmentationImporterTest {

    private final TextJsonImportParser json = new TextJsonImportParser();
    private final ControlledCommonMarkParser text = new ControlledCommonMarkParser();

    @Test
    @DisplayName("Explicit JSON code-point spans survive Unicode conversion unchanged")
    void installsExplicitUnicodeTokenAndSentenceSpans() throws Exception {
        JsonTextImport imported = json.parse("{"
                + "\"text\":{\"type\":\"txt\",\"content\":\"😀 humanitas.\"},"
                + "\"segmentation\":{"
                + "\"sentences\":[{\"start_char\":0,\"end_char\":12}],"
                + "\"tokens\":["
                + "{\"start_char\":0,\"end_char\":1,\"sentence\":0,\"text\":\"😀\"},"
                + "{\"start_char\":2,\"end_char\":11,\"sentence\":0,"
                + "\"text\":\"humanitas\",\"lemma\":\"humanitas\",\"pos\":\"NOUN\"},"
                + "{\"start_char\":11,\"end_char\":12,\"sentence\":0,\"text\":\".\"}]}}"
        );
        ParsedTextDocument document = text.parseJsonTextStructure(imported.content);
        new AnnotatedSegmentationImporter().apply(document, imported);

        assertThat(document.segmentationSource)
                .isEqualTo(SegmentationSource.ANNOTATED_IMPORT);
        assertThat(document.tokens.stream().map(token -> token.text)
                .collect(Collectors.toList()))
                .containsExactly("😀", "humanitas", ".");
        assertThat(document.tokens.get(1).beginChar).isEqualTo(3);
        assertThat(UnicodeOffsetMapper.utf16ToCodePoint(document.cleanText,
                document.tokens.get(1).beginChar)).isEqualTo(2);
        assertThat(document.tokens.get(1).lemma).isEqualTo("humanitas");
        assertThat(document.contentHash).isNotBlank();
        assertThat(document.segmentationHash).isNotBlank();
    }

    @Test
    @DisplayName("Incorrect annotation offsets are rejected, never repaired silently")
    void rejectsOffsetsThatDoNotMatchCanonicalText() throws Exception {
        JsonTextImport imported = json.parse("{"
                + "\"text\":{\"type\":\"txt\",\"content\":\"uno due\"},"
                + "\"segmentation\":{"
                + "\"sentences\":[{\"start_char\":0,\"end_char\":7}],"
                + "\"tokens\":[{\"start_char\":0,\"end_char\":3,"
                + "\"sentence\":0,\"text\":\"due\"}]}}"
        );
        ParsedTextDocument document = text.parseJsonTextStructure(imported.content);

        assertThatThrownBy(() -> new AnnotatedSegmentationImporter()
                .apply(document, imported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_ANNOTATED_SEGMENTATION:");
    }
}
