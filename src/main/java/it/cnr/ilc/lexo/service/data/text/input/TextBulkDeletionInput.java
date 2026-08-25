package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.List;

/** Input for starting an asynchronous bulk text deletion. */
@ApiModel(description = "Text identifiers to delete independently")
public class TextBulkDeletionInput {

    @ApiModelProperty(value = "unique text fileIds to delete",
            required = true)
    public List<String> fileIds = new ArrayList<String>();

    public TextBulkDeletionInput() {
    }

    public TextBulkDeletionInput(List<String> fileIds) {
        this.fileIds = fileIds;
    }
}
