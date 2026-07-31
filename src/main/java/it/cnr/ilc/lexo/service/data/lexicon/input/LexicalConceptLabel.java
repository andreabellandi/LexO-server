package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Language-tagged text used by lexical concept labels and definitions. */
@ApiModel(description = "A text value paired with its ISO 639 language")
public class LexicalConceptLabel implements Data {

    @ApiModelProperty(value = "text value", required = true,
            example = "abitazione")
    public String label;

    @ApiModelProperty(value = "ISO 639 language code", required = true,
            example = "it")
    public String language;

    public LexicalConceptLabel() {
    }

    public LexicalConceptLabel(String label, String language) {
        this.label = label;
        this.language = language;
    }
}
