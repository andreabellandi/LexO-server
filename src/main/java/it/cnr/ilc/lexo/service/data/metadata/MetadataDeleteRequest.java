package it.cnr.ilc.lexo.service.data.metadata;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.List;

/** Explicit metadata property deletion request. */
@ApiModel(description = "Metadata properties to remove from one entity")
public class MetadataDeleteRequest extends MetadataTarget {

    @ApiModelProperty(value = "absolute property IRIs to remove", required = true)
    public List<String> properties;

    public MetadataDeleteRequest() {
    }
}
