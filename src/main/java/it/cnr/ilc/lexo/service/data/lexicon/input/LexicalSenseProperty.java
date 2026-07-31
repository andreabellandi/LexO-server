package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** One property and its values for a new lexical sense. */
@ApiModel(description = "One RDF property of a lexical sense")
public class LexicalSenseProperty implements Data {

    @ApiModelProperty(value = "absolute IRI of the RDF property", required = true)
    public String property;

    @ApiModelProperty(value = "non-empty list of RDF values", required = true)
    public List<LexicalRdfValue> values;

    public LexicalSenseProperty() {
    }
}
