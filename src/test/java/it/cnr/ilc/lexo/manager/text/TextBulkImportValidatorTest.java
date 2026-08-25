package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextBulkImportValidatorTest {

    @Test
    @DisplayName("Bulk admission accepts TXT, CommonMark and fixed-schema JSON file names")
    void acceptsTextCommonMarkAndJsonFiles() {
        assertThatCode(() -> TextBulkImportValidator.requireSupportedFileName("one.txt"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TextBulkImportValidator.requireSupportedFileName("two.MD"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TextBulkImportValidator.requireSupportedFileName("three.markdown"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TextBulkImportValidator.requireSupportedFileName("four.JSON"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Any CoNLL-U file or multipart field rejects the complete bulk")
    void rejectsConlluWithStableCode() {
        assertThatThrownBy(() ->
                TextBulkImportValidator.requireSupportedFileName("tokens.conllu"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("BULK_CONLLU_NOT_ALLOWED:");
        assertThatThrownBy(() -> TextBulkImportValidator.rejectConlluPart(true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("BULK_CONLLU_NOT_ALLOWED:");
    }

    @Test
    @DisplayName("Missing, excessive and unsupported bulk inputs have stable codes")
    void rejectsInvalidGeneralInput() {
        assertThatThrownBy(() -> TextBulkImportValidator.requireFileCount(0, 10))
                .hasMessageStartingWith("BULK_MISSING_FILES:");
        assertThatThrownBy(() -> TextBulkImportValidator.requireFileCount(11, 10))
                .hasMessageStartingWith("BULK_TOO_MANY_FILES:");
        assertThatThrownBy(() ->
                TextBulkImportValidator.requireSupportedFileName("document.pdf"))
                .hasMessageStartingWith("BULK_UNSUPPORTED_FILE_TYPE:");
    }

    @Test
    @DisplayName("corpusId is forbidden for JSON-only bulks and remains available to text items")
    void restrictsTheQueryCorpusForJsonOnlyBulks() {
        assertThatThrownBy(() -> TextBulkImportValidator.requireAllowedCorpusParameter(
                true, false, "corpus-a"))
                .hasMessageStartingWith("CORPUS_ID_NOT_ALLOWED_FOR_JSON:");
        assertThatCode(() -> TextBulkImportValidator.requireAllowedCorpusParameter(
                true, true, "corpus-a"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TextBulkImportValidator.requireAllowedCorpusParameter(
                true, false, null))
                .doesNotThrowAnyException();
    }
}
