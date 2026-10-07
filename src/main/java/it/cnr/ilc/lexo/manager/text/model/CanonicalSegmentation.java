package it.cnr.ilc.lexo.manager.text.model;

import java.util.Collections;
import java.util.List;

/** Immutable view of the single segmentation used by NIF and Lucene. */
public final class CanonicalSegmentation {

    public final CanonicalText text;
    public final SegmentationSource source;
    public final List<Token> tokens;
    public final List<Sentence> sentences;

    public CanonicalSegmentation(ParsedTextDocument document) {
        if (document == null || document.cleanText == null
                || document.segmentationSource == null) {
            throw new IllegalArgumentException("Canonical segmentation is incomplete");
        }
        this.text = CanonicalText.rendered(document.cleanText);
        this.source = document.segmentationSource;
        this.tokens = Collections.unmodifiableList(document.tokens);
        this.sentences = Collections.unmodifiableList(document.sentences);
    }
}
