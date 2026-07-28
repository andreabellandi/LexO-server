package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies all three ISO 639 code columns used by text upload validation. */
class Iso639LanguageValidatorTest {

    private final Iso639LanguageValidator validator = Iso639LanguageValidator.get();

    @Test
    @DisplayName("ISO 639-1, bibliographic, terminologic and ISO 639-3 codes are accepted")
    void acceptsCodesFromEachSupportedColumn() {
        assertThat(validator.requireValid("it")).isEqualTo("it");
        assertThat(validator.requireValid("GER")).isEqualTo("ger");
        assertThat(validator.requireValid("deu")).isEqualTo("deu");
        assertThat(validator.requireValid("lld")).isEqualTo("lld");
    }

    @Test
    @DisplayName("A missing text language has a stable error code")
    void rejectsMissingLanguage() {
        assertThatThrownBy(() -> validator.requireValid("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("MISSING_LANGUAGE:");
    }

    @Test
    @DisplayName("A code outside the bundled ISO list has a stable error code")
    void rejectsUnknownLanguage() {
        assertThatThrownBy(() -> validator.requireValid("not-a-language"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_LANGUAGE:");
        assertThatThrownBy(() -> validator.requireValid("Italian"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_LANGUAGE:");
    }
}
