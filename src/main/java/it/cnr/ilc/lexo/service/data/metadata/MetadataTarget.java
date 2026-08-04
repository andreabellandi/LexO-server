package it.cnr.ilc.lexo.service.data.metadata;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Identifies an entity and the safe context used to resolve its named graph. */
@ApiModel(description = "Metadata target entity and graph-selection context")
public class MetadataTarget implements Data {

    @ApiModelProperty(value = "supported entity kind", required = true,
            allowableValues = "lexicalEntry,lexicalSense,form,lexicalConcept,attestation")
    public String entityType;
    @ApiModelProperty(value = "absolute IRI of the target resource", required = true)
    public String resource;
    @ApiModelProperty(value = "ISO 639 language required for lexicalEntry, lexicalSense, or form")
    public String language;
    @ApiModelProperty(value = "document identifier required for attestation")
    public String fileId;

    public MetadataTarget() {
    }
}
