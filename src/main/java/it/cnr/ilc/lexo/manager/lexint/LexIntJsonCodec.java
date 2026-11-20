package it.cnr.ilc.lexo.manager.lexint;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Strict UTF-8 LexInt JSON without changing the global Jersey provider. */
public final class LexIntJsonCodec {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);

    public static <T> T read(InputStream input, Class<T> type) {
        if (input == null) {
            throw LexIntException.invalid("JSON body is required");
        }
        int maximum = LexIntResourceUris.limit("LexInt.write.maxBytes",
                1048576);
        try (InputStream in = input;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if ((long) bytes.size() + count > maximum) {
                    throw new LexIntException(413, "LEXINT_LIMIT_EXCEEDED",
                            "JSON body exceeds the configured limit");
                }
                bytes.write(buffer, 0, count);
            }
            String raw = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return read(raw, type);
        } catch (LexIntException ex) {
            throw ex;
        } catch (IOException ex) {
            throw LexIntException.invalid("Body must be valid UTF-8 JSON");
        }
    }

    public static <T> T read(String raw, Class<T> type) {
        if (raw == null) {
            throw LexIntException.invalid("JSON body is required");
        }
        try {
            try (JsonParser parser = JSON.getFactory().createParser(raw)) {
                int depth = 0;
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (token.isStructStart() && ++depth > 12) {
                        throw LexIntException.invalid(
                                "JSON nesting exceeds the LexInt limit");
                    }
                    if (token.isStructEnd()) {
                        depth--;
                    }
                }
            }
            JsonNode tree = JSON.readTree(raw);
            return JSON.treeToValue(tree, type);
        } catch (LexIntException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw LexIntException.invalid("Malformed or unsupported LexInt JSON");
        }
    }

    public static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (IOException ex) {
            throw new IllegalStateException("Cannot serialize LexInt JSON", ex);
        }
    }

    public static String fingerprint(Object value) {
        return LexIntResourceUris.sha256(write(value));
    }

    private LexIntJsonCodec() { }
}
