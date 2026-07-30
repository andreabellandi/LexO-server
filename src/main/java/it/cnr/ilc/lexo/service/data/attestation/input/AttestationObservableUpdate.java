package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Atomic replacement of the observable for one or more attestations. */
@ApiModel(description = "Atomic observable replacement for FRAC attestations")
public class AttestationObservableUpdate implements Data {

    @ApiModelProperty(value = "IRI of the replacement OntoLex observable",
            required = true)
    public String observable;
    @ApiModelProperty(value = "IRIs of the attestations to update",
            required = true)
    public List<String> attestations;

    public AttestationObservableUpdate() {
    }
}
