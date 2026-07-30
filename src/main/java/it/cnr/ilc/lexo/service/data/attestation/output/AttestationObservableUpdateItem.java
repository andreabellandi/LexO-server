package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Observable replacement summary for one attestation. */
@ApiModel(description = "Observable replacement performed on one attestation")
public class AttestationObservableUpdateItem implements Data {

    @ApiModelProperty(value = "IRI of the updated attestation")
    public String attestation;
    @ApiModelProperty(value = "previous observable IRIs")
    public List<String> previousObservables = new ArrayList<String>();
    @ApiModelProperty(value = "IRI of the replacement observable")
    public String observable;
    @ApiModelProperty(value = "new dcterms:modified timestamp")
    public String lastUpdate;

    public AttestationObservableUpdateItem() {
    }
}
