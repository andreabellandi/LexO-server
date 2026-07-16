package it.cnr.ilc.lexo.service.data.text.output;

import it.cnr.ilc.lexo.manager.text.model.ValidationIssue;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TextRecord {
    public String fileId;
    public String documentUri;
    public String corpusId;
    public String corpusUri;
    public String segmentationMethod;
    public Boolean frontMatterPresent;
    public String originalFileName;
    public String conlluFileName;
    public String originalPath;
    public String canonicalPath;
    public String nifPath;
    public String nifGraph;
    public String metadataPath;
    public String conlluPath;
    public String createdAt;
    public String completedAt;
    public Integer headingCount;
    public Integer paragraphCount;
    public Integer sentenceCount;
    public Integer tokenCount;
    public Map<String, String> metadata = new LinkedHashMap<String, String>();
    public Map<String, List<String>> metadataValues =
            new LinkedHashMap<String, List<String>>();
    public List<ValidationIssue> warnings = new ArrayList<ValidationIssue>();
}
