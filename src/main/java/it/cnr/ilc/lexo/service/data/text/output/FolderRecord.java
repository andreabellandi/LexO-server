package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FolderRecord implements Data {
    public String folderId;
    public String uri;
    public String name;
    public String description;
    public String parentFolderId;
    public String createdAt;
    public String modifiedAt;

    public FolderRecord() {
    }

    public FolderRecord(FolderRecord source) {
        folderId = source.folderId;
        uri = source.uri;
        name = source.name;
        description = source.description;
        parentFolderId = source.parentFolderId;
        createdAt = source.createdAt;
        modifiedAt = source.modifiedAt;
    }
}
