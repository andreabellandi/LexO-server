package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Input model for deleting attestations grouped by NIF locus. */
@ApiModel(description = "Atomic deletion of selected or all attestations at one NIF locus")
public class AttestationDeleteByLocusInput implements Data {

    @ApiModelProperty(value = "IRI of the shared NIF locus", required = true)
    public String locus;
    @ApiModelProperty(value = "delete every attestation at the locus in the selected text graph",
            example = "false")
    public Boolean all;
    @ApiModelProperty(value = "attestation IRIs to delete when all is false")
    public List<String> attestations = new ArrayList<String>();

    public AttestationDeleteByLocusInput() {
    }
}
