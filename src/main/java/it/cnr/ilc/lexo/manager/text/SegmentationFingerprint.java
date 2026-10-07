package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.Sentence;
import it.cnr.ilc.lexo.manager.text.model.Token;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic content and segmentation fingerprints shared by RDF/Lucene. */
public final class SegmentationFingerprint {

    private SegmentationFingerprint() {
    }

    public static void complete(ParsedTextDocument document) {
        if (document == null || document.cleanText == null
                || document.segmentationSource == null) {
            throw new IllegalArgumentException("Complete canonical segmentation is required");
        }
        document.segmentationSchemaVersion = configuredVersion();
        document.contentHash = sha256(document.cleanText);
        StringBuilder canonical = new StringBuilder();
        canonical.append(document.segmentationSchemaVersion).append('\n')
                .append(document.segmentationSource.name()).append('\n')
                .append(nullToEmpty(document.tokenizerProfile)).append('\n')
                .append(nullToEmpty(document.sentenceSplitterProfile)).append('\n');
        for (Sentence sentence : document.sentences) {
            canonical.append('S').append('|').append(sentence.ordinal).append('|')
                    .append(sentence.beginChar).append('|').append(sentence.endChar)
                    .append('\n');
        }
        for (Token token : document.tokens) {
            canonical.append('T').append('|').append(token.ordinal).append('|')
                    .append(token.beginChar).append('|').append(token.endChar).append('|')
                    .append(token.sentence == null ? 0 : token.sentence.ordinal).append('|')
                    .append(nullToEmpty(token.text)).append('|')
                    .append(nullToEmpty(token.lemma)).append('\n');
        }
        document.segmentationHash = sha256(canonical.toString());
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexadecimal = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                hexadecimal.append(String.format("%02x", current & 0xff));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String configuredVersion() {
        String value = LexOProperties.getProperty("lexo.lucene.segmentationSchemaVersion", "1");
        return value == null || value.trim().isEmpty() ? "1" : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
