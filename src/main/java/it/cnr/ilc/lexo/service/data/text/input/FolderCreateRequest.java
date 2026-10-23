package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(description = "Metadata and optional parent for a new text folder")
public class FolderCreateRequest {
    @ApiModelProperty(value = "required folder name", required = true)
    public String name;
    @ApiModelProperty(value = "optional folder description")
    public String description;
    @ApiModelProperty(value = "optional parent folder id; null creates a root folder")
    public String parentFolderId;
}
