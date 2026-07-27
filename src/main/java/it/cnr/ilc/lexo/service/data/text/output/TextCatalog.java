package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Response returned by the text catalog service. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextCatalog implements Data {

    public String corpusId;
    public int total;
    public List<TextCatalogItem> texts = new ArrayList<TextCatalogItem>();
}
