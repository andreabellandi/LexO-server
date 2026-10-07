package it.cnr.ilc.lexo.service.data.text.output;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class CorpusIndexStatus {
    public boolean available;
    public Long documents;
    public String lastCommit;
    public String luceneVersion;
    public String tokenizerProfile;
    public String indexSchemaVersion;
    public String error;
}
