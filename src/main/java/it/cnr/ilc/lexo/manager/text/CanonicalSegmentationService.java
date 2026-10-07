package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.manager.text.model.Heading;
import it.cnr.ilc.lexo.manager.text.model.Paragraph;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.SegmentationSource;
import it.cnr.ilc.lexo.manager.text.model.Sentence;
import it.cnr.ilc.lexo.manager.text.model.TitleSegment;
import it.cnr.ilc.lexo.manager.text.model.Token;
import java.io.IOException;
import java.io.StringReader;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;

/** Builds the default language-independent Lucene-driven segmentation. */
public final class CanonicalSegmentationService {

    public static final String TOKENIZER_PROFILE = "LUCENE_STANDARD_8_11";
    public static final String SENTENCE_PROFILE = "UNICODE_SENTENCE_JAVA8";

    public void applyLuceneStandard(ParsedTextDocument document) {
        if (document == null || document.cleanText == null) {
            throw new IllegalArgumentException("Canonical text is required");
        }
        clear(document);
        Locale locale = localeFor(document.metadata.get("language"));
        Counter counter = new Counter();
        List<SegmentRange> ranges = new ArrayList<SegmentRange>();
        for (Heading heading : document.allHeadings) {
            TitleSegment title = heading.titleSegment;
            ranges.add(new SegmentRange(title.beginChar, title.endChar, null,
                    heading, true));
        }
        for (Paragraph paragraph : document.paragraphs) {
            ranges.add(new SegmentRange(paragraph.beginChar, paragraph.endChar,
                    paragraph, null, false));
        }
        Collections.sort(ranges, new Comparator<SegmentRange>() {
            @Override
            public int compare(SegmentRange left, SegmentRange right) {
                return Integer.compare(left.begin, right.begin);
            }
        });
        for (SegmentRange range : ranges) {
            segmentRange(document, range.begin, range.end, range.paragraph,
                    range.heading, range.headingTitle, locale, counter);
        }
        document.segmentationSource = SegmentationSource.LUCENE_STANDARD;
        document.segmentationMethod = "lucene-standard";
        document.tokenizerProfile = TOKENIZER_PROFILE;
        document.sentenceSplitterProfile = SENTENCE_PROFILE;
        validate(document);
        SegmentationFingerprint.complete(document);
    }

    private void segmentRange(ParsedTextDocument document, int rangeBegin, int rangeEnd,
                              Paragraph paragraph, Heading heading,
                              boolean headingTitle, Locale locale, Counter counter) {
        String range = document.cleanText.substring(rangeBegin, rangeEnd);
        BreakIterator sentences = BreakIterator.getSentenceInstance(locale);
        sentences.setText(range);
        int relativeBegin = sentences.first();
        for (int relativeEnd = sentences.next(); relativeEnd != BreakIterator.DONE;
             relativeBegin = relativeEnd, relativeEnd = sentences.next()) {
            int[] trimmed = trim(document.cleanText, rangeBegin + relativeBegin,
                    rangeBegin + relativeEnd);
            if (trimmed[0] >= trimmed[1]) {
                continue;
            }
            Sentence sentence = new Sentence();
            sentence.ordinal = ++counter.sentences;
            sentence.id = "sentence-" + sentence.ordinal;
            sentence.beginChar = trimmed[0];
            sentence.endChar = trimmed[1];
            sentence.text = document.cleanText.substring(trimmed[0], trimmed[1]);
            sentence.paragraph = paragraph;
            sentence.heading = heading;
            sentence.inHeadingTitle = headingTitle;
            tokenize(document, sentence, counter);
            if (paragraph != null) {
                paragraph.sentences.add(sentence);
            } else if (heading != null) {
                heading.titleSentences.add(sentence);
            }
            document.sentences.add(sentence);
        }
    }

    private void tokenize(ParsedTextDocument document, Sentence sentence, Counter counter) {
        StandardTokenizer tokenizer = new StandardTokenizer();
        try {
            tokenizer.setReader(new StringReader(sentence.text));
            OffsetAttribute offsets = tokenizer.addAttribute(OffsetAttribute.class);
            CharTermAttribute terms = tokenizer.addAttribute(CharTermAttribute.class);
            tokenizer.reset();
            while (tokenizer.incrementToken()) {
                Token token = new Token();
                token.ordinal = ++counter.tokens;
                token.id = "token-" + token.ordinal;
                token.beginChar = sentence.beginChar + offsets.startOffset();
                token.endChar = sentence.beginChar + offsets.endOffset();
                token.text = document.cleanText.substring(token.beginChar, token.endChar);
                token.indexedText = terms.toString();
                token.sentence = sentence;
                sentence.tokens.add(token);
                document.tokens.add(token);
            }
            tokenizer.end();
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to tokenize canonical text", impossible);
        } finally {
            try {
                tokenizer.close();
            } catch (IOException impossible) {
                throw new IllegalStateException("Unable to close the tokenizer", impossible);
            }
        }
    }

    public static void validate(ParsedTextDocument document) {
        Set<Sentence> sentenceSet = Collections.newSetFromMap(
                new IdentityHashMap<Sentence, Boolean>());
        int previousSentenceEnd = -1;
        for (int index = 0; index < document.sentences.size(); index++) {
            Sentence sentence = document.sentences.get(index);
            if (sentence.ordinal != index + 1
                    || sentence.beginChar < previousSentenceEnd
                    || sentence.beginChar < 0
                    || sentence.endChar <= sentence.beginChar
                    || sentence.endChar > document.cleanText.length()
                    || sentence.text == null
                    || !sentence.text.equals(document.cleanText.substring(
                            sentence.beginChar, sentence.endChar))) {
                throw new IllegalArgumentException(
                        "INVALID_CANONICAL_SEGMENTATION: inconsistent sentence "
                        + (index + 1));
            }
            sentenceSet.add(sentence);
            previousSentenceEnd = sentence.endChar;
        }
        int previousEnd = -1;
        for (int index = 0; index < document.tokens.size(); index++) {
            Token token = document.tokens.get(index);
            if (token.ordinal != index + 1 || token.beginChar < previousEnd
                    || token.beginChar < 0 || token.endChar <= token.beginChar
                    || token.endChar > document.cleanText.length()
                    || token.sentence == null
                    || !sentenceSet.contains(token.sentence)
                    || token.beginChar < token.sentence.beginChar
                    || token.endChar > token.sentence.endChar
                    || !token.text.equals(document.cleanText.substring(
                            token.beginChar, token.endChar))) {
                throw new IllegalArgumentException(
                        "INVALID_CANONICAL_SEGMENTATION: inconsistent token " + (index + 1));
            }
            previousEnd = token.endChar;
        }
    }

    private static void clear(ParsedTextDocument document) {
        document.sentences.clear();
        document.tokens.clear();
        for (Paragraph paragraph : document.paragraphs) {
            paragraph.sentences.clear();
        }
        for (Heading heading : document.allHeadings) {
            heading.titleSentences.clear();
        }
    }

    private static int[] trim(String text, int begin, int end) {
        while (begin < end && Character.isWhitespace(text.codePointAt(begin))) {
            begin += Character.charCount(text.codePointAt(begin));
        }
        while (end > begin && Character.isWhitespace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return new int[]{begin, end};
    }

    private static Locale localeFor(String language) {
        if (language == null || language.trim().isEmpty()) {
            return Locale.ROOT;
        }
        Locale locale = Locale.forLanguageTag(language.trim().replace('_', '-'));
        return locale.getLanguage().isEmpty() ? Locale.ROOT : locale;
    }

    private static final class Counter {
        int sentences;
        int tokens;
    }

    private static final class SegmentRange {
        final int begin;
        final int end;
        final Paragraph paragraph;
        final Heading heading;
        final boolean headingTitle;

        SegmentRange(int begin, int end, Paragraph paragraph, Heading heading,
                     boolean headingTitle) {
            this.begin = begin;
            this.end = end;
            this.paragraph = paragraph;
            this.heading = heading;
            this.headingTitle = headingTitle;
        }
    }
}
