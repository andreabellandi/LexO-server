package it.cnr.ilc.lexo.service.data.attestation.output;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of an atomic observable replacement batch. */
@ApiModel(description = "Result of an atomic attestation observable update")
public class AttestationObservableUpdateResult implements Data {

    @ApiModelProperty(value = "id selecting the per-text attestation graph")
    public String fileId;
    @ApiModelProperty(value = "updated attestations")
    public List<AttestationObservableUpdateItem> updated =
            new ArrayList<AttestationObservableUpdateItem>();
    @ApiModelProperty(value = "updated per-text frequencies keyed by observable IRI; zero means that the frequency object was removed")
    public Map<String, Integer> frequencies =
            new LinkedHashMap<String, Integer>();

    public AttestationObservableUpdateResult() {
    }
}
