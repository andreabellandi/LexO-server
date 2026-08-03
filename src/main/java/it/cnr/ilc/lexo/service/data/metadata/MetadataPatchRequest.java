package it.cnr.ilc.lexo.service.data.metadata;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.List;

/** Property-wise metadata replacement request. */
@ApiModel(description = "Atomic metadata property replacements for one entity")
public class MetadataPatchRequest extends MetadataTarget {

    @ApiModelProperty(value = "properties to replace; empty values remove a property",
            required = true)
    public List<RdfMetadataProperty> properties;

    public MetadataPatchRequest() {
    }
}
