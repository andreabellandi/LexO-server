package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.service.data.text.output.TextCatalog;
import it.cnr.ilc.lexo.service.data.text.output.TextCatalogItem;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/**
 * Builds the UI-oriented catalog from LexOTexts and the per-text attestation
 * and annotation graph families stored in LexOLexica.
 */
public final class TextCatalogManager {

    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String OA = "http://www.w3.org/ns/oa#";
    private static final TextCatalogManager INSTANCE = new TextCatalogManager();

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final String graphBase;
    private final String structureNamespace;

    public static TextCatalogManager get() {
        return INSTANCE;
    }

    private TextCatalogManager() {
        graphBase = trailingSeparator(configured("TextGraphDb.namedGraphBase",
                "https://lexo.ilc.cnr.it/graphs/nif/"));
        structureNamespace = namespace(System.getProperty(
                "lexo.text.structureNamespace",
                "https://lexo.ilc.cnr.it/vocabulary/nif-structure#"));
    }

    /** Returns every text, optionally restricted to one existing corpus. */
    public TextCatalog list(String corpusId) {
        RepositoryConnection textConnection =
                GraphDbUtil.getConnection(RepositoryTarget.TEXT);
        RepositoryConnection lexicalConnection = null;
        try {
            lexicalConnection = GraphDbUtil.getConnection(RepositoryTarget.LEXICON);
            return list(textConnection, lexicalConnection, normalizeCorpusId(corpusId));
        } finally {
            if (lexicalConnection != null) {
                GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON, lexicalConnection);
            }
            if (textConnection != null) {
                GraphDbUtil.releaseConnection(RepositoryTarget.TEXT, textConnection);
            }
        }
    }

    TextCatalog list(RepositoryConnection textConnection,
                     RepositoryConnection lexicalConnection, String corpusId) {
        IRI requestedCorpus = corpusId == null
                ? null : findCorpus(textConnection, corpusId);
        if (corpusId != null && requestedCorpus == null) {
            throw new IllegalArgumentException("Corpus not found: " + corpusId);
        }

        List<TextCatalogItem> items = readTexts(textConnection, requestedCorpus);
        applyLexicalGraphCounts(lexicalConnection, items);
        Collections.sort(items, Comparator
                .comparing((TextCatalogItem item) -> item.name == null ? "" : item.name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> item.fileId == null ? "" : item.fileId));

        TextCatalog catalog = new TextCatalog();
        catalog.corpusId = corpusId;
        catalog.texts.addAll(items);
        catalog.total = items.size();
        return catalog;
    }

    private List<TextCatalogItem> readTexts(RepositoryConnection connection,
                                            IRI requestedCorpus) {
        Map<String, TextLocation> locations = new LinkedHashMap<String, TextLocation>();
        IRI isString = iri(NIF + "isString");
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, isString, null, false)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                Resource graph = statement.getContext();
                if (!(graph instanceof IRI)
                        || !graph.stringValue().startsWith(graphBase + "documents/")) {
                    continue;
                }
                String key = graph.stringValue();
                locations.putIfAbsent(key,
                        new TextLocation((IRI) graph, statement.getSubject()));
            }
        }

        List<TextCatalogItem> items = new ArrayList<TextCatalogItem>();
        for (TextLocation location : locations.values()) {
            Model model = readGraph(connection, location.graph);
            TextCatalogItem item = toItem(connection, model, location.context);
            if (item == null) {
                continue;
            }
            if (requestedCorpus != null
                    && !requestedCorpus.stringValue().equals(item.corpusUri)) {
                continue;
            }
            items.add(item);
        }
        return items;
    }

    private TextCatalogItem toItem(RepositoryConnection connection, Model model,
                                   Resource context) {
        String canonical = literal(model, context, iri(NIF + "isString"));
        String fileId = literal(model, context, iri(structureNamespace + "fileId"));
        if (canonical == null || fileId == null) {
            return null;
        }

        TextCatalogItem item = new TextCatalogItem();
        item.fileId = fileId;
        String contextUri = context.stringValue();
        item.documentUri = contextUri.endsWith("#context")
                ? contextUri.substring(0, contextUri.length() - "#context".length())
                : contextUri;
        Resource source = iri(item.documentUri + "/source");
        item.name = literal(model, source, DCTERMS.IDENTIFIER);
        if (item.name == null || item.name.trim().isEmpty()) {
            item.name = fileId;
        }
        item.sizeBytes = Long.valueOf(canonical.getBytes(StandardCharsets.UTF_8).length);
        item.sentenceCount = optionalCount(model, NIF + "Sentence");
        item.tokenCount = optionalCount(model, NIF + "Word");
        readMetadata(model, context, item);
        readCorpus(connection, model, context, item);
        return item;
    }

    private void readCorpus(RepositoryConnection connection, Model model, Resource context,
                            TextCatalogItem item) {
        for (Value value : model.filter(context, DCTERMS.IS_PART_OF, null).objects()) {
            if (!(value instanceof IRI)) {
                continue;
            }
            String corpusId = corpusId(connection, (IRI) value);
            if (corpusId != null) {
                item.corpusId = corpusId;
                item.corpusUri = value.stringValue();
                return;
            }
        }
    }

    private void readMetadata(Model model, Resource context, TextCatalogItem item) {
        Map<IRI, String> predicates = new LinkedHashMap<IRI, String>();
        predicates.put(DCTERMS.IDENTIFIER, "id");
        predicates.put(DCTERMS.TITLE, "title");
        predicates.put(DCTERMS.CREATOR, "author");
        predicates.put(DCTERMS.CREATED, "date");
        predicates.put(DCTERMS.LANGUAGE, "language");
        predicates.put(DCTERMS.FORMAT, "format");
        predicates.put(DCTERMS.IS_PART_OF, "corpus");
        for (Map.Entry<IRI, String> predicate : predicates.entrySet()) {
            List<String> values = new ArrayList<String>();
            for (Value value : model.filter(context, predicate.getKey(), null).objects()) {
                values.add(value.stringValue());
            }
            if (!values.isEmpty()) {
                item.metadata.put(predicate.getValue(), values.get(0));
                item.metadataValues.put(predicate.getValue(), values);
            }
        }
    }

    private void applyLexicalGraphCounts(RepositoryConnection connection,
                                         List<TextCatalogItem> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<String, TextCatalogItem> attestationsByGraph =
                new HashMap<String, TextCatalogItem>();
        Map<String, TextCatalogItem> annotationsByGraph =
                new HashMap<String, TextCatalogItem>();
        for (TextCatalogItem item : items) {
            attestationsByGraph.put(LexicalNamedGraphs.attestationGraphUri(item.fileId), item);
            annotationsByGraph.put(LexicalNamedGraphs.annotationGraphUri(item.fileId), item);
        }
        applyTypeCounts(connection, attestationsByGraph, iri(FRAC + "Attestation"), true);
        applyTypeCounts(connection, annotationsByGraph, iri(OA + "Annotation"), false);
    }

    private void applyTypeCounts(RepositoryConnection connection,
                                 Map<String, TextCatalogItem> itemsByGraph,
                                 IRI type, boolean attestations) {
        Map<String, Set<String>> counted = new HashMap<String, Set<String>>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, RDF.TYPE, type, true)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                Resource graph = statement.getContext();
                if (!(graph instanceof IRI)) {
                    continue;
                }
                TextCatalogItem item = itemsByGraph.get(graph.stringValue());
                if (item == null) {
                    continue;
                }
                Set<String> resources = counted.computeIfAbsent(graph.stringValue(),
                        key -> new HashSet<String>());
                if (resources.add(statement.getSubject().stringValue())) {
                    if (attestations) {
                        item.attestationCount = Long.valueOf(
                                item.attestationCount.longValue() + 1L);
                    } else {
                        item.annotationCount = Long.valueOf(
                                item.annotationCount.longValue() + 1L);
                    }
                }
            }
        }
    }

    private IRI findCorpus(RepositoryConnection connection, String corpusId) {
        IRI predicate = iri(structureNamespace + "corpusId");
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, predicate, vf.createLiteral(corpusId), false)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                if (statement.getContext() instanceof IRI
                        && statement.getContext().stringValue()
                                .startsWith(graphBase + "corpora/")
                        && statement.getSubject() instanceof IRI) {
                    return (IRI) statement.getSubject();
                }
            }
        }
        return null;
    }

    private String corpusId(RepositoryConnection connection, IRI corpus) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                corpus, iri(structureNamespace + "corpusId"), null, false)) {
            return statements.hasNext()
                    ? statements.next().getObject().stringValue() : null;
        }
    }

    private Model readGraph(RepositoryConnection connection, IRI graph) {
        Model model = new LinkedHashModel();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, null, null, true, graph)) {
            while (statements.hasNext()) {
                model.add(statements.next());
            }
        }
        return model;
    }

    private Integer optionalCount(Model model, String type) {
        int count = model.filter(null, RDF.TYPE, iri(type)).subjects().size();
        return count == 0 ? null : Integer.valueOf(count);
    }

    private String literal(Model model, Resource subject, IRI predicate) {
        if (subject == null) {
            return null;
        }
        Set<Value> values = model.filter(subject, predicate, null).objects();
        return values.isEmpty() ? null : values.iterator().next().stringValue();
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }

    private static String normalizeCorpusId(String corpusId) {
        if (corpusId == null || corpusId.trim().isEmpty()) {
            return null;
        }
        String normalized = corpusId.trim();
        if (!normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid corpusId");
        }
        return normalized;
    }

    private static String configured(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        return value == null || value.trim().isEmpty() || value.contains("${")
                ? fallback : value.trim();
    }

    private static String trailingSeparator(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "/";
    }

    private static String namespace(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "#";
    }

    private static final class TextLocation {
        final IRI graph;
        final Resource context;

        TextLocation(IRI graph, Resource context) {
            this.graph = graph;
            this.context = context;
        }
    }
}
