package it.cnr.ilc.lexo.service.data.text.input;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit persistence request; searches never create RDF implicitly. */
public class CollocationPersistRequest {
    public String head;
    public String collocate;
    public String observedIn;
    public String author;
    public Double frequency;
    public Map<String, Double> metrics = new LinkedHashMap<String, Double>();
}
