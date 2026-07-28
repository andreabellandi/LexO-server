package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Output model for one FRAC attestation and its NIF locus. */
@ApiModel(description = "Output model representing a corpus attestation")
public class Attestation implements Data {

    @ApiModelProperty(value = "IRI of the created FRAC attestation")
    public String attestation;
    @ApiModelProperty(value = "IRI of the observed lexical entity")
    public String observable;
    @ApiModelProperty(value = "display label resolved from the observed lexical entity")
    public String observableLabel;
    @ApiModelProperty(value = "RDF types of the observed lexical entity")
    public List<String> observableTypes = new ArrayList<String>();
    @ApiModelProperty(value = "optional attestation description")
    public String description;
    @ApiModelProperty(value = "observed string")
    public String value;
    @ApiModelProperty(value = "Unicode code-point start offset")
    public Integer start;
    @ApiModelProperty(value = "Unicode code-point end offset")
    public Integer end;
    @ApiModelProperty(value = "IRI of the corpus or text where the value was observed")
    public String corpus;
    @ApiModelProperty(value = "IRI of the NIF phrase used as attestation locus")
    public String locus;
    @ApiModelProperty(value = "RDF types of the NIF locus")
    public List<String> locusTypes = new ArrayList<String>();
    @ApiModelProperty(value = "language tag of the NIF anchor, when available")
    public String language;
    @ApiModelProperty(value = "IRI of the NIF reference context")
    public String referenceContext;
    @ApiModelProperty(value = "id used to select the per-text attestation graph")
    public String fileId;
    @ApiModelProperty(value = "true when the observed text is external to LexOTexts")
    public Boolean external;
    @ApiModelProperty(value = "account that created the attestation")
    public String creator;
    @ApiModelProperty(value = "attestation creation timestamp")
    public String creationDate;
    @ApiModelProperty(value = "attestation last-modification timestamp")
    public String lastUpdate;

    public Attestation() {
    }
}
