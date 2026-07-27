package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Input model for one observed occurrence in an attestation batch. */
@ApiModel(description = "One textual occurrence to create as a FRAC attestation")
public class AttestationOccurrence implements Data {

    @ApiModelProperty(value = "optional description of this attestation",
            example = "Occurrence observed in the interview")
    public String description;
    @ApiModelProperty(value = "string observed in the canonical NIF context",
            example = "gli stessi diritti", required = true)
    public String value;
    @ApiModelProperty(value = "inclusive Unicode code-point start offset",
            example = "42", required = true)
    public Integer start;
    @ApiModelProperty(value = "exclusive Unicode code-point end offset",
            example = "60", required = true)
    public Integer end;

    public AttestationOccurrence() {
    }

    public AttestationOccurrence(String description, String value,
                                 Integer start, Integer end) {
        this.description = description;
        this.value = value;
        this.start = start;
        this.end = end;
    }
}
