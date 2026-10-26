package it.cnr.ilc.lexo.service.data.text.input;

import io.swagger.annotations.ApiModelProperty;

public class FullTextSearchRequest extends CorpusSelection {
    @ApiModelProperty(required = true,
            value = "query text; BOOLEAN uses the original Lucene syntax with case-sensitive uppercase AND, OR and NOT operators",
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
    @ApiModelProperty(value = "selects the case-preserving fields when true; when false only query terms are normalized and Lucene operators remain case-sensitive",
            example = "false")
    public boolean caseSensitive;
    public Integer leftContext;
    public Integer rightContext;
    public String contextUnit = "TOKEN";
    public String boundary = "DOCUMENT";
    public Integer pageSize;
    public String cursor;
    public boolean includeMetadata;
}
