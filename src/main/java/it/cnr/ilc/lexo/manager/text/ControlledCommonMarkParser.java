package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.manager.text.model.Heading;
import it.cnr.ilc.lexo.manager.text.model.Paragraph;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.Sentence;
import it.cnr.ilc.lexo.manager.text.model.TitleSegment;
import it.cnr.ilc.lexo.manager.text.model.Token;
import it.cnr.ilc.lexo.manager.text.model.ValidationIssue;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the deterministic CommonMark subset used by the corpus service.
 * It recognizes optional flat front matter, attributed headings, and paragraphs.
 */
public final class ControlledCommonMarkParser {

    private static final Pattern HEADING = Pattern.compile(
            "^(#{1,6})\\s+\\[id=([A-Za-z][A-Za-z0-9._-]*)(?:;\\s*n=([^\\]]+))?\\]\\s+(.+?)\\s*$");
    private static final Pattern META = Pattern.compile("^([A-Za-z][A-Za-z0-9_.-]*)\\s*:\\s*(.*)$");
    private static final Pattern META_LIST_ITEM = Pattern.compile("^\\s*-\\s+(.+?)\\s*$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^\\s*\\d+[.)]\\s+.*$");

    public ParsedTextDocument parse(String rawText) throws ControlledCommonMarkException {
        ParsedTextDocument doc = parseStructure(rawText);
        segmentWithBreakIterator(doc);
        return doc;
    }

    /**
     * Parses an unstructured UTF-8 text file. Blank lines delimit paragraphs;
     * CommonMark headings and front matter are not required or interpreted.
     */
    public ParsedTextDocument parsePlainText(String rawText) throws ControlledCommonMarkException {
        ParsedTextDocument doc = parsePlainTextStructure(rawText);
        segmentWithBreakIterator(doc);
        return doc;
    }

    /** Parses a corpus descriptor made exclusively of a front-matter block. */
    public ParsedTextDocument parseMetadataOnly(String rawText)
            throws ControlledCommonMarkException {
        List<ValidationIssue> issues = new ArrayList<ValidationIssue>();
        if (rawText == null) {
            issues.add(new ValidationIssue(1, 1, "EMPTY_DOCUMENT", "Documento assente"));
            throw new ControlledCommonMarkException(issues);
        }
        String source = normalizeText(rawText);
        if (source.indexOf('\u0000') >= 0) {
            issues.add(new ValidationIssue(1, 1, "NUL_CHARACTER",
                    "Il file contiene un carattere NUL"));
        }
        String[] lines = source.split("\\n", -1);
        ParsedTextDocument doc = new ParsedTextDocument();
        int contentStart = parseOptionalFrontMatter(lines, doc, issues);
        if (!doc.frontMatterPresent) {
            issues.add(new ValidationIssue(1, 1, "MISSING_FRONT_MATTER",
                    "Il corpus richiede un header di metadati delimitato da ---"));
        }
        for (int i = contentStart; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                issues.add(new ValidationIssue(i + 1, 1, "TEXT_IN_CORPUS_DESCRIPTOR",
                        "Il file del corpus deve contenere solo metadati"));
            }
        }
        if (doc.metadataValues.isEmpty()) {
            issues.add(new ValidationIssue(1, 1, "MISSING_METADATA",
                    "È richiesto almeno un metadato ammesso"));
        }
        if (!issues.isEmpty()) {
            throw new ControlledCommonMarkException(issues);
        }
        doc.cleanText = "";
        doc.segmentationMethod = "metadata-only";
        return doc;
    }

    /**
     * Parses only the paragraph structure of an unstructured text file.
     * Linguistic segmentation can then be supplied by an optional CoNLL-U file.
     */
    public ParsedTextDocument parsePlainTextStructure(String rawText)
            throws ControlledCommonMarkException {
        List<ValidationIssue> issues = new ArrayList<ValidationIssue>();
        if (rawText == null) {
            issues.add(new ValidationIssue(1, 1, "EMPTY_DOCUMENT", "Documento assente"));
            throw new ControlledCommonMarkException(issues);
        }

        String source = normalizeText(rawText);
        if (source.indexOf('\u0000') >= 0) {
            issues.add(new ValidationIssue(1, 1, "NUL_CHARACTER",
                    "Il file contiene un carattere NUL"));
        }

        String[] lines = source.split("\\n", -1);
        ParsedTextDocument doc = new ParsedTextDocument();
        int contentStart = parseOptionalFrontMatter(lines, doc, issues);
        StringBuilder clean = new StringBuilder();
        List<String> paragraphLines = new ArrayList<String>();
        int paragraphOrdinal = 0;

        for (int i = contentStart; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                paragraphOrdinal = flushPlainParagraph(paragraphLines, clean, doc,
                        paragraphOrdinal);
            } else {
                paragraphLines.add(line);
            }
        }
        flushPlainParagraph(paragraphLines, clean, doc, paragraphOrdinal);

        if (doc.paragraphs.isEmpty()) {
            issues.add(new ValidationIssue(1, 1, "EMPTY_DOCUMENT",
                    "Il documento di testo è vuoto"));
        }
        if (!issues.isEmpty()) {
            throw new ControlledCommonMarkException(issues);
        }

        doc.cleanText = clean.toString();
        return doc;
    }

    /**
     * Returns true when the input declares CommonMark heading syntax.
     * The import service uses this content check instead of relying on the
     * multipart filename, which may be rewritten by a client.
     */
    public boolean hasControlledCommonMarkHeading(String rawText) {
        if (rawText == null) {
            return false;
        }
        String source = normalizeText(rawText);
        String[] lines = source.split("\\n", -1);
        for (String line : lines) {
            if (line.startsWith("#")) {
                return true;
            }
        }
        return false;
    }

    public ParsedTextDocument parseStructure(String rawText) throws ControlledCommonMarkException {
        List<ValidationIssue> issues = new ArrayList<ValidationIssue>();
        if (rawText == null) {
            issues.add(new ValidationIssue(1, 1, "EMPTY_DOCUMENT", "Documento assente"));
            throw new ControlledCommonMarkException(issues);
        }

        String source = normalizeText(rawText);
        if (source.indexOf('\u0000') >= 0) {
            issues.add(new ValidationIssue(1, 1, "NUL_CHARACTER", "Il file contiene un carattere NUL"));
        }

        String[] lines = source.split("\\n", -1);
        ParsedTextDocument doc = new ParsedTextDocument();
        int contentStart = parseOptionalFrontMatter(lines, doc, issues);

        StringBuilder clean = new StringBuilder();
        Deque<Heading> stack = new ArrayDeque<Heading>();
        Set<String> headingIds = new HashSet<String>();
        List<String> paragraphLines = new ArrayList<String>();
        int paragraphStartLine = -1;
        int previousHeadingLevel = 0;
        int paragraphOrdinal = 0;

        for (int i = contentStart; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = i + 1;

            if (line.indexOf('\t') >= 0) {
                issues.add(new ValidationIssue(lineNo, line.indexOf('\t') + 1,
                        "TAB_NOT_ALLOWED", "Le tabulazioni non sono ammesse nel testo strutturato"));
            }

            Matcher headingMatcher = HEADING.matcher(line);
            if (headingMatcher.matches()) {
                paragraphOrdinal = flushParagraph(paragraphLines, paragraphStartLine, stack,
                        clean, doc, paragraphOrdinal);
                paragraphStartLine = -1;

                int level = headingMatcher.group(1).length();
                String id = headingMatcher.group(2);
                String number = trimToNull(headingMatcher.group(3));
                String title = headingMatcher.group(4).trim();

                if (doc.allHeadings.isEmpty() && level != 1) {
                    issues.add(new ValidationIssue(lineNo, 1, "FIRST_HEADING_NOT_CHAPTER",
                            "La prima intestazione deve essere di livello 1 (#)"));
                }
                if (previousHeadingLevel > 0 && level > previousHeadingLevel + 1) {
                    issues.add(new ValidationIssue(lineNo, 1, "HEADING_LEVEL_JUMP",
                            "Salto di livello non consentito: da " + previousHeadingLevel + " a " + level));
                }
                if (!headingIds.add(id)) {
                    issues.add(new ValidationIssue(lineNo, 1, "DUPLICATE_HEADING_ID",
                            "Identificatore di intestazione duplicato: " + id));
                }
                if (title.isEmpty()) {
                    issues.add(new ValidationIssue(lineNo, 1, "EMPTY_HEADING_TITLE",
                            "Il titolo dell'intestazione non può essere vuoto"));
                }

                while (!stack.isEmpty() && stack.peek().level >= level) {
                    stack.pop().endChar = clean.length();
                }

                appendBlockSeparator(clean);
                int titleBegin = clean.length();
                clean.append(title);
                int titleEnd = clean.length();

                Heading heading = new Heading();
                heading.id = id;
                heading.number = number;
                heading.text = title;
                heading.level = level;
                heading.beginChar = titleBegin;
                heading.parent = stack.peek();

                TitleSegment titleSegment = new TitleSegment();
                titleSegment.id = id + "-title";
                titleSegment.text = title;
                titleSegment.beginChar = titleBegin;
                titleSegment.endChar = titleEnd;
                titleSegment.heading = heading;
                heading.titleSegment = titleSegment;

                if (heading.parent == null) {
                    doc.rootHeadings.add(heading);
                } else {
                    heading.parent.children.add(heading);
                }
                doc.allHeadings.add(heading);
                stack.push(heading);
                previousHeadingLevel = level;
                continue;
            }

            if (line.startsWith("#")) {
                paragraphOrdinal = flushParagraph(paragraphLines, paragraphStartLine, stack,
                        clean, doc, paragraphOrdinal);
                paragraphStartLine = -1;
                issues.add(new ValidationIssue(lineNo, 1, "INVALID_HEADING",
                        "Intestazione non valida. Forma richiesta: ## [id=sec-1; n=1.1] Titolo"));
                continue;
            }

            if (line.trim().isEmpty()) {
                paragraphOrdinal = flushParagraph(paragraphLines, paragraphStartLine, stack,
                        clean, doc, paragraphOrdinal);
                paragraphStartLine = -1;
                continue;
            }

            if (isUnsupportedBlock(line)) {
                issues.add(new ValidationIssue(lineNo, firstNonWhitespaceColumn(line),
                        "UNSUPPORTED_BLOCK", "Sono ammessi solo intestazioni e paragrafi di testo"));
            }
            if (stack.isEmpty()) {
                issues.add(new ValidationIssue(lineNo, 1, "TEXT_OUTSIDE_HEADING",
                        "Il testo deve appartenere a un capitolo o a una sua sotto-intestazione"));
            }

            if (paragraphLines.isEmpty()) {
                paragraphStartLine = lineNo;
            }
            paragraphLines.add(unescapePlainLine(line));
        }

        flushParagraph(paragraphLines, paragraphStartLine, stack, clean, doc, paragraphOrdinal);
        while (!stack.isEmpty()) {
            stack.pop().endChar = clean.length();
        }

        if (doc.allHeadings.isEmpty()) {
            issues.add(new ValidationIssue(Math.max(1, contentStart + 1), 1, "MISSING_HEADING",
                    "È richiesta almeno un'intestazione di livello 1"));
        }
        if (!issues.isEmpty()) {
            throw new ControlledCommonMarkException(issues);
        }

        doc.cleanText = clean.toString();
        return doc;
    }

    public void segmentWithBreakIterator(ParsedTextDocument doc) {
        clearSegmentation(doc);
        Locale locale = localeFor(doc.metadata.get("language"));
        Counter counter = new Counter();

        for (Heading heading : doc.allHeadings) {
            TitleSegment title = heading.titleSegment;
            segmentRange(doc, title.beginChar, title.endChar, null, heading, true, locale, counter);
        }
        for (Paragraph paragraph : doc.paragraphs) {
            segmentRange(doc, paragraph.beginChar, paragraph.endChar, paragraph, null, false, locale, counter);
        }
        doc.segmentationMethod = "break-iterator";
    }

    private static void segmentRange(ParsedTextDocument doc, int rangeBegin, int rangeEnd,
                                     Paragraph paragraph, Heading heading, boolean inHeadingTitle,
                                     Locale locale, Counter counter) {
        String segment = doc.cleanText.substring(rangeBegin, rangeEnd);
        BreakIterator sentenceIterator = BreakIterator.getSentenceInstance(locale);
        sentenceIterator.setText(segment);
        int relativeStart = sentenceIterator.first();
        for (int relativeEnd = sentenceIterator.next(); relativeEnd != BreakIterator.DONE;
             relativeStart = relativeEnd, relativeEnd = sentenceIterator.next()) {
            int begin = rangeBegin + relativeStart;
            int end = rangeBegin + relativeEnd;
            int[] trimmed = trimWhitespace(doc.cleanText, begin, end);
            begin = trimmed[0];
            end = trimmed[1];
            if (begin >= end) {
                continue;
            }

            Sentence sentence = new Sentence();
            sentence.ordinal = ++counter.sentences;
            sentence.id = "sentence-" + sentence.ordinal;
            sentence.beginChar = begin;
            sentence.endChar = end;
            sentence.text = doc.cleanText.substring(begin, end);
            sentence.paragraph = paragraph;
            sentence.heading = heading;
            sentence.inHeadingTitle = inHeadingTitle;

            BreakIterator wordIterator = BreakIterator.getWordInstance(locale);
            wordIterator.setText(sentence.text);
            int wordStart = wordIterator.first();
            for (int wordEnd = wordIterator.next(); wordEnd != BreakIterator.DONE;
                 wordStart = wordEnd, wordEnd = wordIterator.next()) {
                int tokenBegin = begin + wordStart;
                int tokenEnd = begin + wordEnd;
                int[] tokenTrimmed = trimWhitespace(doc.cleanText, tokenBegin, tokenEnd);
                tokenBegin = tokenTrimmed[0];
                tokenEnd = tokenTrimmed[1];
                if (tokenBegin >= tokenEnd) {
                    continue;
                }
                Token token = new Token();
                token.ordinal = ++counter.tokens;
                token.id = "token-" + token.ordinal;
                token.beginChar = tokenBegin;
                token.endChar = tokenEnd;
                token.text = doc.cleanText.substring(tokenBegin, tokenEnd);
                token.sentence = sentence;
                sentence.tokens.add(token);
                doc.tokens.add(token);
            }

            if (paragraph != null) {
                paragraph.sentences.add(sentence);
            } else if (heading != null) {
                heading.titleSentences.add(sentence);
            }
            doc.sentences.add(sentence);
        }
    }

    private static int parseOptionalFrontMatter(String[] lines, ParsedTextDocument doc,
                                                 List<ValidationIssue> issues) {
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            doc.frontMatterPresent = false;
            return 0;
        }

        doc.frontMatterPresent = true;
        boolean closed = false;
        String activeListKey = null;
        boolean ignoredList = false;
        int i;
        for (i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ("---".equals(line.trim())) {
                closed = true;
                i++;
                break;
            }
            if (line.trim().isEmpty()) {
                continue;
            }
            Matcher listItem = META_LIST_ITEM.matcher(line);
            if (listItem.matches()) {
                if (activeListKey != null) {
                    addMetadataValue(doc, activeListKey, listItem.group(1));
                } else if (!ignoredList) {
                    issues.add(new ValidationIssue(i + 1, 1, "INVALID_METADATA_LIST",
                            "Valore di lista senza una chiave di metadato"));
                }
                continue;
            }
            Matcher matcher = META.matcher(line);
            if (!matcher.matches()) {
                issues.add(new ValidationIssue(i + 1, 1, "INVALID_METADATA",
                        "Metadato non valido. Forma richiesta: chiave: valore"));
                continue;
            }
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String value = matcher.group(2).trim();
            if (!isSupportedMetadataKey(key)) {
                activeListKey = null;
                ignoredList = value.isEmpty();
                continue;
            }
            ignoredList = false;
            if (value.isEmpty()) {
                activeListKey = key;
            } else {
                activeListKey = null;
                addMetadataValue(doc, key, value);
            }
        }
        if (!closed) {
            issues.add(new ValidationIssue(Math.max(1, lines.length), 1, "UNCLOSED_FRONT_MATTER",
                    "Front matter non chiuso con ---"));
            return lines.length;
        }
        return i;
    }

    private static void addMetadataValue(ParsedTextDocument doc, String key, String rawValue) {
        String value = unquoteMetadataValue(rawValue.trim());
        if (value.isEmpty()) {
            return;
        }
        List<String> values = doc.metadataValues.get(key);
        if (values == null) {
            values = new ArrayList<String>();
            doc.metadataValues.put(key, values);
        }
        values.add(value);
        if (!doc.metadata.containsKey(key)) {
            doc.metadata.put(key, value);
        }
    }

    private static String unquoteMetadataValue(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static boolean isSupportedMetadataKey(String key) {
        return "id".equals(key)
                || "title".equals(key)
                || "author".equals(key)
                || "date".equals(key)
                || "language".equals(key)
                || "format".equals(key)
                || "corpus".equals(key);
    }

    private static int flushParagraph(List<String> lines, int sourceLine, Deque<Heading> stack,
                                      StringBuilder clean, ParsedTextDocument doc, int ordinal) {
        if (lines.isEmpty()) {
            return ordinal;
        }
        String text = joinAndNormalizeParagraph(lines);
        lines.clear();
        if (text.isEmpty() || stack.isEmpty()) {
            return ordinal;
        }

        appendBlockSeparator(clean);
        int begin = clean.length();
        clean.append(text);
        int end = clean.length();

        Paragraph paragraph = new Paragraph();
        paragraph.ordinal = ++ordinal;
        paragraph.id = "paragraph-" + ordinal;
        paragraph.text = text;
        paragraph.beginChar = begin;
        paragraph.endChar = end;
        paragraph.heading = stack.peek();
        paragraph.heading.paragraphs.add(paragraph);
        doc.paragraphs.add(paragraph);
        return ordinal;
    }

    private static int flushPlainParagraph(List<String> lines, StringBuilder clean,
                                           ParsedTextDocument doc, int ordinal) {
        if (lines.isEmpty()) {
            return ordinal;
        }
        String text = joinAndNormalizeParagraph(lines);
        lines.clear();
        if (text.isEmpty()) {
            return ordinal;
        }

        appendBlockSeparator(clean);
        int begin = clean.length();
        clean.append(text);
        int end = clean.length();

        Paragraph paragraph = new Paragraph();
        paragraph.ordinal = ++ordinal;
        paragraph.id = "paragraph-" + ordinal;
        paragraph.text = text;
        paragraph.beginChar = begin;
        paragraph.endChar = end;
        paragraph.heading = null;
        doc.paragraphs.add(paragraph);
        return ordinal;
    }

    private static void clearSegmentation(ParsedTextDocument doc) {
        doc.sentences.clear();
        doc.tokens.clear();
        for (Paragraph paragraph : doc.paragraphs) {
            paragraph.sentences.clear();
        }
        for (Heading heading : doc.allHeadings) {
            heading.titleSentences.clear();
        }
    }

    private static String normalizeText(String rawText) {
        String source = Normalizer.normalize(rawText, Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        if (!source.isEmpty() && source.charAt(0) == '\uFEFF') {
            source = source.substring(1);
        }
        return source;
    }

    private static void appendBlockSeparator(StringBuilder clean) {
        if (clean.length() > 0) {
            clean.append("\n\n");
        }
    }

    private static String joinAndNormalizeParagraph(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String normalized = line.trim().replaceAll("\\s+", " ");
            if (normalized.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(normalized);
        }
        return out.toString();
    }

    private static boolean isUnsupportedBlock(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("- ") || trimmed.startsWith("* ")
                || trimmed.startsWith("+ ") || trimmed.startsWith(">")
                || trimmed.startsWith("```") || trimmed.startsWith("~~~")
                || ORDERED_LIST.matcher(line).matches();
    }

    private static String unescapePlainLine(String line) {
        return line.startsWith("\\#") ? line.substring(1) : line;
    }

    private static int firstNonWhitespaceColumn(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isWhitespace(line.charAt(i))) {
                return i + 1;
            }
        }
        return 1;
    }

    private static int[] trimWhitespace(String text, int begin, int end) {
        while (begin < end) {
            int cp = text.codePointAt(begin);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            begin += Character.charCount(cp);
        }
        while (end > begin) {
            int cp = text.codePointBefore(end);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class Counter {
        int sentences;
        int tokens;
    }
}
