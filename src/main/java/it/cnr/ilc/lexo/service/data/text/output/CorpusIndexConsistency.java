package it.cnr.ilc.lexo.service.data.text.output;

import java.util.ArrayList;
import java.util.List;

public class CorpusIndexConsistency {
    public boolean consistent;
    public List<String> missingInLucene = new ArrayList<String>();
    public List<String> missingInRdf = new ArrayList<String>();
    public List<String> contentHashMismatch = new ArrayList<String>();
    public List<String> segmentationHashMismatch = new ArrayList<String>();
    public List<String> tokenCountMismatch = new ArrayList<String>();
    public List<String> sentenceCountMismatch = new ArrayList<String>();
}
