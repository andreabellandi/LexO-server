package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** New Unicode code-point interval for one attestation locus. */
@ApiModel(description = "Update of the NIF locus used by one FRAC attestation")
public class AttestationLocusUpdate implements Data {

    @ApiModelProperty(value = "absolute IRI of the attestation", required = true)
    public String attestation;
    @ApiModelProperty(value = "inclusive Unicode code-point start offset",
            example = "42", required = true)
    public Integer start;
    @ApiModelProperty(value = "exclusive Unicode code-point end offset",
            example = "60", required = true)
    public Integer end;
    @ApiModelProperty(value = "also replace frac:gloss; defaults to true",
            example = "true")
    public Boolean updateGloss;

    public AttestationLocusUpdate() {
    }
}
