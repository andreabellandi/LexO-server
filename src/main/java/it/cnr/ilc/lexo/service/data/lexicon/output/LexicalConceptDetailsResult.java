package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import java.util.ArrayList;
import java.util.List;

/** Complete read model for one lexical concept. */
@ApiModel(description = "Complete lexical concept data and linked resources")
public class LexicalConceptDetailsResult implements Data {

    @ApiModelProperty(value = "lexical concept IRI")
    public String lexicalConcept;
    public List<LexicalConceptTypedLabel> labels =
            new ArrayList<LexicalConceptTypedLabel>();
    public List<RdfMetadataValue> definitions =
            new ArrayList<RdfMetadataValue>();
    public List<LexicalConceptLinkedResource> lexicalEntries =
            new ArrayList<LexicalConceptLinkedResource>();
    public List<LexicalConceptLinkedResource> lexicalSenses =
            new ArrayList<LexicalConceptLinkedResource>();
    public List<String> conceptSets = new ArrayList<String>();
    public LexicalConceptRelations children = new LexicalConceptRelations();
    public LexicalConceptRelations parents = new LexicalConceptRelations();
    public List<RdfMetadataProperty> metadata =
            new ArrayList<RdfMetadataProperty>();

    public LexicalConceptDetailsResult() {
    }
}
