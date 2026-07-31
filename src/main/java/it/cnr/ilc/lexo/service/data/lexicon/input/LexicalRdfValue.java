package it.cnr.ilc.lexo.service.data.lexicon.input;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** One RDF object supplied while creating a lexical sense. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "One literal or IRI value of a lexical sense property")
public class LexicalRdfValue implements Data {

    @ApiModelProperty(value = "lexical value or absolute IRI", required = true)
    public String value;

    @ApiModelProperty(value = "RDF value kind", required = true,
            allowableValues = "literal,iri")
    public String type;

    @ApiModelProperty(value = "optional BCP 47 language tag for a literal")
    public String language;

    @ApiModelProperty(value = "optional absolute datatype IRI for a literal")
    public String datatype;

    public LexicalRdfValue() {
    }

    public LexicalRdfValue(String value, String type, String language,
                           String datatype) {
        this.value = value;
        this.type = type;
        this.language = language;
        this.datatype = datatype;
    }
}
