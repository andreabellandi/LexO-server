package it.cnr.ilc.lexo.service.data.attestation;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** RDF value used by attestation metadata input and output. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "One RDF value of an attestation metadata property")
public class AttestationMetadataValue implements Data {

    @ApiModelProperty(value = "lexical form of the literal or absolute IRI",
            required = true)
    public String value;
    @ApiModelProperty(value = "RDF value kind: literal or iri", required = true,
            allowableValues = "literal,iri")
    public String type;
    @ApiModelProperty(value = "optional BCP 47 language tag for a literal")
    public String language;
    @ApiModelProperty(value = "optional absolute datatype IRI for a literal")
    public String datatype;

    public AttestationMetadataValue() {
    }

    public AttestationMetadataValue(String value, String type, String language,
                                    String datatype) {
        this.value = value;
        this.type = type;
        this.language = language;
        this.datatype = datatype;
    }
}
