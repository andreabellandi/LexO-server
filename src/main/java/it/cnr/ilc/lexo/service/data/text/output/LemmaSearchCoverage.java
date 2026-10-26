package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModelProperty;
import java.util.ArrayList;
import java.util.List;

/** Explicit coverage report for a lemma search over a corpus subset. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LemmaSearchCoverage {
    @ApiModelProperty(value = "number of indexed contexts selected by the request")
    public int requestedContextCount;
    @ApiModelProperty(value = "number of selected contexts containing at least one usable lemma")
    public int searchedContextCount;
    @ApiModelProperty(value = "number of selected contexts excluded because they contain no usable lemma")
    public int excludedContextCount;
    @ApiModelProperty(value = "number of surface tokens in the selected contexts")
    public long tokenCount;
    @ApiModelProperty(value = "number of selected tokens carrying a usable lemma")
    public long lemmaTokenCount;
    @ApiModelProperty(value = "lemmaTokenCount divided by tokenCount")
    public double lemmaCoverage;
    public List<String> searchedContextIRIs = new ArrayList<String>();
    public List<String> excludedContextIRIs = new ArrayList<String>();
}
