package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;

/** Imported attestation that was not persisted after a successful text conversion. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@ApiModel(description = "One JSON-import attestation that could not be persisted")
public class UnsavedAttestation implements Data {

    @ApiModelProperty(value = "optional source identifier used only for result correlation")
    public String id;
    @ApiModelProperty(value = "IRI supplied as the observable")
    public String observable;
    @ApiModelProperty(value = "declared OntoLex observable type")
    public String type;
    @ApiModelProperty(value = "stable machine-readable failure code")
    public String code;
    @ApiModelProperty(value = "human-readable reason why the attestation was skipped")
    public String cause;
}
