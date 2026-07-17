package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests CoNLL-U anchoring against the canonical plain-text representation. */
class ConlluSegmenterTest {

    private final ControlledCommonMarkParser parser = new ControlledCommonMarkParser();
    private final ConlluSegmenter segmenter = new ConlluSegmenter();

    @Test
    @DisplayName("Valid CoNLL-U offsets replace automatic segmentation")
    void appliesValidConlluSegmentation() throws Exception {
        ParsedTextDocument document = parser.parsePlainTextStructure("Mario corre.");
        segmenter.apply(document,
                "# sent_id = s1\n"
                        + "# text = Mario corre.\n"
                        + "# start_char = 0\n"
                        + "# end_char = 12\n"
                        + "1\tMario\tMario\tPROPN\t_\t_\t2\tnsubj\t_\tTokenRange=0:5\n"
                        + "2\tcorre\tcorrere\tVERB\t_\t_\t0\troot\t_\tTokenRange=6:11\n"
                        + "3\t.\t.\tPUNCT\t_\t_\t2\tpunct\t_\tTokenRange=11:12\n",
                "example.conllu");

        assertThat(document.segmentationMethod).isEqualTo("conllu");
        assertThat(document.sentences).singleElement().satisfies(sentence -> {
            assertThat(sentence.conlluSentId).isEqualTo("s1");
            assertThat(sentence.text).isEqualTo("Mario corre.");
        });
        assertThat(document.tokens).extracting(token -> token.text)
                .containsExactly("Mario", "corre", ".");
        assertThat(document.tokens.get(1).lemma).isEqualTo("correre");
    }

    @Test
    @DisplayName("A FORM that does not match its offsets is rejected")
    void rejectsTokenFormMismatch() throws Exception {
        ParsedTextDocument document = parser.parsePlainTextStructure("Mario corre.");
        String invalid = "# start_char = 0\n# end_char = 12\n"
                + "1\tLuigi\t_\tPROPN\t_\t_\t0\troot\t_\tTokenRange=0:5\n";

        assertThatThrownBy(() -> segmenter.apply(document, invalid, "invalid.conllu"))
                .isInstanceOfSatisfying(ConlluValidationException.class,
                        error -> assertThat(error.getIssues().stream()
                                .map(issue -> issue.code).collect(Collectors.toList()))
                                .contains("TOKEN_FORM_MISMATCH"));
    }

    @Test
    @DisplayName("Every ordinary token must provide character offsets")
    void rejectsMissingTokenOffsets() throws Exception {
        ParsedTextDocument document = parser.parsePlainTextStructure("Mario");
        String invalid = "1\tMario\t_\tPROPN\t_\t_\t0\troot\t_\t_\n";

        assertThatThrownBy(() -> segmenter.apply(document, invalid, "invalid.conllu"))
                .isInstanceOfSatisfying(ConlluValidationException.class,
                        error -> assertThat(error.getIssues().stream()
                                .map(issue -> issue.code).collect(Collectors.toList()))
                                .contains("MISSING_TOKEN_OFFSETS"));
    }
}
