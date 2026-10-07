package it.cnr.ilc.lexo.service.data.text.input;

import java.util.ArrayList;
import java.util.List;

public class CollocateExtractionRequest extends CorpusSelection {
    public String node;
    public Integer leftWindow;
    public Integer rightWindow;
    public String direction = "BOTH";
    public String boundary = "DOCUMENT";
    public boolean caseSensitive;
    public Integer minCooccurrenceFrequency = Integer.valueOf(2);
    public Long minFrequency;
    public Double minScore;
    public List<String> metrics = new ArrayList<String>();
    public String sortBy = "LOG_DICE";
    public String sortOrder = "DESC";
    public Integer limit = Integer.valueOf(100);
    public List<String> excludeTerms = new ArrayList<String>();
    public List<String> stoplist = new ArrayList<String>();
}
