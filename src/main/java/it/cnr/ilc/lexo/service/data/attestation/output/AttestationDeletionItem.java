package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Summary of one deleted attestation. */
@ApiModel(description = "One deleted FRAC attestation")
public class AttestationDeletionItem implements Data {

    @ApiModelProperty(value = "IRI of the deleted attestation")
    public String attestation;
    @ApiModelProperty(value = "IRI of the observed lexical entity")
    public String observable;
    @ApiModelProperty(value = "IRI of the NIF locus")
    public String locus;

    public AttestationDeletionItem() {
    }
}
