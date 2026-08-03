package it.cnr.ilc.lexo.service.data.metadata;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Common multivalued RDF metadata property. */
@ApiModel(description = "One RDF metadata property and all its values")
public class RdfMetadataProperty implements Data {

    @ApiModelProperty(value = "absolute IRI of the RDF property", required = true)
    public String property;
    @ApiModelProperty(value = "complete value set; an empty list removes the property",
            required = true)
    public List<RdfMetadataValue> values = new ArrayList<RdfMetadataValue>();

    public RdfMetadataProperty() {
    }
}
