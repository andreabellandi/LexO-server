package it.cnr.ilc.lexo.manager.text;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.Sentence;
import it.cnr.ilc.lexo.manager.text.model.Token;
import java.util.ArrayList;
import java.util.List;

/** Serializable document used at the Lucene boundary and by safe rebuilds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class CorpusIndexDocument {

    public String fileId;
    public String contextIRI;
    public String corpusIRI;
    public String graphIRI;
    public String content;
    public String contentHash;
    public String segmentationHash;
    public String segmentationSource;
    public String language;
    public String analyzerProfile;
    public String tokenizerProfile;
    public String sentenceSplitterProfile;
    public String segmentationSchemaVersion;
    public List<TokenData> tokens = new ArrayList<TokenData>();
    public List<SentenceData> sentences = new ArrayList<SentenceData>();

    public static CorpusIndexDocument from(String fileId, String contextIRI,
                                           String corpusIRI, String graphIRI,
                                           ParsedTextDocument document) {
        CorpusIndexDocument result = new CorpusIndexDocument();
        result.fileId = fileId;
        result.contextIRI = contextIRI;
        result.corpusIRI = corpusIRI;
        result.graphIRI = graphIRI;
        result.content = document.cleanText;
        result.contentHash = document.contentHash;
        result.segmentationHash = document.segmentationHash;
        result.segmentationSource = document.segmentationSource.name();
        result.language = document.metadata.get("language");
        result.analyzerProfile = "SURFACE_AND_LOWERCASE";
        result.tokenizerProfile = document.tokenizerProfile;
        result.sentenceSplitterProfile = document.sentenceSplitterProfile;
        result.segmentationSchemaVersion = document.segmentationSchemaVersion;
        for (Sentence sentence : document.sentences) {
            SentenceData data = new SentenceData();
            data.index = sentence.ordinal - 1;
            data.startUtf16 = sentence.beginChar;
            data.endUtf16 = sentence.endChar;
            data.beginIndex = UnicodeOffsetMapper.utf16ToCodePoint(
                    document.cleanText, sentence.beginChar);
            data.endIndex = UnicodeOffsetMapper.utf16ToCodePoint(
                    document.cleanText, sentence.endChar);
            result.sentences.add(data);
        }
        for (Token token : document.tokens) {
            TokenData data = new TokenData();
            data.position = token.ordinal - 1;
            data.surface = token.text;
            data.indexedTerm = token.indexedText == null
                    ? token.text : token.indexedText;
            data.startUtf16 = token.beginChar;
            data.endUtf16 = token.endChar;
            data.beginIndex = UnicodeOffsetMapper.utf16ToCodePoint(
                    document.cleanText, token.beginChar);
            data.endIndex = UnicodeOffsetMapper.utf16ToCodePoint(
                    document.cleanText, token.endChar);
            data.sentenceIndex = token.sentence.ordinal - 1;
            data.lemma = token.lemma;
            data.pos = token.upos;
            result.tokens.add(data);
        }
        return result;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class TokenData {
        public int position;
        public String surface;
        public String indexedTerm;
        public int startUtf16;
        public int endUtf16;
        public int beginIndex;
        public int endIndex;
        public int sentenceIndex;
        public String lemma;
        public String pos;
    }

    public static final class SentenceData {
        public int index;
        public int startUtf16;
        public int endUtf16;
        public int beginIndex;
        public int endIndex;
    }
}
