package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Input model for deleting attestations grouped by observable. */
@ApiModel(description = "Atomic deletion of selected or all attestations of one observable")
public class AttestationDeleteByObservableInput implements Data {

    @ApiModelProperty(value = "IRI of the observable whose attestations must be deleted",
            required = true)
    public String observable;
    @ApiModelProperty(value = "delete every attestation of the observable in the selected text graph",
            example = "false")
    public Boolean all;
    @ApiModelProperty(value = "attestation IRIs to delete when all is false")
    public List<String> attestations = new ArrayList<String>();

    public AttestationDeleteByObservableInput() {
    }
}
