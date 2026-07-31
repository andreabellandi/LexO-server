package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;
import java.util.Map;

/** Properties and custom metadata of one lexical sense to create. */
@ApiModel(description = "One lexical sense created together with a lexical entry")
public class LexicalSenseCreation implements Data {

    @ApiModelProperty(value = "semantic or structural RDF properties of the sense")
    public List<LexicalSenseProperty> properties;

    @ApiModelProperty(value = "custom metadata keyed by absolute property IRI")
    public Map<String, List<LexicalRdfValue>> metadata;

    public LexicalSenseCreation() {
    }
}
