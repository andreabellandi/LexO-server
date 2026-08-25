package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.manager.text.model.ValidationIssue;
import it.cnr.ilc.lexo.service.data.Data;
import java.util.List;

/** Status of one independently converted document in a bulk text job. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkTextJobItem implements Data {

    public String fileId;
    public String originalFileName;
    public String inputType;
    public String corpusId;
    public String state;
    public int progress;
    public String message;
    public String resultId;
    public List<ValidationIssue> issues;
    public String attestationState;
    public Integer attestationTotal;
    public Integer savedAttestations;
    public List<UnsavedAttestation> unsavedAttestations;
}
