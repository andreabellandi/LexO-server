package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;

/** One RDF object supplied while creating a lexical sense. */
@ApiModel(description = "One literal or IRI value of a lexical sense property")
public class LexicalRdfValue extends RdfMetadataValue {

    public LexicalRdfValue() {
    }

    public LexicalRdfValue(String value, String type, String language,
                           String datatype) {
        super(value, type, language, datatype);
    }
}
