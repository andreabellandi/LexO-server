package it.cnr.ilc.lexo.service.data.attestation;

import io.swagger.annotations.ApiModel;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;

/** RDF value used by attestation metadata input and output. */
@ApiModel(description = "One RDF value of an attestation metadata property")
public class AttestationMetadataValue extends RdfMetadataValue {

    public AttestationMetadataValue() {
    }

    public AttestationMetadataValue(String value, String type, String language,
                                    String datatype) {
        super(value, type, language, datatype);
    }
}
