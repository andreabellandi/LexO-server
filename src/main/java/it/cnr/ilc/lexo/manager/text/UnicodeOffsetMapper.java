package it.cnr.ilc.lexo.manager.text;

/** Central conversion between Java/Lucene UTF-16 and public NIF code points. */
public final class UnicodeOffsetMapper {

    private UnicodeOffsetMapper() {
    }

    public static int utf16ToCodePoint(String text, int utf16Offset) {
        requireUtf16(text, utf16Offset);
        return text.codePointCount(0, utf16Offset);
    }

    public static int codePointToUtf16(String text, int codePointOffset) {
        if (text == null || codePointOffset < 0
                || codePointOffset > text.codePointCount(0, text.length())) {
            throw new IllegalArgumentException("Code-point offset outside canonical text");
        }
        return text.offsetByCodePoints(0, codePointOffset);
    }

    public static String substringByCodePoint(String text, int begin, int end) {
        if (begin < 0 || end < begin) {
            throw new IllegalArgumentException("Invalid code-point range");
        }
        return text.substring(codePointToUtf16(text, begin),
                codePointToUtf16(text, end));
    }

    private static void requireUtf16(String text, int offset) {
        if (text == null || offset < 0 || offset > text.length()) {
            throw new IllegalArgumentException("UTF-16 offset outside canonical text");
        }
        if (offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset))) {
            throw new IllegalArgumentException("UTF-16 offset splits a surrogate pair");
        }
    }
}
