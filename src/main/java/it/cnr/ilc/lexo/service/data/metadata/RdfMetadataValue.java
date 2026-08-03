package it.cnr.ilc.lexo.service.data.metadata;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Common RDF value used by metadata on every application entity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "One IRI or literal RDF metadata value")
public class RdfMetadataValue implements Data {

    @ApiModelProperty(value = "lexical form of the literal or absolute IRI",
            required = true)
    public String value;
    @ApiModelProperty(value = "RDF value kind", allowableValues = "literal,iri",
            required = true)
    public String type;
    @ApiModelProperty(value = "optional BCP 47 language tag for a literal")
    public String language;
    @ApiModelProperty(value = "optional absolute datatype IRI for a literal")
    public String datatype;

    public RdfMetadataValue() {
    }

    public RdfMetadataValue(String value, String type, String language,
                            String datatype) {
        this.value = value;
        this.type = type;
        this.language = language;
        this.datatype = datatype;
    }
}
