package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Compact lexical entry returned by the advanced list service. */
@ApiModel(description = "One lexical entry matching the advanced filters")
public class LexicalEntryListItem implements Data {

    @ApiModelProperty(value = "lexical entry IRI")
    public String entry;
    @ApiModelProperty(value = "effective label selected by the documented fallback")
    public String label;
    @ApiModelProperty(value = "lexical entry RDF type IRI")
    public String type;
    @ApiModelProperty(value = "part-of-speech individual IRI")
    public String pos;
    @ApiModelProperty(value = "entry creator")
    public String author;
    @ApiModelProperty(value = "entry workflow status")
    public String status;
    @ApiModelProperty(value = "number of distinct senses linked from the entry")
    public int senseNumber;

    public LexicalEntryListItem() {
    }
}
