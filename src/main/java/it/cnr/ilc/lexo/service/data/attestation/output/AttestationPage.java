package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Paginated output model for attestation retrieval. */
@ApiModel(description = "Paginated list of attestations and their NIF loci")
public class AttestationPage implements Data {

    @ApiModelProperty(value = "number of attestations matching the filters")
    public int totalHits;
    @ApiModelProperty(value = "maximum number of attestations returned in this page")
    public int limit;
    @ApiModelProperty(value = "zero-based offset of this page")
    public int offset;
    @ApiModelProperty(value = "attestations in the requested page")
    public List<AttestationListItem> list = new ArrayList<AttestationListItem>();

    public AttestationPage() {
    }
}
