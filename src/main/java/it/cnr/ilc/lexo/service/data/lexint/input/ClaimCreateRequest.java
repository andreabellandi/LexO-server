package it.cnr.ilc.lexo.service.data.lexint.input;

import java.util.List;

public final class ClaimCreateRequest {
    public String description;
    public List<String> evidenceIris;
    public List<TargetInput> targets;
    public String author;
}
