package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Metadata property replacements for one attestation. */
@ApiModel(description = "Metadata properties to replace on one FRAC attestation")
public class AttestationMetadataUpdate implements Data {

    @ApiModelProperty(value = "absolute IRI of the attestation", required = true)
    public String attestation;
    @ApiModelProperty(value = "properties whose complete value sets must be replaced",
            required = true)
    public List<AttestationMetadataProperty> properties;

    public AttestationMetadataUpdate() {
    }
}
