package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Presence-aware request body for a partial lexical concept update. */
@ApiModel(description = "Partial lexical concept update; omitted fields remain unchanged")
public class LexicalConceptUpdateRequest implements Data {

    private String lexicalConcept;
    private String expectedModified;
    private List<LexicalConceptLabel> label;
    private List<LexicalConceptLabel> alternativeLabel;
    private List<LexicalConceptLabel> hiddenLabel;
    private List<LexicalConceptLabel> definition;
    private List<LexicalConceptSenseLink> senses;
    private List<String> senseId;
    private String parent;
    private String conceptSetId;
    private boolean expectedModifiedPresent;
    private boolean labelPresent;
    private boolean alternativeLabelPresent;
    private boolean hiddenLabelPresent;
    private boolean definitionPresent;
    private boolean sensesPresent;
    private boolean legacySenseIdPresent;
    private boolean parentPresent;
    private boolean conceptSetIdPresent;

    public LexicalConceptUpdateRequest() {
    }

    @ApiModelProperty(value = "absolute IRI of the lexical concept", required = true)
    public String getLexicalConcept() {
        return lexicalConcept;
    }

    public void setLexicalConcept(String lexicalConcept) {
        this.lexicalConcept = lexicalConcept;
    }

    @ApiModelProperty(value = "optional current dcterms:modified value used for optimistic concurrency")
    public String getExpectedModified() {
        return expectedModified;
    }

    public void setExpectedModified(String expectedModified) {
        this.expectedModified = expectedModified;
        this.expectedModifiedPresent = true;
    }

    @ApiModelProperty(value = "replacement preferred labels; when supplied the list must not be empty")
    public List<LexicalConceptLabel> getLabel() {
        return label;
    }

    public void setLabel(List<LexicalConceptLabel> label) {
        this.label = label;
        this.labelPresent = true;
    }

    @ApiModelProperty(value = "replacement alternative labels; an empty list removes them")
    public List<LexicalConceptLabel> getAlternativeLabel() {
        return alternativeLabel;
    }

    public void setAlternativeLabel(List<LexicalConceptLabel> alternativeLabel) {
        this.alternativeLabel = alternativeLabel;
        this.alternativeLabelPresent = true;
    }

    @ApiModelProperty(value = "replacement hidden labels; an empty list removes them")
    public List<LexicalConceptLabel> getHiddenLabel() {
        return hiddenLabel;
    }

    public void setHiddenLabel(List<LexicalConceptLabel> hiddenLabel) {
        this.hiddenLabel = hiddenLabel;
        this.hiddenLabelPresent = true;
    }

    @ApiModelProperty(value = "replacement definitions; an empty list removes them")
    public List<LexicalConceptLabel> getDefinition() {
        return definition;
    }

    public void setDefinition(List<LexicalConceptLabel> definition) {
        this.definition = definition;
        this.definitionPresent = true;
    }

    @ApiModelProperty(value = "unsupported legacy sense IRI list; use senses with a language for each item",
            hidden = true)
    @Deprecated
    public List<String> getSenseId() {
        return senseId;
    }

    @Deprecated
    public void setSenseId(List<String> senseId) {
        this.senseId = senseId;
        this.legacySenseIdPresent = true;
    }

    @ApiModelProperty(value = "replacement lexical senses with their ISO 639 language graphs; an empty list removes all links")
    public List<LexicalConceptSenseLink> getSenses() {
        return senses;
    }

    public void setSenses(List<LexicalConceptSenseLink> senses) {
        this.senses = senses;
        this.sensesPresent = true;
    }

    @ApiModelProperty(value = "replacement parent lexical concept IRI; explicit null removes it")
    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
        this.parentPresent = true;
    }

    @ApiModelProperty(value = "replacement concept set IRI; explicit null removes it")
    public String getConceptSetId() {
        return conceptSetId;
    }

    public void setConceptSetId(String conceptSetId) {
        this.conceptSetId = conceptSetId;
        this.conceptSetIdPresent = true;
    }

    public boolean hasExpectedModified() {
        return expectedModifiedPresent;
    }

    public boolean hasLabel() {
        return labelPresent;
    }

    public boolean hasAlternativeLabel() {
        return alternativeLabelPresent;
    }

    public boolean hasHiddenLabel() {
        return hiddenLabelPresent;
    }

    public boolean hasDefinition() {
        return definitionPresent;
    }

    public boolean hasSenses() {
        return sensesPresent;
    }

    public boolean hasLegacySenseId() {
        return legacySenseIdPresent;
    }

    public boolean hasParent() {
        return parentPresent;
    }

    public boolean hasConceptSetId() {
        return conceptSetIdPresent;
    }
}
