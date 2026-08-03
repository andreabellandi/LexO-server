package it.cnr.ilc.lexo.service.data.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Canonical metadata representation returned by the common service. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "Canonical RDF metadata of one entity")
public class MetadataResult implements Data {

    @ApiModelProperty(value = "entity kind")
    public String entityType;
    @ApiModelProperty(value = "target resource IRI")
    public String resource;
    @ApiModelProperty(value = "last modification timestamp")
    public String modified;
    @ApiModelProperty(value = "metadata in the common property/value shape")
    public List<RdfMetadataProperty> metadata =
            new ArrayList<RdfMetadataProperty>();

    public MetadataResult() {
    }
}
