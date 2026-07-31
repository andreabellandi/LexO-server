package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** One requested lexical-entry workflow transition. */
@ApiModel(description = "Requested workflow transition for one lexical entry")
public class LexicalEntryStatusChange implements Data {

    @ApiModelProperty(value = "absolute IRI of the lexical entry", required = true)
    public String entry;

    @ApiModelProperty(value = "expected current status", required = true,
            allowableValues = "working, completed, revised")
    public String fromStatus;

    @ApiModelProperty(value = "requested new status", required = true,
            allowableValues = "working, completed, revised")
    public String toStatus;

    public LexicalEntryStatusChange() {
    }

    public LexicalEntryStatusChange(String entry, String fromStatus,
                                    String toStatus) {
        this.entry = entry;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }
}
