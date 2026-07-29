package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Batch of metadata property replacements for one attestation graph. */
@ApiModel(description = "Atomic batch of attestation metadata updates")
public class AttestationMetadataBatch implements Data {

    @ApiModelProperty(value = "metadata updates grouped by attestation",
            required = true)
    public List<AttestationMetadataUpdate> updates;

    public AttestationMetadataBatch() {
    }
}
