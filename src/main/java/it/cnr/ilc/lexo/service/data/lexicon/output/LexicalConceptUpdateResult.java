package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptLabel;
import java.util.List;

/** Result of a lexical concept semantic-property update. */
@ApiModel(description = "Updated lexical concept labels, definitions, and links")
public class LexicalConceptUpdateResult implements Data {

    @ApiModelProperty(value = "updated lexical concept IRI")
    public String lexicalConcept;
    @ApiModelProperty(value = "resolved account performing the update")
    public String author;
    @ApiModelProperty(value = "new dcterms:modified timestamp")
    public String modified;
    public List<LexicalConceptLabel> label;
    public List<LexicalConceptLabel> alternativeLabel;
    public List<LexicalConceptLabel> hiddenLabel;
    public List<LexicalConceptLabel> definition;
    public List<String> senseId;
    public String parent;
    public String conceptSetId;

    public LexicalConceptUpdateResult() {
    }
}
