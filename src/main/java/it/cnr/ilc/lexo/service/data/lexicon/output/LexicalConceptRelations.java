package it.cnr.ilc.lexo.service.data.lexicon.output;

import io.swagger.annotations.ApiModel;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Hierarchical lexical concept relations kept distinct by SKOS predicate. */
@ApiModel(description = "Direct and transitive lexical concept relations")
public class LexicalConceptRelations implements Data {

    public List<LexicalConceptRelation> direct =
            new ArrayList<LexicalConceptRelation>();
    public List<LexicalConceptRelation> transitive =
            new ArrayList<LexicalConceptRelation>();

    public LexicalConceptRelations() {
    }
}
