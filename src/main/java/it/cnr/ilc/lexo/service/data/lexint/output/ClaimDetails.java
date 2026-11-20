package it.cnr.ilc.lexo.service.data.lexint.output;

import java.util.ArrayList;
import java.util.List;

public final class ClaimDetails {
    public String id;
    public String iri;
    public String description;
    public List<String> evidenceIris = new ArrayList<String>();
    public List<TargetDetails> targets = new ArrayList<TargetDetails>();
    public String creator;
    public String created;
    public String modified;
    public String etag;
    public boolean editable;
    public String identityAssurance;
}
