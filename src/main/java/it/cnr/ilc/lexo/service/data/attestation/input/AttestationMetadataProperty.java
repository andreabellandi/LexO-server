package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.attestation.AttestationMetadataValue;
import java.util.List;

/** Complete replacement value set for one attestation metadata property. */
@ApiModel(description = "One RDF metadata property and its replacement values")
public class AttestationMetadataProperty implements Data {

    @ApiModelProperty(value = "absolute IRI of the RDF property", required = true)
    public String property;
    @ApiModelProperty(value = "complete replacement value set; empty removes the property",
            required = true)
    public List<AttestationMetadataValue> values;

    public AttestationMetadataProperty() {
    }
}
