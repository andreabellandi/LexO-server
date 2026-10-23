package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@ApiModel(description = "Descriptive metadata for an existing text folder")
public class FolderUpdateRequest {
    @ApiModelProperty(value = "required replacement folder name", required = true)
    public String name;
    @ApiModelProperty(value = "replacement description; null removes it")
    public String description;
}
