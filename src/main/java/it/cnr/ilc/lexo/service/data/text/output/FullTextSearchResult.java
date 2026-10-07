package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FullTextSearchResult {
    public long totalOccurrences;
    public String nextCursor;
    public List<TextOccurrence> occurrences = new ArrayList<TextOccurrence>();
}
