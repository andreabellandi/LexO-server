package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.List;

public class CollocateExtractionRequest extends CorpusSelection {
    @ApiModelProperty(required = true, value = "node value in nodeField")
    public String node;
    @ApiModelProperty(value = "token attribute used to match node",
            allowableValues = "TOKEN, LEMMA, POS", example = "TOKEN")
    public String nodeField = "TOKEN";
    @ApiModelProperty(value = "token attribute used to group collocates and calculate their frequencies",
            allowableValues = "TOKEN, LEMMA, POS", example = "TOKEN")
    public String collocateField = "TOKEN";
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
