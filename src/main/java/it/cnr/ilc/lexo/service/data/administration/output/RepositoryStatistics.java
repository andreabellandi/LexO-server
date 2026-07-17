package it.cnr.ilc.lexo.service.data.administration.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete statistics for the lexical and text GraphDB repositories. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepositoryStatistics implements Data {

    public LexicalRepository lexicalRepository;
    public TextRepository textRepository;

    public static class RepositorySummary {
        public String name;
        public long totalStatements;
        public long inferredStatements;
        public long explicitStatements;
        public Double expansionRatio;
    }

    public static class LexicalRepository extends RepositorySummary {
        public long lexiconCount;
        public List<LexicalResource> lexicons = new ArrayList<LexicalResource>();
        public long lexicalEntryCount;
        public long lexicalSenseCount;
        public long dictionaryCount;
        public List<LexicalResource> dictionaries = new ArrayList<LexicalResource>();
        public long dictionaryEntryCount;
        public long attestationCount;
    }

    public static class LexicalResource {
        public String iri;
        public List<RdfValue> descriptions = new ArrayList<RdfValue>();
        public List<RdfValue> languages = new ArrayList<RdfValue>();
    }

    public static class TextRepository extends RepositorySummary {
        public long corpusCount;
        public long textCount;
        public List<Corpus> corpora = new ArrayList<Corpus>();
        public long unassignedTextCount;
        public List<Text> unassignedTexts = new ArrayList<Text>();
    }

    public static class Corpus {
        public String iri;
        public Map<String, List<RdfValue>> metadata =
                new LinkedHashMap<String, List<RdfValue>>();
        public long textCount;
        public List<Text> texts = new ArrayList<Text>();
    }

    public static class Text {
        public String iri;
        public Map<String, List<RdfValue>> metadata =
                new LinkedHashMap<String, List<RdfValue>>();
    }

    /** Preserves whether a metadata value is an IRI or a literal. */
    public static class RdfValue {
        public String value;
        public String kind;
        public String language;
        public String datatype;
    }
}
