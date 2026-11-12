package it.cnr.ilc.lexo.manager.cpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.text.CollocateExtractionParameters;
import it.cnr.ilc.lexo.manager.text.CollocationStatisticsService;
import it.cnr.ilc.lexo.service.data.cpp.input.*;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/** Strict CPP-only JSON; never changes the public text-service JSON provider. */
public final class CppJsonCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    public static <T> T read(java.io.InputStream input, Class<T> type) {
        if (input == null) { throw new CppException(400,"CPP_BAD_JSON","JSON body is required"); }
        int maximum=CppResourceUris.limit("CPP.write.maxBytes",1048576);
        try (java.io.InputStream in=input; java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096]; int count;
            while ((count=in.read(buffer))!=-1) {
                if ((long)bytes.size()+count>maximum) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","JSON body exceeds the configured limit"); }
                bytes.write(buffer,0,count);
            }
            String raw=StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
            return read(raw,type);
        } catch (IOException e) { throw new CppException(400,"CPP_BAD_JSON","Cannot read a valid UTF-8 JSON body"); }
    }

    public static <T> T read(String raw, Class<T> type) {
        if (raw == null) { throw new CppException(400,"CPP_BAD_JSON","JSON body is required"); }
        if (raw.getBytes(StandardCharsets.UTF_8).length > CppResourceUris.limit("CPP.write.maxBytes", 1048576)) {
            throw new CppException(413,"CPP_LIMIT_EXCEEDED","JSON body exceeds the configured limit");
        }
        try {
            // Reject excessive nesting before constructing a tree (Jackson 2.12 has no depth constraint).
            try (JsonParser parser=JSON.getFactory().createParser(raw)) {
                int depth=0; com.fasterxml.jackson.core.JsonToken token;
                while ((token=parser.nextToken())!=null) {
                    if (token.isStructStart() && ++depth>8) { throw CppException.invalid("JSON nesting exceeds the CPP limit"); }
                    if (token.isStructEnd()) { depth--; }
                }
            }
            JsonNode tree = JSON.readTree(raw);
            shape(tree, type, 0);
            return JSON.treeToValue(tree, type);
        } catch (CppException e) { throw e; }
        catch (UnrecognizedPropertyException e) { throw new CppException(400,"CPP_UNKNOWN_FIELD","Unknown CPP field"); }
        catch (IOException | IllegalArgumentException e) { throw new CppException(400,"CPP_BAD_JSON","Malformed CPP JSON"); }
    }

    private static void shape(JsonNode n, Type t, int depth) {
        if (depth > 8 || n == null) { throw CppException.invalid("Invalid JSON structure"); }
        if (n.isNull()) {
            if (t == boolean.class || t == Boolean.class) { throw CppException.invalid("Boolean values cannot be null"); }
            return;
        }
        if (t instanceof ParameterizedType) {
            if (!n.isArray()) { throw CppException.invalid("An array is required"); }
            if (n.size() > CppResourceUris.limit("CPP.write.maxListItems",1000)) {
                throw new CppException(413,"CPP_LIMIT_EXCEEDED","Array exceeds the configured limit");
            }
            for (JsonNode item : n) {
                if (item.isNull()) { throw CppException.invalid("Array members cannot be null"); }
                shape(item, ((ParameterizedType)t).getActualTypeArguments()[0],depth+1);
            }
        } else if (t == String.class) {
            if (!n.isTextual()) { throw CppException.invalid("A string is required"); }
            if (n.textValue().length() > CppResourceUris.limit("CPP.write.maxStringLength",8192)) {
                throw new CppException(413,"CPP_LIMIT_EXCEEDED","String exceeds the configured limit");
            }
        } else if (t == boolean.class || t == Boolean.class) {
            if (!n.isBoolean()) { throw CppException.invalid("A JSON boolean is required"); }
        } else if (t == Integer.class || t == Long.class) {
            if (!n.isIntegralNumber() || (t == Integer.class && !n.canConvertToInt()) || !n.canConvertToLong()) {
                throw CppException.invalid("An integer in range is required");
            }
        } else if (t == Double.class) {
            if (!n.isNumber() || !Double.isFinite(n.doubleValue())) { throw CppException.invalid("A finite number is required"); }
        } else {
            if (!n.isObject()) { throw CppException.invalid("An object is required"); }
            Iterator<Map.Entry<String,JsonNode>> fields = n.fields();
            while (fields.hasNext()) {
                Map.Entry<String,JsonNode> item = fields.next();
                try { Field f = ((Class<?>) t).getField(item.getKey()); shape(item.getValue(), f.getGenericType(), depth+1); }
                catch (NoSuchFieldException e) { throw new CppException(400,"CPP_UNKNOWN_FIELD","Unknown field: " + item.getKey()); }
            }
        }
    }

    public static CppRunCreateRequest normalize(CppRunCreateRequest r, String author) {
        if (r == null || r.search == null || r.cppOptions == null || r.cppOptions.ordered == null
                || r.corpusSnapshot == null || r.index == null || r.software == null) {
            throw CppException.invalid("search, cppOptions.ordered, corpusSnapshot, index and software are required");
        }
        if (r.search.sortOrder != null && !r.search.sortOrder.trim().isEmpty()
                && !Arrays.asList("ASC","DESC").contains(r.search.sortOrder.trim().toUpperCase(Locale.ROOT))) {
            throw CppException.invalid("sortOrder must be ASC or DESC");
        }
        try {
            r.search = CollocateExtractionParameters.normalize(r.search);
            List<String> measures = new ArrayList<String>();
            for (CollocationStatisticsService.Metric m : CollocationStatisticsService.parse(r.search.metrics)) { measures.add(m.name()); }
            Collections.sort(measures); r.search.metrics = measures;
        } catch (IllegalArgumentException e) { throw CppException.invalid("Invalid extraction parameters"); }
        try (StandardTokenizer tokenizer = new StandardTokenizer()) {
            tokenizer.setReader(new StringReader(r.search.node)); tokenizer.reset();
            if (!tokenizer.incrementToken()) { throw CppException.invalid("node requires a canonical query token"); }
            tokenizer.end();
        } catch (IOException e) { throw CppException.invalid("Invalid query token"); }
        r.collocations = iris(r.collocations, "CPP.write.maxCollocations",100,true);
        r.corpusSnapshot.memberIRIs = iris(r.corpusSnapshot.memberIRIs,"CPP.write.maxCorpusResources",1000,true);
        r.search.contextIRIs = iris(r.search.contextIRIs,"CPP.write.maxCorpusResources",1000,false);
        r.search.corpusIRIs = iris(r.search.corpusIRIs,"CPP.write.maxCorpusResources",1000,false);
        r.search.graphIRIs = iris(r.search.graphIRIs,"CPP.write.maxCorpusResources",1000,false);
        if (r.search.contextIRI != null) { CppResourceUris.iri(r.search.contextIRI); }
        if (r.search.corpusIRI != null) { CppResourceUris.iri(r.search.corpusIRI); }
        if (r.search.graphIRI != null) { CppResourceUris.iri(r.search.graphIRI); }
        r.search.stoplist = strings(r.search.stoplist);
        r.search.excludeTerms = strings(r.search.excludeTerms);
        r.index.id = required(r.index.id,"index.id");
        // Logical identifiers must not expose a host filesystem location.
        if (r.index.id.startsWith("/") || r.index.id.contains("\\") || r.index.id.startsWith("file:")) {
            throw CppException.invalid("index.id must be a logical identifier");
        }
        r.software.name = required(r.software.name,"software.name");
        if (r.software.iri != null) { CppResourceUris.iri(r.software.iri); }
        if (r.execution != null) {
            if (r.execution.startedAtTime == null && r.execution.endedAtTime == null) { throw CppException.invalid("execution requires a time"); }
            r.execution.startedAtTime = time(r.execution.startedAtTime);
            r.execution.endedAtTime = time(r.execution.endedAtTime);
            if (r.execution.startedAtTime != null && r.execution.endedAtTime != null
                    && java.time.Instant.parse(r.execution.startedAtTime).isAfter(java.time.Instant.parse(r.execution.endedAtTime))) {
                throw new CppException(422,"CPP_INVALID_SEMANTICS","Execution end precedes start");
            }
        }
        r.author = LexiconCrudSupport.author(author);
        return r;
    }
    public static List<String> iris(List<String> values, String key, int max, boolean required) {
        if (values == null) { values = Collections.emptyList(); }
        if (values.size() > CppResourceUris.limit(key,max)) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","Too many resources"); }
        if (required && values.isEmpty()) { throw CppException.invalid("At least one resource is required"); }
        Set<String> unique = new TreeSet<String>();
        for (String v : values) { if (!unique.add(CppResourceUris.iri(v).stringValue())) { throw CppException.invalid("Duplicate resource IRI"); } }
        return new ArrayList<String>(unique);
    }
    private static List<String> strings(List<String> input) {
        Set<String> values = new TreeSet<String>();
        if (input != null) { for (String v : input) { if (v == null) { throw CppException.invalid("Null term"); } if (!v.trim().isEmpty()) { values.add(v); } } }
        return new ArrayList<String>(values);
    }
    public static String required(String text, String name) {
        if (text == null || text.trim().isEmpty()) { throw CppException.invalid(name + " is required"); }
        return text.trim();
    }
    public static String time(String text) {
        if (text == null) { return null; }
        try { return OffsetDateTime.parse(text).toInstant().toString(); }
        catch (RuntimeException e) { throw CppException.invalid("An ISO 8601 timestamp with offset is required"); }
    }
    public static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalStateException("Cannot serialize CPP data",e); }
    }
    public static String fingerprint(Object value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : bytes) { result.append(String.format("%02x",b & 255)); }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private CppJsonCodec() { }
}
