package it.cnr.ilc.lexo.service.data.text.input;

public class CooccurrenceRequest extends CorpusSelection {
    public String node;
    public String collocate;
    public Integer leftWindow;
    public Integer rightWindow;
    public String direction = "BOTH";
    public String boundary = "DOCUMENT";
    public boolean caseSensitive;
    public boolean includeOccurrences = true;
    public Integer pageSize;
    public String cursor;
}
