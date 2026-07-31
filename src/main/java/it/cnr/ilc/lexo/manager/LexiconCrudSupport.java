package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/**
 * Shared invariants for the new lexical CRUD services.
 */
public final class LexiconCrudSupport {

    public static final String LEXICAL_GRAPH_BASE_URI =
            "https://lexo.ilc.cnr.it/graphs/lexical/lexica/";

    private static final Map<String, String> LEXICAL_PREFIXES = lexicalPrefixes();

    private LexiconCrudSupport() {
    }

    /**
     * Returns the lexical named graph dedicated to one language.
     *
     * @param language code found in the bundled ISO 639 language list
     * @return the absolute IRI of the language-specific lexical named graph
     */
    public static String lexicalGraphUri(String language) {
        String canonicalLanguage = Iso639LanguageValidator.get()
                .requireValid(language);
        return LEXICAL_GRAPH_BASE_URI + canonicalLanguage;
    }

    /**
     * Creates a resource IRI using the current millisecond timestamp.
     *
     * @return the generated absolute resource IRI
     */
    public static String newResourceUri() {
        Timestamp tm = new Timestamp(System.currentTimeMillis());
        return newResourceUri(tm);
    }

    /**
     * Creates a resource IRI from the configured namespace, instance id, and
     * millisecond timestamp.
     *
     * @param timestamp timestamp used as the resource-local identifier
     * @return the generated absolute resource IRI
     */
    public static String newResourceUri(Timestamp timestamp) {
        return newResourceUri(
                timestamp,
                LexOProperties.getProperty("repository.lexicon.namespace"),
                LexOProperties.getProperty("repository.instance.id"),
                LexOProperties.getProperty("manager.operationTimestampFormat"));
    }

    static String newResourceUri(Timestamp timestamp, String namespace,
                                 String instanceId, String timestampPattern) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Timestamp is required");
        }
        if (namespace == null || instanceId == null || timestampPattern == null) {
            throw new IllegalStateException("Lexical resource IRI configuration is incomplete");
        }
        String localTimestamp = formatTimestamp(timestamp, timestampPattern)
                .replaceAll("\\s+", "")
                .replaceAll(":", "_")
                .replaceAll("\\.", "_");
        return namespace + instanceId + localTimestamp;
    }

    /** Returns the current operation timestamp in the configured wire format. */
    public static String operationTimestamp() {
        return formatTimestamp(new Timestamp(System.currentTimeMillis()),
                LexOProperties.getProperty("manager.operationTimestampFormat"));
    }

    static String formatTimestamp(Timestamp timestamp, String timestampPattern) {
        if (timestamp == null) {
            throw new IllegalArgumentException("Timestamp is required");
        }
        if (timestampPattern == null) {
            throw new IllegalStateException(
                    "Lexical operation timestamp configuration is incomplete");
        }
        return new SimpleDateFormat(timestampPattern).format(timestamp);
    }

    /**
     * Normalizes the optional author accepted by every new lexical service.
     *
     * @param author resolved authenticated user or explicit request author
     * @return the author, or {@code anonymous} when it is blank
     */
    public static String author(String author) {
        return author == null || author.trim().isEmpty() ? "anonymous" : author;
    }

    /** Expands the exact compact prefixes supported by new lexical CRUD APIs. */
    public static String expandLexicalIri(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(':');
        if (separator > 0) {
            String namespace = LEXICAL_PREFIXES.get(value.substring(0, separator));
            if (namespace != null) {
                return namespace + value.substring(separator + 1);
            }
        }
        return value;
    }

    /** Exact prefix associations shared by every new lexical CRUD service. */
    public static Map<String, String> lexicalPrefixes() {
        Map<String, String> prefixes = new LinkedHashMap<String, String>();
        prefixes.put("decomp", "http://www.w3.org/ns/lemon/decomp#");
        prefixes.put("vartrans", "http://www.w3.org/ns/lemon/vartrans#");
        prefixes.put("ontolex", "http://www.w3.org/ns/lemon/ontolex#");
        prefixes.put("synsem", "http://www.w3.org/ns/lemon/synsem#");
        prefixes.put("lexinfo", "http://www.lexinfo.net/ontology/3.0/lexinfo#");
        prefixes.put("lime", "http://www.w3.org/ns/lemon/lime#");
        prefixes.put("lexicog", "http://www.w3.org/ns/lemon/lexicog#");
        return Collections.unmodifiableMap(prefixes);
    }

    /** Tests a transitive RDF subclass relation in the supplied named graphs. */
    public static boolean isSubclassOf(RepositoryConnection connection,
                                       IRI candidate, IRI expected,
                                       Resource... graphs) {
        return isSubclassOf(connection, candidate, expected,
                new HashSet<String>(), graphs);
    }

    private static boolean isSubclassOf(RepositoryConnection connection,
                                        IRI candidate, IRI expected,
                                        Set<String> visited,
                                        Resource... graphs) {
        if (candidate.equals(expected)) {
            return true;
        }
        if (!visited.add(candidate.stringValue())) {
            return false;
        }
        try (RepositoryResult<Statement> parents = connection.getStatements(
                candidate, RDFS.SUBCLASSOF, null, false, graphs)) {
            while (parents.hasNext()) {
                Value parent = parents.next().getObject();
                if (parent instanceof IRI && isSubclassOf(connection, (IRI) parent,
                        expected, visited, graphs)) {
                    return true;
                }
            }
        }
        return false;
    }
}
