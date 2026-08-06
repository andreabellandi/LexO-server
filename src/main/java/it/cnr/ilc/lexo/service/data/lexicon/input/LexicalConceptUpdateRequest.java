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
    private List<LexicalConceptLabel> addLabels;
    private List<LexicalConceptLabel> removeLabels;
    private List<LexicalConceptLabel> addAlternativeLabels;
    private List<LexicalConceptLabel> removeAlternativeLabels;
    private List<LexicalConceptLabel> addHiddenLabels;
    private List<LexicalConceptLabel> removeHiddenLabels;
    private List<LexicalConceptLabel> addDefinitions;
    private List<LexicalConceptLabel> removeDefinitions;
    private List<LexicalConceptSenseLink> senses;
    private List<LexicalConceptSenseLink> addSenses;
    private List<String> removeSenseIds;
    private List<String> senseId;
    private String parent;
    private String conceptSetId;
    private boolean expectedModifiedPresent;
    private boolean labelPresent;
    private boolean alternativeLabelPresent;
    private boolean hiddenLabelPresent;
    private boolean definitionPresent;
    private boolean addLabelsPresent;
    private boolean removeLabelsPresent;
    private boolean addAlternativeLabelsPresent;
    private boolean removeAlternativeLabelsPresent;
    private boolean addHiddenLabelsPresent;
    private boolean removeHiddenLabelsPresent;
    private boolean addDefinitionsPresent;
    private boolean removeDefinitionsPresent;
    private boolean sensesPresent;
    private boolean addSensesPresent;
    private boolean removeSenseIdsPresent;
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

    @ApiModelProperty(value = "preferred labels to add without replacing existing labels")
    public List<LexicalConceptLabel> getAddLabels() {
        return addLabels;
    }

    public void setAddLabels(List<LexicalConceptLabel> addLabels) {
        this.addLabels = addLabels;
        this.addLabelsPresent = true;
    }

    @ApiModelProperty(value = "exact preferred labels to remove while preserving the others")
    public List<LexicalConceptLabel> getRemoveLabels() {
        return removeLabels;
    }

    public void setRemoveLabels(List<LexicalConceptLabel> removeLabels) {
        this.removeLabels = removeLabels;
        this.removeLabelsPresent = true;
    }

    @ApiModelProperty(value = "alternative labels to add without replacing existing labels")
    public List<LexicalConceptLabel> getAddAlternativeLabels() {
        return addAlternativeLabels;
    }

    public void setAddAlternativeLabels(
            List<LexicalConceptLabel> addAlternativeLabels) {
        this.addAlternativeLabels = addAlternativeLabels;
        this.addAlternativeLabelsPresent = true;
    }

    @ApiModelProperty(value = "exact alternative labels to remove while preserving the others")
    public List<LexicalConceptLabel> getRemoveAlternativeLabels() {
        return removeAlternativeLabels;
    }

    public void setRemoveAlternativeLabels(
            List<LexicalConceptLabel> removeAlternativeLabels) {
        this.removeAlternativeLabels = removeAlternativeLabels;
        this.removeAlternativeLabelsPresent = true;
    }

    @ApiModelProperty(value = "hidden labels to add without replacing existing labels")
    public List<LexicalConceptLabel> getAddHiddenLabels() {
        return addHiddenLabels;
    }

    public void setAddHiddenLabels(
            List<LexicalConceptLabel> addHiddenLabels) {
        this.addHiddenLabels = addHiddenLabels;
        this.addHiddenLabelsPresent = true;
    }

    @ApiModelProperty(value = "exact hidden labels to remove while preserving the others")
    public List<LexicalConceptLabel> getRemoveHiddenLabels() {
        return removeHiddenLabels;
    }

    public void setRemoveHiddenLabels(
            List<LexicalConceptLabel> removeHiddenLabels) {
        this.removeHiddenLabels = removeHiddenLabels;
        this.removeHiddenLabelsPresent = true;
    }

    @ApiModelProperty(value = "definitions to add without replacing existing definitions")
    public List<LexicalConceptLabel> getAddDefinitions() {
        return addDefinitions;
    }

    public void setAddDefinitions(
            List<LexicalConceptLabel> addDefinitions) {
        this.addDefinitions = addDefinitions;
        this.addDefinitionsPresent = true;
    }

    @ApiModelProperty(value = "exact definitions to remove while preserving the others")
    public List<LexicalConceptLabel> getRemoveDefinitions() {
        return removeDefinitions;
    }

    public void setRemoveDefinitions(
            List<LexicalConceptLabel> removeDefinitions) {
        this.removeDefinitions = removeDefinitions;
        this.removeDefinitionsPresent = true;
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

    @ApiModelProperty(value = "lexical senses to add without replacing existing links; each item selects its ISO 639 language graph")
    public List<LexicalConceptSenseLink> getAddSenses() {
        return addSenses;
    }

    public void setAddSenses(List<LexicalConceptSenseLink> addSenses) {
        this.addSenses = addSenses;
        this.addSensesPresent = true;
    }

    @ApiModelProperty(value = "lexical sense IRIs to unlink without changing other sense links")
    public List<String> getRemoveSenseIds() {
        return removeSenseIds;
    }

    public void setRemoveSenseIds(List<String> removeSenseIds) {
        this.removeSenseIds = removeSenseIds;
        this.removeSenseIdsPresent = true;
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

    public boolean hasAddLabels() {
        return addLabelsPresent;
    }

    public boolean hasRemoveLabels() {
        return removeLabelsPresent;
    }

    public boolean hasAddAlternativeLabels() {
        return addAlternativeLabelsPresent;
    }

    public boolean hasRemoveAlternativeLabels() {
        return removeAlternativeLabelsPresent;
    }

    public boolean hasAddHiddenLabels() {
        return addHiddenLabelsPresent;
    }

    public boolean hasRemoveHiddenLabels() {
        return removeHiddenLabelsPresent;
    }

    public boolean hasAddDefinitions() {
        return addDefinitionsPresent;
    }

    public boolean hasRemoveDefinitions() {
        return removeDefinitionsPresent;
    }

    public boolean hasSenses() {
        return sensesPresent;
    }

    public boolean hasAddSenses() {
        return addSensesPresent;
    }

    public boolean hasRemoveSenseIds() {
        return removeSenseIdsPresent;
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
