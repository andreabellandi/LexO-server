package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Result of an atomic lexical-entry workflow transition batch. */
@ApiModel(description = "Result of an atomic lexical-entry status change batch")
public class LexicalEntryStatusChangeResult implements Data {

    @ApiModelProperty(value = "normalized ISO 639 language")
    public String language;

    @ApiModelProperty(value = "resolved author of the status changes")
    public String author;

    @ApiModelProperty(value = "timestamp shared by all status changes")
    public String modified;

    @ApiModelProperty(value = "applied lexical-entry status changes")
    public List<LexicalEntryStatusChangeItem> entries =
            new ArrayList<LexicalEntryStatusChangeItem>();

    public LexicalEntryStatusChangeResult() {
    }
}
