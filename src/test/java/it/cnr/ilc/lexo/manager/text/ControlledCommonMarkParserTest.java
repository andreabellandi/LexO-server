package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.ValidationIssue;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Executable specification of the two accepted text dialects.
 *
 * <p>These tests deliberately keep plain TXT and controlled CommonMark separate:
 * the regression that originally produced TEXT_OUTSIDE_HEADING was caused by
 * treating every .txt file as controlled CommonMark.</p>
 */
class ControlledCommonMarkParserTest {

    private final ControlledCommonMarkParser parser = new ControlledCommonMarkParser();

    @Test
    @DisplayName("A plain TXT does not require a CommonMark heading")
    void parsesPlainTextWithoutHeading() throws Exception {
        ParsedTextDocument document = parser.parsePlainText(
                "Prima riga del testo.\nSeconda riga dello stesso paragrafo.\n\nSecondo paragrafo.");

        assertThat(document.cleanText)
                .isEqualTo("Prima riga del testo. Seconda riga dello stesso paragrafo.\n\nSecondo paragrafo.");
        assertThat(document.paragraphs).hasSize(2);
        assertThat(document.sentences).isNotEmpty();
        assertThat(document.tokens).isNotEmpty();
        assertThat(document.segmentationMethod).isEqualTo("break-iterator");
    }

    @Test
    @DisplayName("The service can recognize controlled CommonMark by its content")
    void detectsControlledCommonMarkHeading() {
        assertThat(parser.hasControlledCommonMarkHeading("Testo semplice")).isFalse();
        assertThat(parser.hasControlledCommonMarkHeading("# [id=chapter-1] Capitolo\nTesto"))
                .isTrue();
    }

    @Test
    @DisplayName("Controlled CommonMark accepts text inside a chapter")
    void parsesValidControlledCommonMark() throws Exception {
        ParsedTextDocument document = parser.parse(
                "# [id=chapter-1; n=1] Capitolo primo\nTesto del capitolo.\n\n"
                        + "## [id=section-1; n=1.1] Sezione\nAltro testo.");

        assertThat(document.allHeadings).hasSize(2);
        assertThat(document.rootHeadings).hasSize(1);
        assertThat(document.paragraphs).hasSize(2);
        assertThat(document.allHeadings.get(1).parent).isSameAs(document.allHeadings.get(0));
    }

    @Test
    @DisplayName("Controlled CommonMark still rejects text outside a heading")
    void rejectsTextOutsideHeading() {
        assertThatThrownBy(() -> parser.parse("Testo prima del capitolo\n# [id=c1] Capitolo"))
                .isInstanceOfSatisfying(ControlledCommonMarkException.class,
                        error -> assertThat(issueCodes(error.getIssues()))
                                .contains("TEXT_OUTSIDE_HEADING"));
    }

    @Test
    @DisplayName("An invalid CommonMark heading reports structured validation codes")
    void reportsInvalidHeadingAndMissingHeading() {
        assertThatThrownBy(() -> parser.parse("# Titolo senza attributo id\nTesto"))
                .isInstanceOfSatisfying(ControlledCommonMarkException.class,
                        error -> assertThat(issueCodes(error.getIssues()))
                                .contains("INVALID_HEADING", "MISSING_HEADING"));
    }

    @Test
    @DisplayName("Front matter retains multiple values and ignores unknown keys")
    void parsesMultipleMetadataValuesAndIgnoresUnknownKeys() throws Exception {
        ParsedTextDocument document = parser.parsePlainText(
                "---\n"
                        + "title: Storia della lessicografia\n"
                        + "author:\n"
                        + "  - Mario Rossi\n"
                        + "  - https://example.org/people/bianchi\n"
                        + "unknown: ignored\n"
                        + "  - also ignored\n"
                        + "language: it\n"
                        + "---\n"
                        + "Testo del documento.");

        assertThat(document.frontMatterPresent).isTrue();
        assertThat(document.metadataValues.get("author"))
                .containsExactly("Mario Rossi", "https://example.org/people/bianchi");
        assertThat(document.metadataValues).doesNotContainKey("unknown");
        assertThat(document.cleanText).isEqualTo("Testo del documento.");
    }

    @Test
    @DisplayName("A corpus descriptor contains front matter only")
    void parsesMetadataOnlyCorpusDescriptor() throws Exception {
        ParsedTextDocument document = parser.parseMetadataOnly(
                "---\nid: https://example.org/corpus/one\ntitle: Corpus uno\n---\n");

        assertThat(document.cleanText).isEmpty();
        assertThat(document.segmentationMethod).isEqualTo("metadata-only");
        assertThat(document.metadataValues).containsKeys("id", "title");
    }

    @Test
    @DisplayName("A corpus descriptor rejects body text")
    void rejectsTextInCorpusDescriptor() {
        assertThatThrownBy(() -> parser.parseMetadataOnly(
                "---\ntitle: Corpus uno\n---\nQuesto testo non è ammesso"))
                .isInstanceOfSatisfying(ControlledCommonMarkException.class,
                        error -> assertThat(issueCodes(error.getIssues()))
                                .contains("TEXT_IN_CORPUS_DESCRIPTOR"));
    }

    @Test
    @DisplayName("Unclosed front matter has a stable machine-readable error code")
    void rejectsUnclosedFrontMatter() {
        assertThatThrownBy(() -> parser.parsePlainText("---\ntitle: Documento\nTesto"))
                .isInstanceOfSatisfying(ControlledCommonMarkException.class,
                        error -> assertThat(issueCodes(error.getIssues()))
                                .contains("UNCLOSED_FRONT_MATTER"));
    }

    @Test
    @DisplayName("NUL characters are rejected")
    void rejectsNulCharacters() {
        assertThatThrownBy(() -> parser.parsePlainText("testo\u0000non valido"))
                .isInstanceOfSatisfying(ControlledCommonMarkException.class,
                        error -> assertThat(issueCodes(error.getIssues()))
                                .contains("NUL_CHARACTER"));
    }

    private static List<String> issueCodes(List<ValidationIssue> issues) {
        return issues.stream().map(issue -> issue.code).collect(Collectors.toList());
    }
}
