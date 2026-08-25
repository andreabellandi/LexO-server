package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;

/** Status of one independently deleted text in a bulk deletion job. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextBulkDeletionItem implements Data {

    public String fileId;
    public String state;
    public String message;
}
