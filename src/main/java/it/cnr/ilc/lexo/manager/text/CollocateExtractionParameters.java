package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.service.data.text.input.CollocateExtractionRequest;
import java.util.Locale;

/** Effective extraction defaults shared by Lucene and retrospective CPP records. */
public final class CollocateExtractionParameters {
    private CollocateExtractionParameters() { }

    public static CollocateExtractionRequest normalize(CollocateExtractionRequest r) {
        if (r == null) { throw new IllegalArgumentException("MISSING_COLLOCATION_REQUEST: body required"); }
        if (r.node == null || r.node.trim().isEmpty()) {
            throw new IllegalArgumentException("MISSING_NODE: node required");
        }
        r.node = r.node.trim();
        r.leftWindow = bounded(r.leftWindow, 5, 0, positive("lexo.lucene.maxCollocationWindow", 100), "leftWindow");
        r.rightWindow = bounded(r.rightWindow, 5, 0, positive("lexo.lucene.maxCollocationWindow", 100), "rightWindow");
        r.limit = bounded(r.limit, 100, 1, positive("lexo.lucene.maxPageSize", 1000), "limit");
        r.minCooccurrenceFrequency = bounded(r.minCooccurrenceFrequency, 2, 1, Integer.MAX_VALUE, "minCooccurrenceFrequency");
        if (r.minFrequency != null && r.minFrequency < 0) {
            throw new IllegalArgumentException("INVALID_MIN_FREQUENCY: minFrequency must be non-negative");
        }
        if (r.minScore != null && !Double.isFinite(r.minScore)) {
            throw new IllegalArgumentException("INVALID_MIN_SCORE: minScore must be finite");
        }
        r.direction = option(r.direction, "BOTH", "LEFT", "RIGHT", "BOTH");
        r.boundary = option(r.boundary, "DOCUMENT", "DOCUMENT", "SENTENCE");
        r.sortBy = upper(r.sortBy, "LOG_DICE");
        if (!"FREQUENCY".equals(r.sortBy)) { try { CollocationStatisticsService.Metric.valueOf(r.sortBy); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("INVALID_SORT_METRIC: " + r.sortBy); } }
        // The historical extractor treats every order other than ASC as DESC.
        r.sortOrder = "ASC".equals(upper(r.sortOrder, "DESC")) ? "ASC" : "DESC";
        return r;
    }

    private static Integer bounded(Integer v, int fallback, int min, int max, String field) {
        int n = v == null ? fallback : v;
        if (n < min || n > max) { throw new IllegalArgumentException("INVALID_" + field.toUpperCase(Locale.ROOT) + ": " + field + " is out of range"); }
        return n;
    }
    private static String option(String v, String fallback, String... allowed) {
        String result = upper(v, fallback);
        for (String a : allowed) { if (a.equals(result)) { return result; } }
        throw new IllegalArgumentException(("BOTH".equals(fallback) ? "INVALID_DIRECTION: " : "INVALID_BOUNDARY: ") + v);
    }
    private static String upper(String v, String fallback) {
        return v == null || v.trim().isEmpty() ? fallback : v.trim().toUpperCase(Locale.ROOT);
    }
    private static int positive(String key, int fallback) {
        try { int n = Integer.parseInt(LexOProperties.getProperty(key, String.valueOf(fallback))); return n > 0 ? n : fallback; }
        catch (NumberFormatException e) { return fallback; }
    }
}
