package it.cnr.ilc.lexo.service.data.metadata;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.List;

/** Property-wise metadata replacement and incremental mutation request. */
@ApiModel(description = "Atomic metadata replacements or incremental value changes for one entity")
public class MetadataPatchRequest extends MetadataTarget {

    @ApiModelProperty(value = "properties to replace completely; empty values remove a property")
    public List<RdfMetadataProperty> properties;

    @ApiModelProperty(value = "property values to add without replacing existing values")
    public List<RdfMetadataProperty> addValues;

    @ApiModelProperty(value = "exact property values to remove without changing other values")
    public List<RdfMetadataProperty> removeValues;

    public MetadataPatchRequest() {
    }
}
