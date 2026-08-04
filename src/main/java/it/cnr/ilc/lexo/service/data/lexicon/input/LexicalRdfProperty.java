package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import java.util.List;

/** One RDF property and its values for a lexical resource. */
@ApiModel(description = "One multivalued RDF property of a lexical resource")
public class LexicalRdfProperty implements Data {

    @ApiModelProperty(value = "absolute IRI of the RDF property", required = true)
    public String property;

    @ApiModelProperty(value = "non-empty list of RDF values", required = true)
    public List<RdfMetadataValue> values;

    public LexicalRdfProperty() {
    }
}
