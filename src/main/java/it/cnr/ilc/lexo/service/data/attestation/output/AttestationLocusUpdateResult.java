package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Result of one attestation locus update. */
@ApiModel(description = "Result of an attestation locus update")
public class AttestationLocusUpdateResult implements Data {

    @ApiModelProperty(value = "id selecting the per-text attestation graph")
    public String fileId;
    @ApiModelProperty(value = "IRI of the updated attestation")
    public String attestation;
    @ApiModelProperty(value = "previous NIF locus IRI")
    public String previousLocus;
    @ApiModelProperty(value = "new NIF locus IRI")
    public String locus;
    @ApiModelProperty(value = "text recalculated from nif:isString")
    public String value;
    @ApiModelProperty(value = "inclusive Unicode code-point start offset")
    public Integer start;
    @ApiModelProperty(value = "exclusive Unicode code-point end offset")
    public Integer end;
    @ApiModelProperty(value = "whether frac:gloss was replaced")
    public boolean glossUpdated;
    @ApiModelProperty(value = "new dcterms:modified timestamp")
    public String lastUpdate;

    public AttestationLocusUpdateResult() {
    }
}
