package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Properties and custom metadata of one lexical sense to create. */
@ApiModel(description = "One lexical sense created together with a lexical entry")
public class LexicalSenseCreation implements Data {

    @ApiModelProperty(value = "semantic or structural RDF properties of the sense")
    public List<LexicalSenseProperty> properties;

    @ApiModelProperty(value = "custom RDF metadata properties, each with a non-empty value list")
    public List<LexicalSenseProperty> metadata;

    public LexicalSenseCreation() {
    }
}
