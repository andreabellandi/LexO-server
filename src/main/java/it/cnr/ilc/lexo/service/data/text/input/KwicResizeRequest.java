package it.cnr.ilc.lexo.service.data.text.input;

import java.util.ArrayList;
import java.util.List;

public class KwicResizeRequest {
    public List<HitReference> hits = new ArrayList<HitReference>();
    public Integer leftContext;
    public Integer rightContext;
    public String contextUnit = "TOKEN";
    public String boundary = "DOCUMENT";

    public static class HitReference {
        public String contextIRI;
        public Integer beginIndex;
        public Integer endIndex;
        public Integer startToken;
        public Integer endToken;
        public String contentHash;
        public String segmentationHash;
    }
}
