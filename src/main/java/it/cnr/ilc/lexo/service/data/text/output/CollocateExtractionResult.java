package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CollocateExtractionResult {
    public String node;
    public String nodeField;
    public String collocateField;
    public long totalTokens;
    public long nodeFrequency;
    public long pairSpace;
    public List<Collocate> collocates = new ArrayList<Collocate>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Collocate {
        public String term;
        public String surface;
        public String lemma;
        public String pos;
        public List<String> surfaceForms = new ArrayList<String>();
        public List<String> lemmas = new ArrayList<String>();
        public List<String> partsOfSpeech = new ArrayList<String>();
        public long fx;
        public long fy;
        public long fxy;
        public long documentFrequencyX;
        public long documentFrequencyY;
        public long documentsWithCooccurrence;
        public long pairN;
        public long pairFx;
        public long pairFy;
        public long pairFxy;
        public Map<String, MetricValue> metrics =
                new LinkedHashMap<String, MetricValue>();
    }
}
