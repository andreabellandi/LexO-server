package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FolderMembershipResult implements Data {
    public String fileId;
    public String contextUri;
    public String folderId;
    public String previousFolderId;
    public Boolean changed;
    public Boolean removed;
}
