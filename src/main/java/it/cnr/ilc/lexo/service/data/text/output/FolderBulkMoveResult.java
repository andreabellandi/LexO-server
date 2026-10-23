package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FolderBulkMoveResult implements Data {
    public String targetFolderId;
    public int total;
    public int changed;
    public List<FolderMembershipResult> texts =
            new ArrayList<FolderMembershipResult>();
}
