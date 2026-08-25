package it.cnr.ilc.lexo.manager.text;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.manager.text.model.JsonTextImport;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict parser for the fixed JSON schema supported by {@code POST /texts/bulk}. */
public final class TextJsonImportParser {

    private static final Set<String> ROOT_FIELDS = fields(
            "metadata", "text", "attestations");
    private static final Set<String> TEXT_FIELDS = fields("type", "content");
    private static final Set<String> TEXT_METADATA_FIELDS = fields(
            "id", "title", "author", "date", "description", "format", "corpus");
    private static final Set<String> ATTESTATION_FIELDS = fields(
            "id", "observable", "type", "value", "gloss", "start_char",
            "end_char", "metadata");
    private static final Set<String> RDF_PROPERTY_FIELDS = fields("property", "values");
    private static final Set<String> RDF_VALUE_FIELDS = fields(
            "value", "type", "language", "datatype");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public JsonTextImport parse(Path path) throws IOException {
        if (path == null) {
            throw invalid("BULK_JSON_MISSING_FILE", "JSON file is required");
        }
        return parse(readUtf8Strict(path));
    }

    public JsonTextImport parse(String rawJson) {
        if (rawJson == null) {
            throw invalid("BULK_JSON_MISSING_CONTENT", "JSON content is required");
        }
        final JsonNode root;
        try {
            root = JSON.readTree(rawJson);
        } catch (JsonProcessingException e) {
            throw invalid("BULK_INVALID_JSON", message(e));
        } catch (IOException e) {
            throw invalid("BULK_INVALID_JSON", message(e));
        }
        if (root == null || !root.isObject()) {
            throw invalid("BULK_INVALID_JSON_SCHEMA",
                    "the JSON root must be an object");
        }
        rejectUnknown(root, ROOT_FIELDS, "$", "BULK_UNKNOWN_JSON_FIELD");

        JsonTextImport result = new JsonTextImport();
        parseTextMetadata(root.get("metadata"), result);
        parseText(root.get("text"), result);
        parseAttestations(root.get("attestations"), result);
        return result;
    }

    private void parseTextMetadata(JsonNode node, JsonTextImport result) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isObject()) {
            throw invalid("BULK_INVALID_JSON_METADATA",
                    "$.metadata must be an object");
        }
        result.metadataPresent = true;
        rejectUnknown(node, TEXT_METADATA_FIELDS, "$.metadata",
                "BULK_UNKNOWN_TEXT_METADATA");
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey().toLowerCase(Locale.ROOT);
            List<String> values = textMetadataValues(field.getValue(),
                    "$.metadata." + field.getKey());
            if ("corpus".equals(key)) {
                if (values.size() != 1 || blank(values.get(0))) {
                    throw invalid("BULK_INVALID_JSON_CORPUS",
                            "$.metadata.corpus must contain one non-blank corpus id");
                }
                result.corpusId = values.get(0).trim();
            } else {
                result.metadata.put(key, values);
            }
        }
    }

    private void parseText(JsonNode node, JsonTextImport result) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw invalid("BULK_MISSING_JSON_TEXT", "$.text must be an object");
        }
        rejectUnknown(node, TEXT_FIELDS, "$.text", "BULK_UNKNOWN_JSON_TEXT_FIELD");
        String type = requiredText(node, "type", "$.text.type");
        if (!"txt".equals(type)) {
            throw invalid("BULK_UNSUPPORTED_JSON_TEXT_TYPE",
                    "$.text.type must be exactly txt");
        }
        result.content = requiredText(node, "content", "$.text.content");
    }

    private void parseAttestations(JsonNode node, JsonTextImport result) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            throw invalid("BULK_INVALID_JSON_ATTESTATIONS",
                    "$.attestations must be an array");
        }
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String path = "$.attestations[" + index + "]";
            if (item == null || !item.isObject()) {
                throw invalid("BULK_INVALID_JSON_ATTESTATION",
                        path + " must be an object");
            }
            rejectUnknown(item, ATTESTATION_FIELDS, path,
                    "BULK_UNKNOWN_ATTESTATION_FIELD");
            JsonTextImport.AttestationInput input =
                    new JsonTextImport.AttestationInput();
            input.id = optionalText(item, "id", path + ".id");
            input.observable = requiredText(item, "observable", path + ".observable");
            input.type = requiredText(item, "type", path + ".type");
            input.value = requiredText(item, "value", path + ".value");
            input.gloss = requiredText(item, "gloss", path + ".gloss");
            input.start = requiredInteger(item, "start_char", path + ".start_char");
            input.end = requiredInteger(item, "end_char", path + ".end_char");
            input.metadata = parseRdfMetadata(item.get("metadata"), path + ".metadata");
            result.attestations.add(input);
        }
    }

    private List<RdfMetadataProperty> parseRdfMetadata(JsonNode node, String path) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw invalid("BULK_INVALID_ATTESTATION_METADATA",
                    path + " must be an array");
        }
        List<RdfMetadataProperty> properties = new ArrayList<RdfMetadataProperty>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode item = node.get(index);
            String propertyPath = path + "[" + index + "]";
            if (item == null || !item.isObject()) {
                throw invalid("BULK_INVALID_ATTESTATION_METADATA",
                        propertyPath + " must be an object");
            }
            rejectUnknown(item, RDF_PROPERTY_FIELDS, propertyPath,
                    "BULK_UNKNOWN_ATTESTATION_METADATA_FIELD");
            RdfMetadataProperty property = new RdfMetadataProperty();
            property.property = optionalText(item, "property", propertyPath + ".property");
            JsonNode values = item.get("values");
            if (values == null || values.isNull()) {
                property.values = null;
            } else if (!values.isArray()) {
                throw invalid("BULK_INVALID_ATTESTATION_METADATA",
                        propertyPath + ".values must be an array");
            } else {
                property.values = new ArrayList<RdfMetadataValue>();
                for (int valueIndex = 0; valueIndex < values.size(); valueIndex++) {
                    property.values.add(parseRdfValue(values.get(valueIndex),
                            propertyPath + ".values[" + valueIndex + "]"));
                }
            }
            properties.add(property);
        }
        return properties;
    }

    private RdfMetadataValue parseRdfValue(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw invalid("BULK_INVALID_ATTESTATION_METADATA",
                    path + " must be an object");
        }
        rejectUnknown(node, RDF_VALUE_FIELDS, path,
                "BULK_UNKNOWN_ATTESTATION_METADATA_FIELD");
        RdfMetadataValue result = new RdfMetadataValue();
        result.value = optionalText(node, "value", path + ".value");
        result.type = optionalText(node, "type", path + ".type");
        result.language = optionalText(node, "language", path + ".language");
        result.datatype = optionalText(node, "datatype", path + ".datatype");
        return result;
    }

    private static List<String> textMetadataValues(JsonNode node, String path) {
        List<String> result = new ArrayList<String>();
        if (node != null && node.isTextual()) {
            result.add(node.textValue());
            return result;
        }
        if (node != null && node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                JsonNode value = node.get(index);
                if (value == null || !value.isTextual()) {
                    throw invalid("BULK_INVALID_JSON_METADATA_VALUE",
                            path + "[" + index + "] must be a string");
                }
                result.add(value.textValue());
            }
            return result;
        }
        throw invalid("BULK_INVALID_JSON_METADATA_VALUE",
                path + " must be a string or an array of strings");
    }

    private static String requiredText(JsonNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw invalid("BULK_MISSING_JSON_FIELD", path + " is required");
        }
        if (!value.isTextual()) {
            throw invalid("BULK_INVALID_JSON_FIELD_TYPE", path + " must be a string");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("BULK_INVALID_JSON_FIELD_TYPE", path + " must be a string");
        }
        return value.textValue();
    }

    private static Integer requiredInteger(JsonNode object, String field, String path) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw invalid("BULK_MISSING_JSON_FIELD", path + " is required");
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("BULK_INVALID_JSON_FIELD_TYPE",
                    path + " must be a 32-bit integer");
        }
        return Integer.valueOf(value.intValue());
    }

    private static void rejectUnknown(JsonNode object, Set<String> allowed,
                                      String path, String code) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid(code, path + "." + name + " is not allowed");
            }
        }
    }

    private static String readUtf8Strict(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw invalid("BULK_JSON_INVALID_UTF8",
                    "JSON file is not valid UTF-8: " + path.getFileName());
        }
    }

    private static Set<String> fields(String... names) {
        return new HashSet<String>(Arrays.asList(names));
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
