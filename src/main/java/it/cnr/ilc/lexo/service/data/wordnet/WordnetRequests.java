package it.cnr.ilc.lexo.service.data.wordnet;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import java.math.BigDecimal;
import java.util.List;

/** WordNet-specific request DTOs; RDF metadata reuses the common wire model. */
public final class WordnetRequests {

    private WordnetRequests() {
    }

    public static class NativeCreate implements Data {
        public List<String> notes;
        public String wordnetStatus;
        public BigDecimal confidenceScore;
        public List<RdfMetadataProperty> metadata;
    }

    public static class NativePatch implements Data {
        private List<String> notes;
        private String wordnetStatus;
        private BigDecimal confidenceScore;
        private String expectedModified;
        private boolean notesPresent;
        private boolean wordnetStatusPresent;
        private boolean confidenceScorePresent;
        private boolean expectedModifiedPresent;
        public MetadataPatchRequest metadata;

        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> value) { notes = value; notesPresent = true; }
        public boolean hasNotes() { return notesPresent; }
        public String getWordnetStatus() { return wordnetStatus; }
        public void setWordnetStatus(String value) { wordnetStatus = value; wordnetStatusPresent = true; }
        public boolean hasWordnetStatus() { return wordnetStatusPresent; }
        public BigDecimal getConfidenceScore() { return confidenceScore; }
        public void setConfidenceScore(BigDecimal value) { confidenceScore = value; confidenceScorePresent = true; }
        public boolean hasConfidenceScore() { return confidenceScorePresent; }
        public String getExpectedModified() { return expectedModified; }
        public void setExpectedModified(String value) { expectedModified = value; expectedModifiedPresent = true; }
        public boolean hasExpectedModified() { return expectedModifiedPresent; }
    }

    @ApiModel(description = "New WordNet root")
    public static final class WordnetCreate extends NativeCreate {
        @ApiModelProperty(required = true) public String wordnetId;
        @ApiModelProperty(required = true) public String language;
    }

    public static final class WordnetPatch extends NativePatch {
        private String wordnetId;
        private String language;
        private String graph;
        private String iri;
        private boolean wordnetIdPresent;
        private boolean languagePresent;
        private boolean graphPresent;
        private boolean iriPresent;
        public String getWordnetId() { return wordnetId; }
        public void setWordnetId(String value) { wordnetId = value; wordnetIdPresent = true; }
        public boolean hasWordnetId() { return wordnetIdPresent; }
        public String getLanguage() { return language; }
        public void setLanguage(String value) { language = value; languagePresent = true; }
        public boolean hasLanguage() { return languagePresent; }
        public String getGraph() { return graph; }
        public void setGraph(String value) { graph = value; graphPresent = true; }
        public boolean hasGraph() { return graphPresent; }
        public String getIri() { return iri; }
        public void setIri(String value) { iri = value; iriPresent = true; }
        public boolean hasIri() { return iriPresent; }
    }

    public static class FormData extends NativeCreate {
        public String writtenRep;
        public String script;
        public List<TagData> tags;
        public List<PronunciationData> pronunciations;
    }

    public static final class EntryCreate extends NativeCreate {
        public String partOfSpeech;
        public String index;
        public FormData canonicalForm;
        public List<FormData> otherForms;
        public List<SyntacticBehaviourData> syntacticBehaviours;
    }

    public static final class EntryPatch extends NativePatch {
        private String partOfSpeech;
        private String index;
        private String canonicalFormId;
        private boolean partOfSpeechPresent;
        private boolean indexPresent;
        private boolean canonicalFormIdPresent;
        public ChildChanges<SyntacticBehaviourData, SyntacticBehaviourUpdate> syntacticBehaviours;
        private String workflowStatus;
        private String workflowTransition;
        private String lexoStatus;
        private String rawStatus;
        private boolean workflowStatusPresent;
        private boolean workflowTransitionPresent;
        private boolean lexoStatusPresent;
        private boolean rawStatusPresent;

        public String getPartOfSpeech() { return partOfSpeech; }
        public void setPartOfSpeech(String value) { partOfSpeech = value; partOfSpeechPresent = true; }
        public boolean hasPartOfSpeech() { return partOfSpeechPresent; }
        public String getIndex() { return index; }
        public void setIndex(String value) { index = value; indexPresent = true; }
        public boolean hasIndex() { return indexPresent; }
        public String getCanonicalFormId() { return canonicalFormId; }
        public void setCanonicalFormId(String value) { canonicalFormId = value; canonicalFormIdPresent = true; }
        public boolean hasCanonicalFormId() { return canonicalFormIdPresent; }
        public String getWorkflowStatus() { return workflowStatus; }
        public void setWorkflowStatus(String value) { workflowStatus = value; workflowStatusPresent = true; }
        public boolean hasWorkflowStatus() { return workflowStatusPresent; }
        public String getWorkflowTransition() { return workflowTransition; }
        public void setWorkflowTransition(String value) { workflowTransition = value; workflowTransitionPresent = true; }
        public boolean hasWorkflowTransition() { return workflowTransitionPresent; }
        public String getLexoStatus() { return lexoStatus; }
        public void setLexoStatus(String value) { lexoStatus = value; lexoStatusPresent = true; }
        @JsonProperty("lexo:status")
        public void setLexoStatusPredicate(String value) { lexoStatus = value; lexoStatusPresent = true; }
        public boolean hasLexoStatus() { return lexoStatusPresent; }
        public String getStatus() { return rawStatus; }
        public void setStatus(String value) { rawStatus = value; rawStatusPresent = true; }
        public boolean hasRawStatus() { return rawStatusPresent; }
    }

    public static final class FormCreate extends FormData {
        public String entryId;
        public String kind;
    }

    public static final class FormPatch extends NativePatch {
        private String writtenRep;
        private String script;
        private boolean writtenRepPresent;
        private boolean scriptPresent;
        public ChildChanges<TagData, TagUpdate> tags;
        public ChildChanges<PronunciationData, PronunciationUpdate> pronunciations;
        private String entryId;
        private String kind;
        private boolean entryIdPresent;
        private boolean kindPresent;

        public String getWrittenRep() { return writtenRep; }
        public void setWrittenRep(String value) { writtenRep = value; writtenRepPresent = true; }
        public boolean hasWrittenRep() { return writtenRepPresent; }
        public String getScript() { return script; }
        public void setScript(String value) { script = value; scriptPresent = true; }
        public boolean hasScript() { return scriptPresent; }
        public String getEntryId() { return entryId; }
        public void setEntryId(String value) { entryId = value; entryIdPresent = true; }
        public boolean hasEntryId() { return entryIdPresent; }
        public String getKind() { return kind; }
        public void setKind(String value) { kind = value; kindPresent = true; }
        public boolean hasKind() { return kindPresent; }
    }

    public static final class SynsetCreate extends NativeCreate {
        public String partOfSpeech;
        public String ili;
        public String lexfile;
        public List<DefinitionData> definitions;
        public List<ExampleData> examples;
    }

    public static final class SynsetPatch extends NativePatch {
        private String partOfSpeech;
        private String ili;
        private String lexfile;
        private List<String> memberOrder;
        private boolean partOfSpeechPresent;
        private boolean iliPresent;
        private boolean lexfilePresent;
        private boolean memberOrderPresent;
        public ChildChanges<DefinitionData, DefinitionUpdate> definitions;
        public ChildChanges<ExampleData, ExampleUpdate> examples;

        public String getPartOfSpeech() { return partOfSpeech; }
        public void setPartOfSpeech(String value) { partOfSpeech = value; partOfSpeechPresent = true; }
        public boolean hasPartOfSpeech() { return partOfSpeechPresent; }
        public String getIli() { return ili; }
        public void setIli(String value) { ili = value; iliPresent = true; }
        public boolean hasIli() { return iliPresent; }
        public String getLexfile() { return lexfile; }
        public void setLexfile(String value) { lexfile = value; lexfilePresent = true; }
        public boolean hasLexfile() { return lexfilePresent; }
        public List<String> getMemberOrder() { return memberOrder; }
        public void setMemberOrder(List<String> value) { memberOrder = value; memberOrderPresent = true; }
        public boolean hasMemberOrder() { return memberOrderPresent; }
    }

    public static final class SenseCreate extends NativeCreate {
        public String entryId;
        public String synsetId;
        public Integer n;
        public List<ExampleData> examples;
        public List<CountData> counts;
        public List<String> subcategorizationIds;
    }

    public static final class SensePatch extends NativePatch {
        private String entryId;
        private String synsetId;
        private Integer n;
        private List<String> subcategorizationIds;
        private boolean entryIdPresent;
        private boolean synsetIdPresent;
        private boolean nPresent;
        private boolean subcategorizationIdsPresent;
        public ChildChanges<ExampleData, ExampleUpdate> examples;
        public ChildChanges<CountData, CountUpdate> counts;

        public String getEntryId() { return entryId; }
        public void setEntryId(String value) { entryId = value; entryIdPresent = true; }
        public boolean hasEntryId() { return entryIdPresent; }
        public String getSynsetId() { return synsetId; }
        public void setSynsetId(String value) { synsetId = value; synsetIdPresent = true; }
        public boolean hasSynsetId() { return synsetIdPresent; }
        public Integer getN() { return n; }
        public void setN(Integer value) { n = value; nPresent = true; }
        public boolean hasN() { return nPresent; }
        public List<String> getSubcategorizationIds() { return subcategorizationIds; }
        public void setSubcategorizationIds(List<String> value) { subcategorizationIds = value; subcategorizationIdsPresent = true; }
        public boolean hasSubcategorizationIds() { return subcategorizationIdsPresent; }
    }

    public static final class RelationCreate extends NativeCreate {
        public String scope;
        public String sourceId;
        public String targetId;
        public String category;
    }

    public static final class RelationPatch extends NativePatch {
        private String sourceId;
        private String targetId;
        private String category;
        private boolean sourceIdPresent;
        private boolean targetIdPresent;
        private boolean categoryPresent;
        private String scope;
        private boolean scopePresent;

        public String getSourceId() { return sourceId; }
        public void setSourceId(String value) { sourceId = value; sourceIdPresent = true; }
        public boolean hasSourceId() { return sourceIdPresent; }
        public String getTargetId() { return targetId; }
        public void setTargetId(String value) { targetId = value; targetIdPresent = true; }
        public boolean hasTargetId() { return targetIdPresent; }
        public String getCategory() { return category; }
        public void setCategory(String value) { category = value; categoryPresent = true; }
        public boolean hasCategory() { return categoryPresent; }
        public String getScope() { return scope; }
        public void setScope(String value) { scope = value; scopePresent = true; }
        public boolean hasScope() { return scopePresent; }
    }

    public static class ChildUpdate extends NativePatch {
        public String id;
    }

    public static class ChildChanges<C, U extends ChildUpdate> {
        public List<C> add;
        public List<U> update;
        public List<String> remove;

        public boolean hasOperations() {
            return add != null && !add.isEmpty()
                    || update != null && !update.isEmpty()
                    || remove != null && !remove.isEmpty();
        }
    }

    public static class DefinitionData extends NativeCreate {
        public String kind;
        public String text;
        public String language;
        public String sourceSenseId;
    }

    public static class ExampleData extends NativeCreate {
        public String text;
        public String language;
    }

    public static class CountData extends NativeCreate {
        public Integer value;
    }

    public static class TagData extends NativeCreate {
        public String category;
        public String value;
    }

    public static class PronunciationData extends NativeCreate {
        public String transcription;
        public String notation;
        public String variety;
        public Boolean phonemic;
        public String audio;
    }

    public static class SyntacticBehaviourData extends NativeCreate {
        public String label;
    }

    public static final class DefinitionUpdate extends ChildUpdate {
        private String kind;
        private String text;
        private String language;
        private String sourceSenseId;
        private boolean kindPresent;
        private boolean textPresent;
        private boolean languagePresent;
        private boolean sourceSenseIdPresent;
        public String getKind() { return kind; }
        public void setKind(String value) { kind = value; kindPresent = true; }
        public boolean hasKind() { return kindPresent; }
        public String getText() { return text; }
        public void setText(String value) { text = value; textPresent = true; }
        public boolean hasText() { return textPresent; }
        public String getLanguage() { return language; }
        public void setLanguage(String value) { language = value; languagePresent = true; }
        public boolean hasLanguage() { return languagePresent; }
        public String getSourceSenseId() { return sourceSenseId; }
        public void setSourceSenseId(String value) { sourceSenseId = value; sourceSenseIdPresent = true; }
        public boolean hasSourceSenseId() { return sourceSenseIdPresent; }
    }

    public static final class ExampleUpdate extends ChildUpdate {
        private String text;
        private String language;
        private boolean textPresent;
        private boolean languagePresent;
        public String getText() { return text; }
        public void setText(String value) { text = value; textPresent = true; }
        public boolean hasText() { return textPresent; }
        public String getLanguage() { return language; }
        public void setLanguage(String value) { language = value; languagePresent = true; }
        public boolean hasLanguage() { return languagePresent; }
    }

    public static final class CountUpdate extends ChildUpdate {
        private Integer value;
        private boolean valuePresent;
        public Integer getValue() { return value; }
        public void setValue(Integer item) { value = item; valuePresent = true; }
        public boolean hasValue() { return valuePresent; }
    }

    public static final class TagUpdate extends ChildUpdate {
        private String category;
        private String value;
        private boolean categoryPresent;
        private boolean valuePresent;
        public String getCategory() { return category; }
        public void setCategory(String item) { category = item; categoryPresent = true; }
        public boolean hasCategory() { return categoryPresent; }
        public String getValue() { return value; }
        public void setValue(String item) { value = item; valuePresent = true; }
        public boolean hasValue() { return valuePresent; }
    }

    public static final class PronunciationUpdate extends ChildUpdate {
        private String transcription;
        private String notation;
        private String variety;
        private Boolean phonemic;
        private String audio;
        private boolean transcriptionPresent;
        private boolean notationPresent;
        private boolean varietyPresent;
        private boolean phonemicPresent;
        private boolean audioPresent;
        public String getTranscription() { return transcription; }
        public void setTranscription(String item) { transcription = item; transcriptionPresent = true; }
        public boolean hasTranscription() { return transcriptionPresent; }
        public String getNotation() { return notation; }
        public void setNotation(String item) { notation = item; notationPresent = true; }
        public boolean hasNotation() { return notationPresent; }
        public String getVariety() { return variety; }
        public void setVariety(String item) { variety = item; varietyPresent = true; }
        public boolean hasVariety() { return varietyPresent; }
        public Boolean getPhonemic() { return phonemic; }
        public void setPhonemic(Boolean item) { phonemic = item; phonemicPresent = true; }
        public boolean hasPhonemic() { return phonemicPresent; }
        public String getAudio() { return audio; }
        public void setAudio(String item) { audio = item; audioPresent = true; }
        public boolean hasAudio() { return audioPresent; }
    }

    public static final class SyntacticBehaviourUpdate extends ChildUpdate {
        private String label;
        private boolean labelPresent;
        public String getLabel() { return label; }
        public void setLabel(String item) { label = item; labelPresent = true; }
        public boolean hasLabel() { return labelPresent; }
    }

    public static final class StatusBatch implements Data {
        public List<StatusChange> entries;
    }

    public static final class StatusChange implements Data {
        public String entryId;
        public String fromStatus;
        public String toStatus;
    }
}
