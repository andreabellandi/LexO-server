package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;

/** Output model for one attestation returned by the paginated text service. */
@ApiModel(description = "Attestation returned for one text")
public class AttestationListItem extends AttestationBase {

    public AttestationListItem() {
    }
}
