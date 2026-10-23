package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(description = "New direct parent for a text folder")
public class FolderMoveRequest {
    @ApiModelProperty(value = "target parent folder id; null moves the folder to root")
    public String parentFolderId;
}
