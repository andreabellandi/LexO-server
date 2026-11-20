package it.cnr.ilc.lexo.service.data.lexint.input;

import java.util.List;

public final class PublicationRequest {
    public String language;
    public String sourceVersionIri;
    public String targetVersionIri;
    public List<PublicationOperationInput> operations;
    public String author;
}
