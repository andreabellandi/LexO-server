package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Result of an atomic observable replacement batch. */
@ApiModel(description = "Result of an atomic attestation observable update")
public class AttestationObservableUpdateResult implements Data {

    @ApiModelProperty(value = "id selecting the per-text attestation graph")
    public String fileId;
    @ApiModelProperty(value = "updated attestations")
    public List<AttestationObservableUpdateItem> updated =
            new ArrayList<AttestationObservableUpdateItem>();

    public AttestationObservableUpdateResult() {
    }
}
