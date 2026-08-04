package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import java.util.ArrayList;
import java.util.List;

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
    @ApiModelProperty(value = "distinct lexical sense IRIs")
    public List<String> senses = new ArrayList<String>();
    @ApiModelProperty(value = "number of distinct canonical form IRIs")
    public int canonicalFormNumber;
    @ApiModelProperty(value = "first canonical form IRI in deterministic order")
    public String canonicalForm;
    @ApiModelProperty(value = "number of distinct other form IRIs")
    public int otherFormNumber;
    @ApiModelProperty(value = "distinct other form IRIs in deterministic order")
    public List<String> otherForms = new ArrayList<String>();
    @ApiModelProperty(value = "entry RDF metadata allowed by the global policy")
    public List<RdfMetadataProperty> metadata =
            new ArrayList<RdfMetadataProperty>();

    public LexicalEntryListItem() {
    }
}
