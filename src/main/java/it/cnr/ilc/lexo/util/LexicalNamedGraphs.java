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
 * is split between the lexical graph and the attestation graph, following the
 * same base-URI convention used by {@code TextNifRepository}.</p>
 */
public final class LexicalNamedGraphs {

    private static final String DEFAULT_BASE =
            "https://lexo.ilc.cnr.it/graphs/lexical/";

    public enum Kind {
        LEXICON,
        ATTESTATION
    }

    private LexicalNamedGraphs() {
    }

    public static String lexiconGraphUri() {
        return configured("GraphDb.lexiconNamedGraph",
                baseUri() + "lexica");
    }

    public static String attestationGraphUri() {
        return configured("GraphDb.attestationNamedGraph",
                baseUri() + "attestations");
    }

    public static String graphUri(Kind kind) {
        return kind == Kind.ATTESTATION
                ? attestationGraphUri()
                : lexiconGraphUri();
    }

    /**
     * Directs INSERT and DELETE operations without an explicit GRAPH clause to
     * the selected application graph. WHERE clauses use that graph as their
     * default graph, preventing accidental updates to schema/bootstrap data.
     */
    public static void configure(Update update, Kind kind) {
        IRI graph = SimpleValueFactory.getInstance().createIRI(graphUri(kind));
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
}
