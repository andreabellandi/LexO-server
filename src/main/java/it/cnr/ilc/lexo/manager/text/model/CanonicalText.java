package it.cnr.ilc.lexo.manager.text.model;

import java.text.Normalizer;

/** Immutable authoritative string stored as {@code nif:isString}. */
public final class CanonicalText {

    private final String value;

    private CanonicalText(String value) {
        this.value = value;
    }

    /** Canonicalizes decoded TXT/JSON input before structure and offsets exist. */
    public static CanonicalText fromDecodedText(String decoded) {
        if (decoded == null) {
            throw new IllegalArgumentException("Canonical text is required");
        }
        String normalized = decoded.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.isEmpty() && normalized.charAt(0) == '\uFEFF') {
            normalized = normalized.substring(1);
        }
        return new CanonicalText(Normalizer.normalize(normalized, Normalizer.Form.NFC));
    }

    /** Wraps text already rendered by the controlled CommonMark pipeline. */
    public static CanonicalText rendered(String rendered) {
        if (rendered == null) {
            throw new IllegalArgumentException("Canonical text is required");
        }
        return new CanonicalText(rendered);
    }

    public String value() {
        return value;
    }
}
