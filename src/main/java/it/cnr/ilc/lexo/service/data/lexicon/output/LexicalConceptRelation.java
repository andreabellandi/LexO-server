package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Related lexical concept and all its supported labels. */
@ApiModel(description = "Related lexical concept and all multilingual labels")
public class LexicalConceptRelation implements Data {

    @ApiModelProperty(value = "related lexical concept IRI")
    public String iri;
    public List<LexicalConceptTypedLabel> labels =
            new ArrayList<LexicalConceptTypedLabel>();

    public LexicalConceptRelation() {
    }

    public LexicalConceptRelation(String iri) {
        this.iri = iri;
    }
}
