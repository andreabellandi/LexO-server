package it.cnr.ilc.lexo.service.data.lexicon.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Presence-aware request body for a partial lexical entry update. */
@ApiModel(description = "Partial lexical entry update; omitted fields remain unchanged")
public class LexicalEntryUpdateRequest implements Data {

    private String entry;
    private String language;
    private String expectedModified;
    private String label;
    private String type;
    private String pos;
    private boolean expectedModifiedPresent;
    private boolean labelPresent;
    private boolean typePresent;
    private boolean posPresent;

    public LexicalEntryUpdateRequest() {
    }

    @ApiModelProperty(value = "absolute IRI of the lexical entry", required = true)
    public String getEntry() {
        return entry;
    }

    public void setEntry(String entry) {
        this.entry = entry;
    }

    @ApiModelProperty(value = "ISO 639 code selecting the lexical named graph",
            required = true, example = "it")
    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    @ApiModelProperty(value = "optional current dcterms:modified value used for optimistic concurrency")
    public String getExpectedModified() {
        return expectedModified;
    }

    public void setExpectedModified(String expectedModified) {
        this.expectedModified = expectedModified;
        this.expectedModifiedPresent = true;
    }

    @ApiModelProperty(value = "replacement entry label; omission preserves the current labels",
            example = "abitazione")
    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
        this.labelPresent = true;
    }

    @ApiModelProperty(value = "replacement lexical entry RDF type")
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
        this.typePresent = true;
    }

    @ApiModelProperty(value = "replacement part-of-speech IRI; explicit null removes it")
    public String getPos() {
        return pos;
    }

    public void setPos(String pos) {
        this.pos = pos;
        this.posPresent = true;
    }

    public boolean hasExpectedModified() {
        return expectedModifiedPresent;
    }

    public boolean hasLabel() {
        return labelPresent;
    }

    public boolean hasType() {
        return typePresent;
    }

    public boolean hasPos() {
        return posPresent;
    }
}
