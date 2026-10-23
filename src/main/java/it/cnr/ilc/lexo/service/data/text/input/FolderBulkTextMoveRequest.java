package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.List;

@ApiModel(description = "Atomic movement of multiple persisted texts")
public class FolderBulkTextMoveRequest {
    @ApiModelProperty(value = "non-empty unique persisted text fileIds", required = true)
    public List<String> fileIds = new ArrayList<String>();
    @ApiModelProperty(value = "target folder id; null makes every selected text unfiled")
    public String targetFolderId;
}
