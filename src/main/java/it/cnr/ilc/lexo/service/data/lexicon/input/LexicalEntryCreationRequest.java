package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Request body for atomic lexical entry creation. */
@ApiModel(description = "A lexical entry, its optional canonical form, and senses")
public class LexicalEntryCreationRequest implements Data {

    @ApiModelProperty(value = "entry label", required = true, example = "casa")
    public String label;

    @ApiModelProperty(value = "absolute IRI of ontolex:LexicalEntry or one of its subclasses",
            required = true,
            example = "http://www.w3.org/ns/lemon/ontolex#Word")
    public String type;

    @ApiModelProperty(value = "optional IRI of a lexinfo:PartOfSpeech individual")
    public String pos;

    @ApiModelProperty(value = "ISO 639 code from the bundled list", required = true,
            example = "it")
    public String language;

    @ApiModelProperty(value = "create a canonical form using the label",
            example = "true")
    public Boolean lemma;

    @ApiModelProperty(value = "optional lexical senses created with the entry")
    public List<LexicalSenseCreation> senses;

    public LexicalEntryCreationRequest() {
    }
}
