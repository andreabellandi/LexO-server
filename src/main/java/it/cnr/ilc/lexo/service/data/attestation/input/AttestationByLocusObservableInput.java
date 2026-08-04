package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import java.util.List;

/** One observable and its optional attestation metadata at a shared locus. */
@ApiModel(description = "One observed lexical entity and optional metadata for its attestation")
public class AttestationByLocusObservableInput implements Data {

    @ApiModelProperty(value = "IRI of the observed OntoLex lexical entity",
            example = "https://lexo.ilc.cnr.it#LexO_example", required = true)
    public String observable;

    @ApiModelProperty(value = "optional custom RDF metadata for this observable's attestation in the common property/value shape")
    public List<RdfMetadataProperty> metadata;

    public AttestationByLocusObservableInput() {
    }

    public AttestationByLocusObservableInput(String observable,
                                             List<RdfMetadataProperty> metadata) {
        this.observable = observable;
        this.metadata = metadata;
    }
}
