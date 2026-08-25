package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Aggregate status of an asynchronous bulk text deletion. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextBulkDeletionJob implements Data {

    public String bulkId;
    public String state;
    public int total;
    public int deleted;
    public int notFound;
    public int failed;
    public List<TextBulkDeletionItem> items =
            new ArrayList<TextBulkDeletionItem>();
}
