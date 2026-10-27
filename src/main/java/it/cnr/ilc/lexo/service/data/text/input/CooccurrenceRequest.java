package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModelProperty;

public class CooccurrenceRequest extends CorpusSelection {
    @ApiModelProperty(required = true, value = "node value in nodeField")
    public String node;
    @ApiModelProperty(value = "token attribute used to match node",
            allowableValues = "TOKEN, LEMMA, POS", example = "TOKEN")
    public String nodeField = "TOKEN";
    @ApiModelProperty(required = true, value = "collocate value in collocateField")
    public String collocate;
    @ApiModelProperty(value = "token attribute used to match collocate",
            allowableValues = "TOKEN, LEMMA, POS", example = "TOKEN")
    public String collocateField = "TOKEN";
    public Integer leftWindow;
    public Integer rightWindow;
    public String direction = "BOTH";
    public String boundary = "DOCUMENT";
    public boolean caseSensitive;
    public boolean includeOccurrences = true;
    public Integer pageSize;
    public String cursor;
}
