package it.cnr.ilc.lexo.service.data.lexint.output;

import java.util.ArrayList;
import java.util.List;

public final class AuditReportDetails {
    public String verificationId;
    public String verificationIri;
    public String reportId;
    public String reportIri;
    public String publicationIri;
    public String outcome;
    public long missingTripleCount;
    public long unexpectedTripleCount;
    public List<TripleDetails> missingTriples = new ArrayList<TripleDetails>();
    public List<TripleDetails> unexpectedTriples = new ArrayList<TripleDetails>();
    public List<String> errorCodes = new ArrayList<String>();
    public String auditScope;
    public String checkedAt;
    public String sourceSnapshotGraphIri;
    public String targetSnapshotGraphIri;
    public String username;
    public String identityAssurance;
    public String creator;
    public String created;
    public String modified;
}
