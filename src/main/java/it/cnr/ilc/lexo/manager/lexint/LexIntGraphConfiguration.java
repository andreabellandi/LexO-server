package it.cnr.ilc.lexo.manager.lexint;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates that every LexInt graph is isolated from application graph spaces. */
public final class LexIntGraphConfiguration {
    public static void validate(String shapesGraph) {
        String schema = property("GraphDb.schemaNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/schema");
        String records = property("GraphDb.lexintRecordsNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/records");
        String audit = property("GraphDb.lexintAuditNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/audit");
        String snapshots = property("GraphDb.lexintSnapshotsBase",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/snapshots/");

        List<String> lexintExact = values(shapesGraph, schema, records, audit);
        List<String> allExact = new ArrayList<String>(lexintExact);
        allExact.addAll(values(
                property("Bootstrap.metadata.namedGraph",
                        "https://lexo.ilc.cnr.it/graphs/bootstrap"),
                property("GraphDb.lexiconNamedGraph",
                        "https://lexo.ilc.cnr.it/graphs/lexical/lexica"),
                LexiconCrudSupport.LEXICAL_CONCEPT_GRAPH_URI,
                optional("GraphDb.cppNamedGraph"),
                property("TextGraphDb.organizationNamedGraph",
                        "https://lexo.ilc.cnr.it/graphs/text-organization"),
                property("TextGraphDb.organizationSchemaNamedGraph",
                        "https://lexo.ilc.cnr.it/graphs/text-organization/schema"),
                property("WordnetGraphDb.schemaNamedGraph",
                        "https://lexo.ilc.cnr.it/graphs/wordnet/schema"),
                property("Bootstrap.wordnet.metadata.namedGraph",
                        "https://lexo.ilc.cnr.it/graphs/wordnet/bootstrap")));
        Set<String> distinct = new HashSet<String>();
        for (String value : allExact) {
            requireGraphIri(value);
            if (!distinct.add(value)) {
                throw new IllegalStateException(
                        "Configured LexInt graph collides with another named graph");
            }
        }

        requireFamilyBase(snapshots, "GraphDb.lexintSnapshotsBase");
        List<String> externalFamilies = values(
                LexiconCrudSupport.LEXICAL_GRAPH_BASE_URI,
                property("GraphDb.attestationNamedGraphBase",
                        "https://lexo.ilc.cnr.it/graphs/lexical/attestations/documents/"),
                property("GraphDb.annotationNamedGraphBase",
                        "https://lexo.ilc.cnr.it/graphs/lexical/annotations/documents/"),
                property("TextGraphDb.namedGraphBase",
                        "https://lexo.ilc.cnr.it/graphs/nif/"),
                property("WordnetGraphDb.namedGraphBase",
                        "https://lexo.ilc.cnr.it/graphs/wordnet/"));
        for (String family : externalFamilies) {
            requireFamilyBase(family, "application named graph base");
            if (overlapsFamily(snapshots, family)) {
                throw new IllegalStateException(
                        "LexInt snapshot graph family collides with an application graph family");
            }
            for (String graph : lexintExact) {
                if (graph.startsWith(family)) {
                    throw new IllegalStateException(
                            "LexInt graph is inside an application graph family");
                }
            }
        }
        for (String graph : allExact) {
            if (graph.startsWith(snapshots)) {
                throw new IllegalStateException(
                        "Named graph is inside the LexInt snapshot family");
            }
        }
    }

    private static boolean overlapsFamily(String left, String right) {
        return left.startsWith(right) || right.startsWith(left);
    }

    private static List<String> values(String... candidates) {
        List<String> result = new ArrayList<String>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                result.add(candidate.trim());
            }
        }
        return result;
    }

    private static String optional(String key) {
        String value = LexOProperties.getProperty(key);
        return value == null || value.trim().isEmpty() || value.contains("${")
                ? null : value.trim();
    }

    private static String property(String key, String fallback) {
        String value = optional(key);
        return value == null ? fallback : value;
    }

    private static void requireFamilyBase(String value, String label) {
        requireGraphIri(value);
        if (!value.endsWith("/") || value.contains("../")
                || value.contains("./")) {
            throw new IllegalStateException(label
                    + " must be a safe absolute IRI base ending in '/'");
        }
    }

    private static void requireGraphIri(String value) {
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalStateException("Invalid named graph IRI: "
                        + value);
            }
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Invalid named graph IRI: "
                    + value, ex);
        }
    }

    private LexIntGraphConfiguration() { }
}
