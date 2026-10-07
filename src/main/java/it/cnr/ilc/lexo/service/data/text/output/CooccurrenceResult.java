package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CooccurrenceResult {
    public long totalPairs;
    public String nextCursor;
    public List<Pair> occurrences = new ArrayList<Pair>();

    public static class Pair {
        public String contextIRI;
        public PositionedTerm node;
        public PositionedTerm collocate;
        public int signedDistance;
        public int absoluteDistance;
        public int gap;
        public String direction;
    }

    public static class PositionedTerm {
        public String text;
        public int tokenPosition;
        public int sentenceIndex;
        public int beginIndex;
        public int endIndex;
    }
}
