package it.cnr.ilc.lexo.service.data.text.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CorpusRecord {
    public String corpusId;
    public String corpusUri;
    public String originalFileName;
    public String originalPath;
    public String nifPath;
    public String nifGraph;
    public String metadataPath;
    public String createdAt;
    public String updatedAt;
    public Map<String, String> metadata = new LinkedHashMap<String, String>();
    public Map<String, List<String>> metadataValues =
            new LinkedHashMap<String, List<String>>();
    public List<String> documentIds = new ArrayList<String>();
    public List<String> documentUris = new ArrayList<String>();
}
