package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.WordnetCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.WordnetPatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** CRUD and discovery for WordNet roots and their application graphs. */
public final class WordnetRegistryManager extends AbstractWordnetManager {

    private static final Object CREATE_LOCK = new Object();

    public WordnetRegistryManager() {
        this(null);
    }

    public WordnetRegistryManager(Repository repository) {
        super(repository);
    }

    public Map<String, Object> create(WordnetCreate request, String author) {
        if (request == null) {
            throw rdf.invalid("MISSING_REQUIRED_FIELD", "request is required");
        }
        final String wordnetId = resolver.requireWordnetId(request.wordnetId);
        final String graphUri = resolver.graphUri(wordnetId, request.language);
        final String language = graphUri.substring(graphUri.lastIndexOf('/',
                graphUri.lastIndexOf('/') - 1) + 1, graphUri.lastIndexOf('/'));
        synchronized (CREATE_LOCK) {
            RepositoryConnection connection = acquire();
            try {
                connection.begin();
                resolver.requireAvailableId(connection, wordnetId);
                IRI graph = SimpleValueFactory.getInstance().createIRI(graphUri);
                IRI root = rdf.mint(connection);
                String timestamp = rdf.timestamp();
                connection.add(root, RDF.TYPE, WordnetVocabulary.LEXICON, graph);
                connection.add(root, WordnetVocabulary.LANGUAGE,
                        SimpleValueFactory.getInstance().createLiteral(language), graph);
                rdf.addAudit(connection, root, author, timestamp, graph);
                rdf.addNativeAndMetadata(connection, root, request, graph);
                connection.commit();
                return rootResponse(connection, new WordnetGraphResolver.ResolvedWordnet(
                        wordnetId, language, graph, root), true, true, true);
            } catch (RuntimeException ex) {
                rollback(connection);
                throw ex;
            } finally {
                release(connection);
            }
        }
    }

    public Map<String, Object> get(String wordnetId, String sections) {
        SectionSelection selection = SectionSelection.root(sections);
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet resolved =
                    resolver.resolve(connection, wordnetId);
            return rootResponse(connection, resolved, selection.core,
                    selection.metadata,
                    selection.statistics);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> list(String language, String label,
            int limit, int offset) {
        validatePaging(limit, offset);
        String normalizedLanguage = language == null || language.trim().isEmpty()
                ? null : it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator.get()
                        .requireValid(language);
        String normalizedLabel = label == null ? null
                : label.toLowerCase(Locale.ROOT);
        RepositoryConnection connection = acquire();
        try {
            List<Map<String, Object>> matching = new ArrayList<Map<String, Object>>();
            for (WordnetGraphResolver.ResolvedWordnet item : resolver.list(connection)) {
                String displayLabel = displayLabel(connection, item);
                if (normalizedLanguage != null
                        && !normalizedLanguage.equals(item.language)) {
                    continue;
                }
                if (normalizedLabel != null
                        && !displayLabel.toLowerCase(Locale.ROOT)
                                .contains(normalizedLabel)) {
                    continue;
                }
                Map<String, Object> summary = new LinkedHashMap<String, Object>();
                summary.put("id", item.wordnetId);
                summary.put("wordnetId", item.wordnetId);
                summary.put("iri", item.root.stringValue());
                summary.put("language", item.language);
                summary.put("displayLabel", displayLabel);
                summary.put("graph", item.graph.stringValue());
                summary.put("statistics", statistics(connection, item.graph));
                matching.add(summary);
            }
            matching.sort((left, right) -> ((String) left.get("iri"))
                    .compareTo((String) right.get("iri")));
            int from = Math.min(offset, matching.size());
            int to = Math.min(from + limit, matching.size());
            Map<String, Object> page = new LinkedHashMap<String, Object>();
            page.put("items", new ArrayList<Map<String, Object>>(
                    matching.subList(from, to)));
            page.put("total", Integer.valueOf(matching.size()));
            page.put("limit", Integer.valueOf(limit));
            page.put("offset", Integer.valueOf(offset));
            return page;
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> patch(String wordnetId, WordnetPatch request,
            String author) {
        if (request == null) {
            throw rdf.invalid("INVALID_PATCH", "request is required");
        }
        if (request.hasWordnetId() || request.hasLanguage()
                || request.hasGraph() || request.hasIri()) {
            throw rdf.invalid("INVALID_PATCH",
                    "wordnetId, language, graph and iri are immutable");
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet resolved =
                    resolver.resolve(connection, wordnetId);
            connection.begin();
            rdf.checkExpectedModified(connection, resolved.root, request,
                    resolved.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, resolved.root,
                    request, resolved.graph);
            if (!changed) {
                throw rdf.invalid("INVALID_PATCH", "No effective change supplied");
            }
            String timestamp = rdf.timestamp();
            rdf.touch(connection, resolved.root, timestamp, resolved.graph);
            connection.commit();
            return rootResponse(connection, resolved, true, true, false);
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    public void delete(String wordnetId, String expectedModified) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet resolved =
                    resolver.resolve(connection, wordnetId);
            connection.begin();
            if (expectedModified != null && !expectedModified.equals(rdf.first(
                    connection, resolved.root,
                    org.eclipse.rdf4j.model.vocabulary.DCTERMS.MODIFIED,
                    resolved.graph))) {
                throw new WordnetException(409, "MODIFIED_MISMATCH",
                        "WordNet was modified after the supplied precondition");
            }
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, null, null, false, resolved.graph)) {
                while (statements.hasNext()) {
                    Statement statement = statements.next();
                    if (!resolved.root.equals(statement.getSubject())) {
                        throw new WordnetException(409, "WORDNET_NOT_EMPTY",
                                "Delete dependent WordNet resources first");
                    }
                }
            }
            connection.clear(resolved.graph);
            connection.commit();
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    private Map<String, Object> rootResponse(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet resolved, boolean core,
            boolean metadata, boolean includeStatistics) {
        Map<String, Object> result = rdf.baseResponse(connection,
                resolved.wordnetId, "wordnet", resolved.root, resolved.graph,
                core);
        result.put("id", resolved.wordnetId);
        result.put("wordnetId", resolved.wordnetId);
        result.put("graph", resolved.graph.stringValue());
        if (core) {
            result.put("language", resolved.language);
            result.put("displayLabel", displayLabel(connection, resolved));
        }
        if (metadata) {
            result.put("metadata", rdf.readMetadata(connection, resolved.root,
                    resolved.graph));
        }
        if (includeStatistics) {
            result.put("statistics", statistics(connection, resolved.graph));
        }
        return result;
    }

    private String displayLabel(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet resolved) {
        String preferred = null;
        String fallback = null;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                resolved.root, RDFS.LABEL, null, false, resolved.graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof org.eclipse.rdf4j.model.Literal) {
                    org.eclipse.rdf4j.model.Literal literal =
                            (org.eclipse.rdf4j.model.Literal) value;
                    if (literal.getLanguage().isPresent()
                            && resolved.language.equals(literal.getLanguage().get())) {
                        if (preferred == null
                                || literal.getLabel().compareTo(preferred) < 0) {
                            preferred = literal.getLabel();
                        }
                    }
                    if (fallback == null
                            || literal.getLabel().compareTo(fallback) < 0) {
                        fallback = literal.getLabel();
                    }
                }
            }
        }
        return preferred != null ? preferred
                : fallback == null ? resolved.wordnetId : fallback;
    }

    private Map<String, Integer> statistics(RepositoryConnection connection,
            Resource graph) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("entries", Integer.valueOf(count(connection,
                WordnetVocabulary.ENTRY, graph)));
        counts.put("forms", Integer.valueOf(count(connection,
                WordnetVocabulary.FORM, graph)));
        counts.put("senses", Integer.valueOf(count(connection,
                WordnetVocabulary.SENSE, graph)));
        counts.put("synsets", Integer.valueOf(count(connection,
                WordnetVocabulary.SYNSET, graph)));
        counts.put("relations", Integer.valueOf(count(connection,
                WordnetVocabulary.CONCEPTUAL_RELATION, graph)
                + count(connection, WordnetVocabulary.SENSE_RELATION, graph)));
        return counts;
    }

    private int count(RepositoryConnection connection, IRI type, Resource graph) {
        int count = 0;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, RDF.TYPE, type, false, graph)) {
            while (statements.hasNext()) {
                statements.next();
                count++;
            }
        }
        return count;
    }

    private void validatePaging(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw rdf.invalid("INVALID_PAGINATION",
                    "limit must be 1..100 and offset must be nonnegative");
        }
    }

    private static final class SectionSelection {
        final boolean core;
        final boolean metadata;
        final boolean statistics;

        SectionSelection(boolean core, boolean metadata, boolean statistics) {
            this.core = core;
            this.metadata = metadata;
            this.statistics = statistics;
        }

        static SectionSelection root(String sections) {
            if (sections == null || "all".equals(sections.trim())) {
                return new SectionSelection(true, true, true);
            }
            boolean core = false;
            boolean metadata = false;
            boolean statistics = false;
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (String token : sections.split(",", -1)) {
                String value = token.trim();
                if (value.isEmpty() || !seen.add(value)
                        || (!"core".equals(value) && !"metadata".equals(value)
                        && !"statistics".equals(value))) {
                    throw new WordnetException(400, "INVALID_SECTION",
                            "Invalid or duplicate WordNet section: " + value);
                }
                core |= "core".equals(value);
                metadata |= "metadata".equals(value);
                statistics |= "statistics".equals(value);
            }
            return new SectionSelection(core, metadata, statistics);
        }
    }
}
