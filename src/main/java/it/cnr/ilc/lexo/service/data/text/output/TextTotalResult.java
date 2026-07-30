package it.cnr.ilc.lexo.service.data.text.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Result of replacing one FRAC total. */
@ApiModel(description = "Persisted FRAC total of a text or corpus")
public class TextTotalResult implements Data {

    @ApiModelProperty(value = "text fileId or corpusId")
    public String id;
    @ApiModelProperty(value = "text or corpus IRI carrying frac:total")
    public String resource;
    @ApiModelProperty(value = "persisted xsd:int value")
    public Integer value;
    @ApiModelProperty(value = "full IRI persisted as frac:unit")
    public String unit;

    public TextTotalResult() {
    }
}
