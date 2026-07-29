package it.cnr.ilc.lexo.service.data.attestation.input;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.List;

/** Input model for creating attestations of multiple observables at one locus. */
@ApiModel(description = "One textual locus and the lexical entities observed there")
public class AttestationByLocusInput implements Data {

    @ApiModelProperty(value = "string observed in the canonical NIF context",
            example = "gli stessi diritti", required = true)
    public String value;
    @ApiModelProperty(value = "inclusive Unicode code-point start offset",
            example = "42", required = true)
    public Integer start;
    @ApiModelProperty(value = "exclusive Unicode code-point end offset",
            example = "60", required = true)
    public Integer end;
    @ApiModelProperty(value = "IRIs of the OntoLex lexical entities observed at the locus",
            required = true)
    public List<String> observables = new ArrayList<String>();

    public AttestationByLocusInput() {
    }

    public AttestationByLocusInput(String value, Integer start, Integer end,
                                   List<String> observables) {
        this.value = value;
        this.start = start;
        this.end = end;
        this.observables = observables;
    }
}
