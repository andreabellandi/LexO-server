package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Result of a lexical entry core-property update. */
@ApiModel(description = "Updated lexical entry core properties")
public class LexicalEntryUpdateResult implements Data {

    @ApiModelProperty(value = "updated lexical entry IRI")
    public String entry;
    @ApiModelProperty(value = "normalized language graph code")
    public String language;
    @ApiModelProperty(value = "resolved account performing the update")
    public String author;
    @ApiModelProperty(value = "new dcterms:modified timestamp")
    public String modified;
    @ApiModelProperty(value = "effective entry label")
    public String label;
    @ApiModelProperty(value = "effective lexical entry RDF type")
    public String type;
    @ApiModelProperty(value = "effective part-of-speech IRI")
    public String pos;

    public LexicalEntryUpdateResult() {
    }
}
