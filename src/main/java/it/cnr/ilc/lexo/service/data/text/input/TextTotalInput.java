package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/** Input for replacing one FRAC total on a text or corpus. */
@ApiModel(description = "FRAC total value and counting unit")
public class TextTotalInput {

    @ApiModelProperty(value = "non-negative total represented as xsd:int",
            example = "2312", required = true)
    public Integer value;
    @ApiModelProperty(value = "tokens, types, lemmas, sentences, the corresponding lexo compact IRI, or its full IRI",
            example = "tokens", required = true)
    public String unit;

    public TextTotalInput() {
    }

    public TextTotalInput(Integer value, String unit) {
        this.value = value;
        this.unit = unit;
    }
}
