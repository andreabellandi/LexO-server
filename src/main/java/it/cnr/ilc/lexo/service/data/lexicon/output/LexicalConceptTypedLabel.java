package it.cnr.ilc.lexo.service.data.lexicon.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** One label value together with the RDF predicate that classifies it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "One lexical concept label with its RDF predicate")
public class LexicalConceptTypedLabel implements Data {

    @ApiModelProperty(value = "absolute IRI of the label property")
    public String property;
    @ApiModelProperty(value = "literal lexical value")
    public String value;
    @ApiModelProperty(value = "optional language tag")
    public String language;
    @ApiModelProperty(value = "optional datatype IRI")
    public String datatype;

    public LexicalConceptTypedLabel() {
    }

    public LexicalConceptTypedLabel(String property, String value,
                                    String language, String datatype) {
        this.property = property;
        this.value = value;
        this.language = language;
        this.datatype = datatype;
    }
}
