package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact description of a text exposed by the text catalog service. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextCatalogItem implements Data {

    public String fileId;
    public String documentUri;
    public String name;
    @ApiModelProperty(value = "UTF-8 byte size of the canonical nif:isString value")
    public Long sizeBytes;
    @ApiModelProperty(value = "number of NIF sentences, omitted when unavailable")
    public Integer sentenceCount;
    @ApiModelProperty(value = "number of NIF tokens, omitted when unavailable")
    public Integer tokenCount;
    @ApiModelProperty(value = "number of distinct FRAC attestations whose locus points to this text")
    public Long attestationCount = Long.valueOf(0L);
    @ApiModelProperty(value = "number of distinct Web Annotations stored for this text")
    public Long annotationCount = Long.valueOf(0L);
    public String corpusId;
    public String corpusUri;
    public Map<String, String> metadata = new LinkedHashMap<String, String>();
    public Map<String, List<String>> metadataValues =
            new LinkedHashMap<String, List<String>>();
}
