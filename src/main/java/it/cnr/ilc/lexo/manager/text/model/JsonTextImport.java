package it.cnr.ilc.lexo.manager.text.model;

import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strictly validated JSON document accepted by the bulk text import. */
public final class JsonTextImport {

    public boolean metadataPresent;
    public final Map<String, List<String>> metadata =
            new LinkedHashMap<String, List<String>>();
    public String corpusId;
    public String textType;
    public String content;
    public final List<AnnotatedToken> tokens = new ArrayList<AnnotatedToken>();
    public final List<AnnotatedSentence> sentences = new ArrayList<AnnotatedSentence>();
    public final List<AttestationInput> attestations =
            new ArrayList<AttestationInput>();

    /** One imported FRAC attestation associated with the JSON document. */
    public static final class AttestationInput {

        public String id;
        public String observable;
        public String type;
        public String value;
        public String gloss;
        public Integer start;
        public Integer end;
        public List<RdfMetadataProperty> metadata;
    }

    public static final class AnnotatedToken {
        public int start;
        public int end;
        public Integer sentence;
        public String text;
        public String lemma;
        public String pos;
    }

    public static final class AnnotatedSentence {
        public int start;
        public int end;
        public String text;
    }
}
