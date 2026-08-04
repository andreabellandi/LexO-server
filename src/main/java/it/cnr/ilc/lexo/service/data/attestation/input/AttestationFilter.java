package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import java.util.List;

/** Boolean filter tree shared by attestation retrieval services. */
@ApiModel(description = "Boolean group or leaf condition used to filter attestations")
public class AttestationFilter implements Data {

    @ApiModelProperty(value = "group operator AND/OR, or leaf operator IN/EQ/EXISTS",
            required = true, allowableValues = "AND,OR,IN,EQ,EXISTS")
    public String operator;
    @ApiModelProperty(value = "children of an AND or OR group")
    public List<AttestationFilter> filters;
    @ApiModelProperty(value = "leaf field",
            allowableValues = "creator,textMetadata,observableType")
    public String field;
    @ApiModelProperty(value = "absolute RDF property IRI for a textMetadata leaf")
    public String property;
    @ApiModelProperty(value = "exact creator values or observable type IRIs; values are OR alternatives")
    public List<String> values;
    @ApiModelProperty(value = "exact RDF text metadata values; values are OR alternatives")
    public List<RdfMetadataValue> rdfValues;

    public AttestationFilter() {
    }
}
