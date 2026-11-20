package it.cnr.ilc.lexo.service.data.lexint.output;

import java.util.ArrayList;
import java.util.List;

public final class PublicationDetails {
    public String id;
    public String iri;
    public String status;
    public String auditStatus;
    public String identityAssurance;
    public String username;
    public String language;
    public String sourceVersionIri;
    public String targetVersionIri;
    public int expectedInsertions;
    public int expectedDeletions;
    public long unexpectedTripleCount;
    public long missingTripleCount;
    public String baseEtag;
    public String targetEtag;
    public String creator;
    public String created;
    public String modified;
    public List<PublicationOperationDetails> operations =
            new ArrayList<PublicationOperationDetails>();
    public List<AuditReportDetails> audit = new ArrayList<AuditReportDetails>();
    public boolean replayed;
    public String requestId;
}
