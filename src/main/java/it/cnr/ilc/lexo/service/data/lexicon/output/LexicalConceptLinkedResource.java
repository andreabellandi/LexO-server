package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import java.util.ArrayList;
import java.util.List;

/** IRI and resolved display values of an entry or lexical sense. */
@ApiModel(description = "Linked lexical resource and its resolved labels")
public class LexicalConceptLinkedResource implements Data {

    @ApiModelProperty(value = "linked resource IRI")
    public String iri;
    @ApiModelProperty(value = "all values from the first available label fallback")
    public List<RdfMetadataValue> labels = new ArrayList<RdfMetadataValue>();

    public LexicalConceptLinkedResource() {
    }

    public LexicalConceptLinkedResource(String iri) {
        this.iri = iri;
    }
}
