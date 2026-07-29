package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Summary of the metadata properties updated on one attestation. */
@ApiModel(description = "Result of one attestation metadata update")
public class AttestationMetadataPatchItem implements Data {

    @ApiModelProperty(value = "IRI of the updated attestation")
    public String attestation;
    @ApiModelProperty(value = "IRIs of the replaced metadata properties")
    public List<String> properties = new ArrayList<String>();
    @ApiModelProperty(value = "new dcterms:modified timestamp")
    public String lastUpdate;

    public AttestationMetadataPatchItem() {
    }
}
