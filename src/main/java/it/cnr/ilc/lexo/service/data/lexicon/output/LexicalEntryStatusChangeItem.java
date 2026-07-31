package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Applied workflow transition for one lexical entry. */
@ApiModel(description = "Applied lexical-entry workflow transition")
public class LexicalEntryStatusChangeItem implements Data {

    @ApiModelProperty(value = "IRI of the lexical entry")
    public String entry;

    @ApiModelProperty(value = "status before the transition")
    public String previousStatus;

    @ApiModelProperty(value = "status after the transition")
    public String status;

    public LexicalEntryStatusChangeItem() {
    }

    public LexicalEntryStatusChangeItem(String entry, String previousStatus,
                                        String status) {
        this.entry = entry;
        this.previousStatus = previousStatus;
        this.status = status;
    }
}
