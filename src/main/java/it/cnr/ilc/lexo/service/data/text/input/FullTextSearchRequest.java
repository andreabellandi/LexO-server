package it.cnr.ilc.lexo.service.data.text.input;

public class FullTextSearchRequest extends CorpusSelection {
    public String query;
    public String queryType = "TERM";
    public boolean caseSensitive;
    public Integer leftContext;
    public Integer rightContext;
    public String contextUnit = "TOKEN";
    public String boundary = "DOCUMENT";
    public Integer pageSize;
    public String cursor;
    public boolean includeMetadata;
}
