package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;

/** Output model for one FRAC attestation and its NIF locus. */
@ApiModel(description = "Output model representing a corpus attestation")
public class Attestation extends AttestationBase {

    public Attestation() {
    }
}
