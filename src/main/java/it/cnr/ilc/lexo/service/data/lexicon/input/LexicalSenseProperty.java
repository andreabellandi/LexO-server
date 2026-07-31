package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
/** One property and its values for a new lexical sense. */
@ApiModel(description = "One RDF property of a lexical sense")
public class LexicalSenseProperty extends LexicalRdfProperty {

    public LexicalSenseProperty() {
    }
}
