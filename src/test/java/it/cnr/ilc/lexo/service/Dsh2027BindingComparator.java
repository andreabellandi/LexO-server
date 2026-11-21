package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;

/** Exact, duplicate-preserving comparator for DSH competency-query bindings. */
final class Dsh2027BindingComparator {
    private Dsh2027BindingComparator() { }

    static Expected expected(JsonNode question,
            Map<String, String> placeholders) {
        if (question == null || !question.isObject()) {
            throw new IllegalArgumentException("Missing competency-question oracle");
        }
        List<String> variables = new ArrayList<String>();
        for (JsonNode variable : question.path("variables")) {
            variables.add(variable.asText());
        }
        if (variables.isEmpty() || variables.size()
                != new LinkedHashSet<String>(variables).size()) {
            throw new IllegalArgumentException("Oracle variables must be unique");
        }
        List<Row> rows = new ArrayList<Row>();
        for (JsonNode row : question.path("rows")) {
            Map<String, Term> bindings = new LinkedHashMap<String, Term>();
            for (String variable : variables) {
                JsonNode term = row.get(variable);
                if (term == null) {
                    throw new IllegalArgumentException(
                            "Oracle row omits variable " + variable);
                }
                bindings.put(variable, Term.fromJson(term, placeholders));
            }
            if (row.size() != variables.size()) {
                throw new IllegalArgumentException(
                        "Oracle row contains unexpected variables");
            }
            rows.add(new Row(bindings));
        }
        return new Expected(variables, rows,
                question.path("semantics").asText("MULTISET"));
    }

    static Comparison compare(Expected expected, List<Row> observed) {
        if (!"MULTISET".equals(expected.semantics)) {
            throw new IllegalArgumentException("Only MULTISET semantics are supported");
        }
        Map<Row, Integer> remaining = counts(observed);
        List<Row> missing = new ArrayList<Row>();
        int matched = 0;
        for (Row row : expected.rows) {
            Integer count = remaining.get(row);
            if (count == null || count.intValue() == 0) {
                missing.add(row);
            } else {
                matched++;
                if (count.intValue() == 1) remaining.remove(row);
                else remaining.put(row, Integer.valueOf(count.intValue() - 1));
            }
        }
        List<Row> unexpected = new ArrayList<Row>();
        for (Map.Entry<Row, Integer> entry : remaining.entrySet()) {
            for (int i = 0; i < entry.getValue().intValue(); i++) {
                unexpected.add(entry.getKey());
            }
        }
        Collections.sort(missing);
        Collections.sort(unexpected);
        return new Comparison(expected.rows.size(), observed.size(), matched,
                missing, unexpected);
    }

    static Row observed(Map<String, Value> bindings) {
        Map<String, Term> terms = new TreeMap<String, Term>();
        for (Map.Entry<String, Value> binding : bindings.entrySet()) {
            terms.put(binding.getKey(), Term.fromValue(binding.getValue()));
        }
        return new Row(terms);
    }

    static Row row(Object... values) {
        if ((values.length % 2) != 0) {
            throw new IllegalArgumentException("Rows require name/term pairs");
        }
        Map<String, Term> bindings = new LinkedHashMap<String, Term>();
        for (int i = 0; i < values.length; i += 2) {
            bindings.put(String.valueOf(values[i]), (Term) values[i + 1]);
        }
        return new Row(bindings);
    }

    private static Map<Row, Integer> counts(List<Row> rows) {
        Map<Row, Integer> result = new LinkedHashMap<Row, Integer>();
        for (Row row : rows) {
            Integer count = result.get(row);
            result.put(row, Integer.valueOf(count == null ? 1
                    : count.intValue() + 1));
        }
        return result;
    }

    static final class Expected {
        final List<String> variables;
        final List<Row> rows;
        final String semantics;

        Expected(List<String> variables, List<Row> rows, String semantics) {
            this.variables = Collections.unmodifiableList(
                    new ArrayList<String>(variables));
            this.rows = Collections.unmodifiableList(new ArrayList<Row>(rows));
            this.semantics = semantics;
        }
    }

    static final class Comparison {
        final int expectedRows;
        final int observedRows;
        final int matchedRows;
        final List<Row> missingRows;
        final List<Row> unexpectedRows;
        final String status;

        Comparison(int expectedRows, int observedRows, int matchedRows,
                List<Row> missingRows, List<Row> unexpectedRows) {
            this.expectedRows = expectedRows;
            this.observedRows = observedRows;
            this.matchedRows = matchedRows;
            this.missingRows = Collections.unmodifiableList(missingRows);
            this.unexpectedRows = Collections.unmodifiableList(unexpectedRows);
            this.status = missingRows.isEmpty() && unexpectedRows.isEmpty()
                    ? "EXACT" : "INCORRECT";
        }

        double completeness() {
            return expectedRows == 0 ? 1.0d
                    : ((double) matchedRows) / ((double) expectedRows);
        }

        double precision() {
            return observedRows == 0 ? (expectedRows == 0 ? 1.0d : 0.0d)
                    : ((double) matchedRows) / ((double) observedRows);
        }

        ObjectNode toJson(ObjectMapper mapper, Expected expected,
                List<Row> observed) {
            ObjectNode value = mapper.createObjectNode();
            value.put("semantics", expected.semantics);
            value.put("expected_rows", expectedRows);
            value.put("observed_rows", observedRows);
            value.put("matched_rows", matchedRows);
            value.put("missing_rows", missingRows.size());
            value.put("unexpected_rows", unexpectedRows.size());
            value.put("completeness", completeness());
            value.put("precision", precision());
            value.put("status", status);
            value.set("variables", mapper.valueToTree(expected.variables));
            value.set("expected_bindings", rowsJson(mapper, expected.rows));
            value.set("observed_bindings", rowsJson(mapper, observed));
            value.set("missing_bindings", rowsJson(mapper, missingRows));
            value.set("unexpected_bindings", rowsJson(mapper, unexpectedRows));
            return value;
        }

        private static ArrayNode rowsJson(ObjectMapper mapper, List<Row> rows) {
            ArrayNode values = mapper.createArrayNode();
            List<Row> ordered = new ArrayList<Row>(rows);
            Collections.sort(ordered);
            for (Row row : ordered) values.add(row.toJson(mapper));
            return values;
        }
    }

    static final class Row implements Comparable<Row> {
        final Map<String, Term> bindings;

        Row(Map<String, Term> bindings) {
            this.bindings = Collections.unmodifiableMap(
                    new TreeMap<String, Term>(bindings));
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode row = mapper.createObjectNode();
            for (Map.Entry<String, Term> entry : bindings.entrySet()) {
                row.set(entry.getKey(), entry.getValue().toJson(mapper));
            }
            return row;
        }

        @Override public int compareTo(Row other) {
            return canonical().compareTo(other.canonical());
        }

        @Override public boolean equals(Object other) {
            return other instanceof Row
                    && bindings.equals(((Row) other).bindings);
        }

        @Override public int hashCode() { return bindings.hashCode(); }

        private String canonical() {
            StringBuilder value = new StringBuilder();
            for (Map.Entry<String, Term> entry : bindings.entrySet()) {
                value.append(entry.getKey()).append('=')
                        .append(entry.getValue().canonical()).append('\n');
            }
            return value.toString();
        }
    }

    static final class Term {
        final String type;
        final String value;
        final String datatype;
        final String language;

        Term(String type, String value, String datatype, String language) {
            this.type = type;
            this.value = value;
            this.datatype = datatype;
            this.language = language;
        }

        static Term iri(String value) { return new Term("IRI", value, null, null); }

        static Term literal(String value) {
            return new Term("LITERAL", value,
                    "http://www.w3.org/2001/XMLSchema#string", null);
        }

        static Term fromValue(Value value) {
            if (value instanceof IRI) return iri(value.stringValue());
            if (value instanceof BNode) {
                return new Term("BNODE", value.stringValue(), null, null);
            }
            if (value instanceof Literal) {
                Literal literal = (Literal) value;
                return new Term("LITERAL", literal.getLabel(),
                        literal.getDatatype() == null ? null
                                : literal.getDatatype().stringValue(),
                        literal.getLanguage().isPresent()
                                ? literal.getLanguage().get() : null);
            }
            throw new IllegalArgumentException("Unsupported RDF value: " + value);
        }

        static Term fromJson(JsonNode node, Map<String, String> placeholders) {
            String type = node.path("type").asText();
            String value = node.path("value").asText();
            if (value.matches("\\$\\{[A-Za-z0-9_]+\\}")) {
                String key = value.substring(2, value.length() - 1);
                value = placeholders.get(key);
                if (value == null || value.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Unresolved oracle placeholder: " + key);
                }
            }
            String datatype = nullable(node.get("datatype"));
            String language = nullable(node.get("language"));
            if (!"IRI".equals(type) && !"LITERAL".equals(type)
                    && !"BNODE".equals(type)) {
                throw new IllegalArgumentException("Invalid RDF term type: " + type);
            }
            if (!"LITERAL".equals(type)
                    && (datatype != null || language != null)) {
                throw new IllegalArgumentException(
                        "Only literal terms may have datatype/language");
            }
            return new Term(type, value, datatype, language);
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", type); node.put("value", value);
            if (datatype != null) node.put("datatype", datatype);
            if (language != null) node.put("language", language);
            return node;
        }

        private String canonical() {
            return type + "|" + value + "|"
                    + (datatype == null ? "" : datatype) + "|"
                    + (language == null ? "" : language.toLowerCase(Locale.ROOT));
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Term)) return false;
            Term term = (Term) other;
            return equal(type, term.type) && equal(value, term.value)
                    && equal(datatype, term.datatype)
                    && equal(language, term.language);
        }

        @Override public int hashCode() {
            int result = type.hashCode();
            result = 31 * result + value.hashCode();
            result = 31 * result + (datatype == null ? 0 : datatype.hashCode());
            result = 31 * result + (language == null ? 0 : language.hashCode());
            return result;
        }

        private static boolean equal(Object left, Object right) {
            return left == null ? right == null : left.equals(right);
        }

        private static String nullable(JsonNode value) {
            return value == null || value.isNull() || value.asText().isEmpty()
                    ? null : value.asText();
        }
    }
}
