package it.cnr.ilc.lexo.service.data.lexint.input;

/** IRI or literal object of one RDF 1.1 classic reification. */
public final class TargetObjectInput {
    public String kind;
    public String value;
    public String language;
    public String datatypeIri;
    /** Reserved detector: RDF 1.2 triple terms are unsupported in v1. */
    public String reifies;
}
