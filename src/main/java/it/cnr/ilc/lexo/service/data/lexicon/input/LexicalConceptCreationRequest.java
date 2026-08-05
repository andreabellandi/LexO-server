package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import java.util.List;

/** Request body for atomic lexical concept creation. */
@ApiModel(description = "A lexical concept with multilingual labels, definitions, and optional links")
public class LexicalConceptCreationRequest implements Data {

    @ApiModelProperty(value = "one or more preferred labels", required = true)
    public List<LexicalConceptLabel> label;

    @ApiModelProperty(value = "optional alternative labels")
    public List<LexicalConceptLabel> alternativeLabel;

    @ApiModelProperty(value = "optional hidden labels")
    public List<LexicalConceptLabel> hiddenLabel;

    @ApiModelProperty(value = "optional definitions")
    public List<LexicalConceptLabel> definition;

    @ApiModelProperty(value = "unsupported legacy sense IRI list; use senses with a language for each item",
            hidden = true)
    @Deprecated
    public List<String> senseId;

    @ApiModelProperty(value = "optional existing lexical senses with their ISO 639 language graphs")
    public List<LexicalConceptSenseLink> senses;

    @ApiModelProperty(value = "optional existing parent ontolex:LexicalConcept IRI")
    public String parent;

    @ApiModelProperty(value = "optional existing ontolex:ConceptSet IRI")
    public String conceptSetId;

    @ApiModelProperty(value = "optional custom RDF metadata in the common property/value shape")
    public List<RdfMetadataProperty> metadata;

    public LexicalConceptCreationRequest() {
    }
}
