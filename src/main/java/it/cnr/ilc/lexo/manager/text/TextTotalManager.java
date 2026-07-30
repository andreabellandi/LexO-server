package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.service.data.text.input.TextTotalInput;
import it.cnr.ilc.lexo.service.data.text.output.TextTotalResult;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/** Validates and persists FRAC totals in LexOTexts document and corpus graphs. */
public final class TextTotalManager {

    private static final String LEXO = "https://lexo.ilc.cnr.it#";
    private static final Set<String> UNITS = new HashSet<String>(Arrays.asList(
            "tokens", "types", "lemmas", "sentences"));

    public static TextTotalManager get() {
        return Holder.INSTANCE;
    }

    private final TextNifRepository repository;

    TextTotalManager(TextNifRepository repository) {
        this.repository = repository;
    }

    public TextTotalResult replaceDocumentTotal(String fileId,
                                                TextTotalInput input) {
        String id = requireId("fileId", fileId);
        ValidatedTotal total = validate(input);
        String resource = repository.replaceDocumentTotal(id, total.value,
                total.unit);
        return resource == null ? null : result(id, resource, total);
    }

    public TextTotalResult replaceCorpusTotal(String corpusId,
                                              TextTotalInput input) {
        String id = requireId("corpusId", corpusId);
        ValidatedTotal total = validate(input);
        String resource = repository.replaceCorpusTotal(id, total.value,
                total.unit);
        return resource == null ? null : result(id, resource, total);
    }

    private ValidatedTotal validate(TextTotalInput input) {
        if (input == null) {
            throw new IllegalArgumentException("MISSING_TOTAL: request body is required");
        }
        if (input.value == null) {
            throw new IllegalArgumentException("MISSING_TOTAL_VALUE: value is required");
        }
        if (input.value.intValue() < 0) {
            throw new IllegalArgumentException(
                    "INVALID_TOTAL_VALUE: value must be non-negative");
        }
        String localName = normalizeUnit(input.unit);
        IRI unit = SimpleValueFactory.getInstance().createIRI(LEXO + localName);
        return new ValidatedTotal(input.value.intValue(), unit);
    }

    private String normalizeUnit(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("MISSING_TOTAL_UNIT: unit is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("lexo:")) {
            normalized = normalized.substring("lexo:".length());
        } else if (normalized.startsWith(LEXO.toLowerCase(Locale.ROOT))) {
            normalized = normalized.substring(LEXO.length());
        }
        if (!UNITS.contains(normalized)) {
            throw new IllegalArgumentException("INVALID_TOTAL_UNIT: unit must be one of "
                    + "lexo:tokens, lexo:types, lexo:lemmas, lexo:sentences");
        }
        return normalized;
    }

    private String requireId(String field, String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            String code = "fileId".equals(field)
                    ? "INVALID_FILE_ID" : "INVALID_CORPUS_ID";
            throw new IllegalArgumentException(code + ": " + field + " is invalid");
        }
        return value;
    }

    private TextTotalResult result(String id, String resource,
                                   ValidatedTotal total) {
        TextTotalResult result = new TextTotalResult();
        result.id = id;
        result.resource = resource;
        result.value = Integer.valueOf(total.value);
        result.unit = total.unit.stringValue();
        return result;
    }

    private static final class ValidatedTotal {
        final int value;
        final IRI unit;

        ValidatedTotal(int value, IRI unit) {
            this.value = value;
            this.unit = unit;
        }
    }

    private static final class Holder {
        private static final TextTotalManager INSTANCE =
                new TextTotalManager(TextNifRepository.get());
    }
}
