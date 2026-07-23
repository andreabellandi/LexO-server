package it.cnr.ilc.lexo.util;

import it.cnr.ilc.lexo.LexOProperties;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.impl.SimpleDataset;

/**
 * Named-graph configuration for data stored in the lexical repository.
 *
 * <p>The schema and bootstrap metadata use their own graphs. Application data
 * is split between the lexical graph and one attestation/annotation graph per
 * text, following the same document-id convention used by the text repository.</p>
 */
public final class LexicalNamedGraphs {

    private static final String DEFAULT_BASE =
            "https://lexo.ilc.cnr.it/graphs/lexical/";

    public enum Kind {
        LEXICON,
        ATTESTATION,
        ANNOTATION
    }

    private LexicalNamedGraphs() {
    }

    public static String lexiconGraphUri() {
        return configured("GraphDb.lexiconNamedGraph",
                baseUri() + "lexica");
    }

    public static String attestationGraphBaseUri() {
        return trailingSeparator(configured("GraphDb.attestationNamedGraphBase",
                baseUri() + "attestations/documents/"));
    }

    public static String annotationGraphBaseUri() {
        return trailingSeparator(configured("GraphDb.annotationNamedGraphBase",
                baseUri() + "annotations/documents/"));
    }

    public static String attestationGraphUri(String fileId) {
        return attestationGraphBaseUri() + requireFileId(fileId);
    }

    public static String annotationGraphUri(String fileId) {
        return annotationGraphBaseUri() + requireFileId(fileId);
    }

    public static String schemaGraphUri() {
        return configured("GraphDb.schemaNamedGraph",
                baseUri() + "schema");
    }

    public static String graphUri(Kind kind, String fileId) {
        switch (kind) {
            case ATTESTATION:
                return attestationGraphUri(fileId);
            case ANNOTATION:
                return annotationGraphUri(fileId);
            case LEXICON:
            default:
                return lexiconGraphUri();
        }
    }

    /**
     * Directs INSERT and DELETE operations without an explicit GRAPH clause to
     * the selected application graph. WHERE clauses use that graph as their
     * default graph, preventing accidental updates to schema/bootstrap data.
     */
    public static void configure(Update update, Kind kind) {
        if (kind != Kind.LEXICON) {
            throw new IllegalArgumentException("fileId is required for "
                    + kind.name().toLowerCase() + " updates");
        }
        configure(update, kind, null);
    }

    /** Directs an update to the graph family member belonging to one text. */
    public static void configure(Update update, Kind kind, String fileId) {
        configure(update, graphUri(kind, fileId));
    }

    public static void configure(Update update, String graphUri) {
        if (update == null) {
            throw new IllegalArgumentException("SPARQL update is required");
        }
        IRI graph = SimpleValueFactory.getInstance().createIRI(graphUri);
        SimpleDataset dataset = new SimpleDataset();
        dataset.addDefaultGraph(graph);
        dataset.addDefaultRemoveGraph(graph);
        dataset.setDefaultInsertGraph(graph);
        update.setDataset(dataset);
    }

    private static String baseUri() {
        return trailingSeparator(configured("GraphDb.namedGraphBase", DEFAULT_BASE));
    }

    private static String configured(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        if (value == null || value.trim().isEmpty() || value.contains("${")) {
            return fallback;
        }
        return value.trim();
    }

    private static String trailingSeparator(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "/";
    }

    private static String requireFileId(String fileId) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid fileId");
        }
        return fileId;
    }
}
