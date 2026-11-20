package it.cnr.ilc.lexo.service.data.lexint.output;

import java.util.ArrayList;
import java.util.List;

public final class VersionDetails {
    public String id;
    public String iri;
    public String language;
    public String versionCode;
    public String state;
    public String snapshotGraphIri;
    public String predecessorIri;
    public String publicationIri;
    public String creator;
    public String created;
    public String modified;
    public String etag;
    public String identityAssurance;
    public List<AuditReportDetails> audit =
            new ArrayList<AuditReportDetails>();
}
