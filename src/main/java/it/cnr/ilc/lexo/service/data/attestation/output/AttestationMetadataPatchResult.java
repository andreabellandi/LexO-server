package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Result of an atomic attestation metadata batch. */
@ApiModel(description = "Result of an atomic attestation metadata batch")
public class AttestationMetadataPatchResult implements Data {

    @ApiModelProperty(value = "id selecting the per-text attestation graph")
    public String fileId;
    @ApiModelProperty(value = "updated attestations")
    public List<AttestationMetadataPatchItem> updated =
            new ArrayList<AttestationMetadataPatchItem>();

    public AttestationMetadataPatchResult() {
    }
}
