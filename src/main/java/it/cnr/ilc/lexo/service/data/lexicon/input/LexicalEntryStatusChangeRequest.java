package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Atomic batch of lexical-entry workflow transitions in one language graph. */
@ApiModel(description = "Atomic lexical-entry workflow status change batch")
public class LexicalEntryStatusChangeRequest implements Data {

    @ApiModelProperty(value = "ISO 639 language selecting the lexical named graph",
            required = true, example = "it")
    public String language;

    @ApiModelProperty(value = "one or more lexical-entry status changes",
            required = true)
    public List<LexicalEntryStatusChange> entries;

    public LexicalEntryStatusChangeRequest() {
    }
}
