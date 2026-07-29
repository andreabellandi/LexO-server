package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextBulkImportValidatorTest {

    @Test
    @DisplayName("Bulk admission accepts only TXT and CommonMark file names")
    void acceptsTextAndCommonMarkFiles() {
        assertThatCode(() -> TextBulkImportValidator.requireSupportedFileName("one.txt"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TextBulkImportValidator.requireSupportedFileName("two.MD"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TextBulkImportValidator.requireSupportedFileName("three.markdown"))
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
}
