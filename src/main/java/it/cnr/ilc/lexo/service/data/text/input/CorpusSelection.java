package it.cnr.ilc.lexo.service.data.text.input;

import java.util.ArrayList;
import java.util.List;

/** Optional Lucene/RDF corpus subset shared by textual analytics requests. */
public class CorpusSelection {
    public String contextIRI;
    public String corpusIRI;
    public String graphIRI;
    public List<String> contextIRIs = new ArrayList<String>();
    public List<String> corpusIRIs = new ArrayList<String>();
    public List<String> graphIRIs = new ArrayList<String>();
}
