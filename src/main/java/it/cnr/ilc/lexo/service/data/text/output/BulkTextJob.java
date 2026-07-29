package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Aggregate response for an asynchronous bulk text conversion. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkTextJob implements Data {

    public String bulkId;
    public String state;
    public String language;
    public String corpusId;
    public int total;
    public int completed;
    public int failed;
    public int cancelled;
    public List<BulkTextJobItem> items = new ArrayList<BulkTextJobItem>();
}
