package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** A lexical sense IRI paired with the language graph that contains it. */
@ApiModel(description = "A lexical sense and the ISO 639 language selecting its named graph")
public class LexicalConceptSenseLink implements Data {

    @ApiModelProperty(value = "absolute IRI of an existing ontolex:LexicalSense",
            required = true)
    public String senseId;

    @ApiModelProperty(value = "ISO 639 code selecting the sense's lexical named graph",
            required = true, example = "it")
    public String language;

    public LexicalConceptSenseLink() {
    }

    public LexicalConceptSenseLink(String senseId, String language) {
        this.senseId = senseId;
        this.language = language;
    }
}
