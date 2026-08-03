package it.cnr.ilc.lexo.service.data.lexicon.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import java.util.List;

/** Identifiers and audit data returned after lexical concept creation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "Created lexical concept and its accepted links")
public class LexicalConceptCreationResult implements Data {

    @ApiModelProperty(value = "IRI of the created lexical concept")
    public String lexicalConcept;

    @ApiModelProperty(value = "resolved creator")
    public String author;

    @ApiModelProperty(value = "creation timestamp")
    public String created;

    @ApiModelProperty(value = "linked lexical sense IRIs")
    public List<String> senseId;

    @ApiModelProperty(value = "parent lexical concept IRI")
    public String parent;

    @ApiModelProperty(value = "concept set IRI")
    public String conceptSetId;

    @ApiModelProperty(value = "created custom RDF metadata")
    public List<RdfMetadataProperty> metadata;

    public LexicalConceptCreationResult() {
    }
}
