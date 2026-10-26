package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModelProperty;

/** Result of parsing a full-text query without executing it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FullTextQueryValidationResult {
    public boolean valid;
    @ApiModelProperty(value = "trimmed query accepted by the search parser")
    public String normalizedQuery;
    public String code;
    public String message;
    @ApiModelProperty(value = "one-based parser column when Lucene reports it")
    public Integer position;
}
