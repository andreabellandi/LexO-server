package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextOccurrence {
    public String contextIRI;
    public Match match;
    public String leftContext;
    public String rightContext;
    public String kwic;
    public Float score;
    public String contentHash;
    public String segmentationHash;
    public Map<String, List<String>> metadata;

    public static class Match {
        public String text;
        public int beginIndex;
        public int endIndex;
        public int startToken;
        public int endToken;
        public int sentenceIndex;
    }
}
