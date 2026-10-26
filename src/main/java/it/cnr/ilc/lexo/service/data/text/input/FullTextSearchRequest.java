package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModelProperty;

public class FullTextSearchRequest extends CorpusSelection {
    @ApiModelProperty(required = true,
            value = "query text; BOOLEAN uses the Lucene query syntax",
            example = "\"mercato del lavoro\" OR lavor*")
    public String query;
    @ApiModelProperty(value = "query syntax",
            allowableValues = "TERM, PHRASE, BOOLEAN, PREFIX, WILDCARD, FUZZY",
            example = "BOOLEAN")
    public String queryType = "TERM";
    @ApiModelProperty(value = "indexed field to search",
            allowableValues = "TOKEN, LEMMA", example = "TOKEN")
    public String searchField = "TOKEN";
    @ApiModelProperty(value = "when LEMMA is selected, reject a corpus subset containing texts without usable lemmas instead of returning partial results",
            example = "true")
    public Boolean requireCompleteLemmaCoverage = Boolean.TRUE;
    public boolean caseSensitive;
    public Integer leftContext;
    public Integer rightContext;
    public String contextUnit = "TOKEN";
    public String boundary = "DOCUMENT";
    public Integer pageSize;
    public String cursor;
    public boolean includeMetadata;
}
