package it.cnr.ilc.lexo.service.data.lexint.output;

import java.util.ArrayList;
import java.util.List;

public final class PublicationPreview {
    public String status;
    public String language;
    public String sourceVersionIri;
    public String targetVersionIri;
    public String baseEtag;
    public int expectedInsertions;
    public int expectedDeletions;
    public boolean partialClaims;
    public List<PublicationOperationDetails> operations =
            new ArrayList<PublicationOperationDetails>();
    public List<String> warnings = new ArrayList<String>();
    public String identityAssurance;
    public String username;
}
