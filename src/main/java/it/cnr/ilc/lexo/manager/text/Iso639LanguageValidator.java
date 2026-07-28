package it.cnr.ilc.lexo.manager.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Validates text languages against the bundled ISO 639 code list. */
public final class Iso639LanguageValidator {

    static final String RESOURCE = "/iso639/lista_ufficiale_isocode_ISO_639.csv";
    private static final Iso639LanguageValidator INSTANCE = loadBundledList();

    private final Map<String, String> canonicalCodes;

    private Iso639LanguageValidator(Map<String, String> canonicalCodes) {
        this.canonicalCodes = canonicalCodes;
    }

    public static Iso639LanguageValidator get() {
        return INSTANCE;
    }

    /**
     * Returns the canonical lowercase spelling found in one of the first four
     * CSV columns, or rejects a missing/unknown value with a stable error code.
     */
    public String requireValid(String language) {
        if (language == null || language.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "MISSING_LANGUAGE: È richiesto un codice lingua per ogni testo caricato");
        }
        String key = language.trim().toLowerCase(Locale.ROOT);
        String canonical = canonicalCodes.get(key);
        if (canonical == null) {
            throw new IllegalArgumentException("INVALID_LANGUAGE: Il codice lingua '"
                    + language.trim()
                    + "' non è presente negli standard ISO 639-1, ISO 639-2 o ISO 639-3");
        }
        return canonical;
    }

    private static Iso639LanguageValidator loadBundledList() {
        InputStream input = Iso639LanguageValidator.class.getResourceAsStream(RESOURCE);
        if (input == null) {
            throw new IllegalStateException("Missing ISO 639 language resource " + RESOURCE);
        }
        Map<String, String> codes = new LinkedHashMap<String, String>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                // Only the first four columns contain codes. Splitting at most
                // five fields keeps commas in language names irrelevant.
                String[] columns = line.split(",", 5);
                int codeColumns = Math.min(4, columns.length);
                for (int i = 0; i < codeColumns; i++) {
                    String code = columns[i].trim();
                    if (!code.isEmpty()) {
                        String key = code.toLowerCase(Locale.ROOT);
                        codes.put(key, key);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read ISO 639 language resource " + RESOURCE, e);
        }
        if (codes.isEmpty()) {
            throw new IllegalStateException("ISO 639 language resource contains no codes");
        }
        return new Iso639LanguageValidator(Collections.unmodifiableMap(codes));
    }
}
