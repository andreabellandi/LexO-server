package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.manager.text.model.Heading;
import it.cnr.ilc.lexo.manager.text.model.JsonTextImport;
import it.cnr.ilc.lexo.manager.text.model.Paragraph;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.SegmentationSource;
import it.cnr.ilc.lexo.manager.text.model.Sentence;
import it.cnr.ilc.lexo.manager.text.model.Token;

/** Validates and installs explicit JSON token/sentence code-point spans. */
public final class AnnotatedSegmentationImporter {

    public void apply(ParsedTextDocument document, JsonTextImport imported) {
        if (imported == null || imported.tokens.isEmpty() || imported.sentences.isEmpty()) {
            throw new IllegalArgumentException(
                    "INVALID_ANNOTATED_SEGMENTATION: complete tokens and sentences are required");
        }
        clear(document);
        int previousSentenceEnd = -1;
        for (int index = 0; index < imported.sentences.size(); index++) {
            JsonTextImport.AnnotatedSentence input = imported.sentences.get(index);
            int begin = utf16(document.cleanText, input.start, input.end)[0];
            int end = utf16(document.cleanText, input.start, input.end)[1];
            if (begin < previousSentenceEnd) {
                fail("sentences must be ordered and non-overlapping");
            }
            Sentence sentence = new Sentence();
            sentence.ordinal = index + 1;
            sentence.id = "sentence-" + sentence.ordinal;
            sentence.beginChar = begin;
            sentence.endChar = end;
            sentence.text = document.cleanText.substring(begin, end);
            if (input.text != null && !input.text.equals(sentence.text)) {
                fail("sentence surface does not match canonical text at index " + index);
            }
            attach(document, sentence);
            document.sentences.add(sentence);
            previousSentenceEnd = end;
        }
        int previousTokenEnd = -1;
        for (int index = 0; index < imported.tokens.size(); index++) {
            JsonTextImport.AnnotatedToken input = imported.tokens.get(index);
            int[] offsets = utf16(document.cleanText, input.start, input.end);
            if (offsets[0] < previousTokenEnd) {
                fail("tokens must be ordered and non-overlapping");
            }
            if (input.sentence == null || input.sentence.intValue() < 0
                    || input.sentence.intValue() >= document.sentences.size()) {
                fail("token sentence index is invalid at token " + index);
            }
            Sentence sentence = document.sentences.get(input.sentence.intValue());
            if (offsets[0] < sentence.beginChar || offsets[1] > sentence.endChar) {
                fail("token is outside its sentence at token " + index);
            }
            Token token = new Token();
            token.ordinal = index + 1;
            token.id = "token-" + token.ordinal;
            token.beginChar = offsets[0];
            token.endChar = offsets[1];
            token.text = document.cleanText.substring(offsets[0], offsets[1]);
            token.indexedText = token.text;
            token.lemma = blankToNull(input.lemma);
            token.upos = blankToNull(input.pos);
            token.sentence = sentence;
            if (input.text != null && !input.text.equals(token.text)) {
                fail("token surface does not match canonical text at token " + index);
            }
            sentence.tokens.add(token);
            document.tokens.add(token);
            previousTokenEnd = offsets[1];
        }
        document.segmentationSource = SegmentationSource.ANNOTATED_IMPORT;
        document.segmentationMethod = "annotated-import";
        document.tokenizerProfile = "ANNOTATED_IMPORT_SPANS";
        document.sentenceSplitterProfile = "ANNOTATED_IMPORT_SPANS";
        CanonicalSegmentationService.validate(document);
        SegmentationFingerprint.complete(document);
    }

    private static int[] utf16(String text, int begin, int end) {
        if (begin < 0 || end <= begin
                || end > text.codePointCount(0, text.length())) {
            fail("invalid code-point span " + begin + ":" + end);
        }
        return new int[]{UnicodeOffsetMapper.codePointToUtf16(text, begin),
            UnicodeOffsetMapper.codePointToUtf16(text, end)};
    }

    private static void attach(ParsedTextDocument document, Sentence sentence) {
        for (Paragraph paragraph : document.paragraphs) {
            if (sentence.beginChar >= paragraph.beginChar
                    && sentence.endChar <= paragraph.endChar) {
                sentence.paragraph = paragraph;
                paragraph.sentences.add(sentence);
                return;
            }
        }
        for (Heading heading : document.allHeadings) {
            if (sentence.beginChar >= heading.titleSegment.beginChar
                    && sentence.endChar <= heading.titleSegment.endChar) {
                sentence.heading = heading;
                sentence.inHeadingTitle = true;
                heading.titleSentences.add(sentence);
                return;
            }
        }
        fail("sentence is outside the canonical document structure");
    }

    private static void clear(ParsedTextDocument document) {
        document.tokens.clear();
        document.sentences.clear();
        for (Paragraph paragraph : document.paragraphs) {
            paragraph.sentences.clear();
        }
        for (Heading heading : document.allHeadings) {
            heading.titleSentences.clear();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(
                "INVALID_ANNOTATED_SEGMENTATION: " + message);
    }
}
