package it.cnr.ilc.lexo.service.data.lexicon.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Identifiers and state returned after lexical entry creation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "Created lexical entry and related resource identifiers")
public class LexicalEntryCreationResult implements Data {

    @ApiModelProperty(value = "IRI of the reused or created lexicon")
    public String lexicon;

    @ApiModelProperty(value = "true when this request created the lexicon")
    public boolean lexiconCreated;

    @ApiModelProperty(value = "IRI of the created lexical entry")
    public String entry;

    @ApiModelProperty(value = "IRI of the optional canonical form")
    public String canonicalForm;

    @ApiModelProperty(value = "IRIs of the created lexical senses")
    public List<String> senses;

    @ApiModelProperty(value = "normalized ISO 639 language")
    public String language;

    @ApiModelProperty(value = "initial workflow status")
    public String status;

    @ApiModelProperty(value = "creation timestamp shared by the new resources")
    public String created;

    public LexicalEntryCreationResult() {
    }
}
