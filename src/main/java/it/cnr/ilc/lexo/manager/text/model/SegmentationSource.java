package it.cnr.ilc.lexo.manager.text.model;

/** Authoritative source used to create both NIF and Lucene token positions. */
public enum SegmentationSource {
    CONLLU,
    ANNOTATED_IMPORT,
    LUCENE_STANDARD,
    /** Exact spans reconstructed from a legacy NIF lacking provenance fields. */
    PERSISTED_NIF
}
