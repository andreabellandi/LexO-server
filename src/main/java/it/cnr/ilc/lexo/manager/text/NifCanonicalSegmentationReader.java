package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.SegmentationSource;
import it.cnr.ilc.lexo.manager.text.model.Sentence;
import it.cnr.ilc.lexo.manager.text.model.Token;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;

/** Reconstructs the exact persisted segmentation without invoking a tokenizer. */
public final class NifCanonicalSegmentationReader {

    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private final String structureNamespace;

    public NifCanonicalSegmentationReader(String structureNamespace) {
        this.structureNamespace = namespace(structureNamespace);
    }

    public ReadResult read(String fileId, String graphIRI, Model model) {
        IRI isString = iri(NIF + "isString");
        Resource context = firstSubject(model, isString);
        if (context == null) {
            throw new IllegalArgumentException("NIF_CONTEXT_NOT_FOUND: " + fileId);
        }
        ParsedTextDocument document = new ParsedTextDocument();
        document.cleanText = literal(model, context, isString);
        if (document.cleanText == null) {
            throw new IllegalArgumentException("NIF_CANONICAL_TEXT_NOT_FOUND: " + fileId);
        }
        document.metadata.put("language", literal(model, context, DCTERMS.LANGUAGE));
        String source = literal(model, context, iri(structureNamespace + "segmentationSource"));
        document.segmentationMethod = literal(model, context,
                iri(structureNamespace + "segmentationMethod"));
        document.segmentationSource = parseSource(source,
                document.segmentationMethod);
        document.tokenizerProfile = fallback(literal(model, context,
                iri(structureNamespace + "tokenizerProfile")), "NIF_PERSISTED");
        document.sentenceSplitterProfile = fallback(literal(model, context,
                iri(structureNamespace + "sentenceSplitterProfile")), "NIF_PERSISTED");
        document.segmentationSchemaVersion = literal(model, context,
                iri(structureNamespace + "segmentationSchemaVersion"));
        document.contentHash = literal(model, context,
                iri(structureNamespace + "contentHash"));
        document.segmentationHash = literal(model, context,
                iri(structureNamespace + "segmentationHash"));

        List<Span> sentenceSpans = spans(model, NIF + "Sentence");
        Map<Resource, Sentence> sentenceByResource = new HashMap<Resource, Sentence>();
        for (int index = 0; index < sentenceSpans.size(); index++) {
            Span span = sentenceSpans.get(index);
            Sentence sentence = new Sentence();
            sentence.ordinal = index + 1;
            sentence.id = "sentence-" + sentence.ordinal;
            sentence.beginChar = UnicodeOffsetMapper.codePointToUtf16(
                    document.cleanText, span.begin);
            sentence.endChar = UnicodeOffsetMapper.codePointToUtf16(
                    document.cleanText, span.end);
            sentence.text = document.cleanText.substring(sentence.beginChar,
                    sentence.endChar);
            document.sentences.add(sentence);
            sentenceByResource.put(span.resource, sentence);
        }
        List<Span> tokenSpans = spans(model, NIF + "Word");
        for (int index = 0; index < tokenSpans.size(); index++) {
            Span span = tokenSpans.get(index);
            Token token = new Token();
            token.ordinal = index + 1;
            token.id = "token-" + token.ordinal;
            token.beginChar = UnicodeOffsetMapper.codePointToUtf16(
                    document.cleanText, span.begin);
            token.endChar = UnicodeOffsetMapper.codePointToUtf16(
                    document.cleanText, span.end);
            token.text = document.cleanText.substring(token.beginChar, token.endChar);
            token.indexedText = token.text;
            token.lemma = literal(model, span.resource, iri(NIF + "lemma"));
            Value sentenceValue = firstObject(model, span.resource, iri(NIF + "sentence"));
            token.sentence = sentenceValue instanceof Resource
                    ? sentenceByResource.get((Resource) sentenceValue) : null;
            if (token.sentence == null) {
                token.sentence = containing(document.sentences,
                        token.beginChar, token.endChar);
            }
            if (token.sentence == null) {
                throw new IllegalArgumentException(
                        "NIF_TOKEN_OUTSIDE_SENTENCE: " + span.resource);
            }
            token.sentence.tokens.add(token);
            document.tokens.add(token);
        }
        CanonicalSegmentationService.validate(document);
        String persistedContentHash = document.contentHash;
        String persistedSegmentationHash = document.segmentationHash;
        SegmentationFingerprint.complete(document);
        if (persistedContentHash != null
                && !persistedContentHash.equals(document.contentHash)) {
            throw new IllegalArgumentException("NIF_CONTENT_HASH_MISMATCH: " + fileId);
        }
        if (persistedSegmentationHash != null
                && !persistedSegmentationHash.equals(document.segmentationHash)) {
            throw new IllegalArgumentException("NIF_SEGMENTATION_HASH_MISMATCH: " + fileId);
        }
        ReadResult result = new ReadResult();
        result.fileId = fileId;
        result.contextIRI = context.stringValue();
        result.graphIRI = graphIRI;
        result.corpusIRI = iriObject(model, context, DCTERMS.IS_PART_OF);
        result.document = document;
        return result;
    }

    private List<Span> spans(Model model, String classIri) {
        List<Span> result = new ArrayList<Span>();
        for (Resource resource : model.filter(null, RDF.TYPE, iri(classIri)).subjects()) {
            Integer begin = integer(model, resource, iri(NIF + "beginIndex"));
            Integer end = integer(model, resource, iri(NIF + "endIndex"));
            if (begin != null && end != null) {
                result.add(new Span(resource, begin.intValue(), end.intValue()));
            }
        }
        Collections.sort(result, Comparator.comparingInt((Span span) -> span.begin)
                .thenComparingInt(span -> span.end)
                .thenComparing(span -> span.resource.stringValue()));
        return result;
    }

    private SegmentationSource parseSource(String value, String method) {
        if (value != null) {
            try {
                return SegmentationSource.valueOf(value);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if ("conllu".equalsIgnoreCase(method)) {
            return SegmentationSource.CONLLU;
        }
        if ("annotated-import".equalsIgnoreCase(method)) {
            return SegmentationSource.ANNOTATED_IMPORT;
        }
        return SegmentationSource.PERSISTED_NIF;
    }

    private static Sentence containing(List<Sentence> sentences, int begin, int end) {
        for (Sentence sentence : sentences) {
            if (begin >= sentence.beginChar && end <= sentence.endChar) {
                return sentence;
            }
        }
        return null;
    }

    private static Resource firstSubject(Model model, IRI predicate) {
        return model.filter(null, predicate, null).subjects().stream()
                .findFirst().orElse(null);
    }

    private static Value firstObject(Model model, Resource subject, IRI predicate) {
        return model.filter(subject, predicate, null).objects().stream()
                .findFirst().orElse(null);
    }

    private static String literal(Model model, Resource subject, IRI predicate) {
        Value value = firstObject(model, subject, predicate);
        return value instanceof Literal ? value.stringValue() : null;
    }

    private static Integer integer(Model model, Resource subject, IRI predicate) {
        Value value = firstObject(model, subject, predicate);
        try {
            return value instanceof Literal
                    ? Integer.valueOf(((Literal) value).intValue()) : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String iriObject(Model model, Resource subject, IRI predicate) {
        Value value = firstObject(model, subject, predicate);
        return value instanceof IRI ? value.stringValue() : null;
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String namespace(String value) {
        String effective = value == null || value.trim().isEmpty()
                ? "https://lexo.ilc.cnr.it/vocabulary/nif-structure#" : value.trim();
        return effective.endsWith("/") || effective.endsWith("#")
                ? effective : effective + "#";
    }

    private static final class Span {
        final Resource resource;
        final int begin;
        final int end;

        Span(Resource resource, int begin, int end) {
            this.resource = resource;
            this.begin = begin;
            this.end = end;
        }
    }

    public static final class ReadResult {
        public String fileId;
        public String contextIRI;
        public String corpusIRI;
        public String graphIRI;
        public ParsedTextDocument document;
    }
}
