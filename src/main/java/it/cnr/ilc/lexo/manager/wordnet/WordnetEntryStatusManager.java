package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.StatusBatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.StatusChange;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Dedicated WordNet Entry workflow service; independent of ordinary Lexica. */
public final class WordnetEntryStatusManager extends AbstractWordnetManager {

    private final ValueFactory vf = SimpleValueFactory.getInstance();

    public WordnetEntryStatusManager() {
        this(null);
    }

    public WordnetEntryStatusManager(Repository repository) {
        super(repository);
    }

    public Map<String, Object> get(String wordnetId, String entryId) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI entry = rdf.resource(connection, entryId, WordnetVocabulary.ENTRY,
                    wordnet.graph, "ENTRY_NOT_FOUND");
            WordnetEntryManager.requireOwnedEntry(connection, wordnet, entry);
            return status(connection, wordnet, entry);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> change(String wordnetId, StatusBatch request,
            String author) {
        if (request == null || request.entries == null || request.entries.isEmpty())
            throw rdf.invalid("INVALID_PATCH", "entries must be non-empty");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            connection.begin();
            Set<String> ids = new HashSet<String>();
            List<Transition> transitions = new ArrayList<Transition>();
            for (StatusChange item : request.entries) {
                if (item == null || item.entryId == null || !ids.add(item.entryId))
                    throw rdf.invalid("INVALID_PATCH", "Entry ids must be unique");
                requireStatus(item.fromStatus);
                requireStatus(item.toStatus);
                IRI entry = rdf.resource(connection, item.entryId,
                        WordnetVocabulary.ENTRY, wordnet.graph, "ENTRY_NOT_FOUND");
                WordnetEntryManager.requireOwnedEntry(connection, wordnet, entry);
                String current = currentStatus(connection, entry, wordnet.graph);
                if (!item.fromStatus.equals(current)) throw new WordnetException(409,
                        "STATUS_MISMATCH", "Stored status does not match fromStatus");
                if (!allowed(item.fromStatus, item.toStatus))
                    throw new WordnetException(409, "STATUS_TRANSITION_NOT_ALLOWED",
                            "Unsupported workflow transition");
                transitions.add(new Transition(entry, item.entryId,
                        item.fromStatus, item.toStatus));
            }
            String timestamp = rdf.timestamp();
            String updater = it.cnr.ilc.lexo.manager.LexiconCrudSupport.author(author);
            for (Transition transition : transitions) {
                connection.remove(transition.entry, WordnetVocabulary.WORKFLOW_STATUS,
                        null, wordnet.graph);
                connection.add(transition.entry, WordnetVocabulary.WORKFLOW_STATUS,
                        vf.createLiteral(transition.to), wordnet.graph);
                connection.remove(transition.entry,
                        WordnetVocabulary.STATUS_CHANGED_BY, null, wordnet.graph);
                connection.add(transition.entry,
                        WordnetVocabulary.STATUS_CHANGED_BY,
                        vf.createLiteral(updater), wordnet.graph);
                rdf.touch(connection, transition.entry, timestamp, wordnet.graph);
            }
            connection.commit();
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("wordnetId", wordnet.wordnetId);
            result.put("author", updater);
            result.put("modified", timestamp);
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (Transition transition : transitions) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("entryId", transition.id);
                item.put("iri", transition.entry.stringValue());
                item.put("fromStatus", transition.from);
                item.put("toStatus", transition.to);
                items.add(item);
            }
            result.put("entries", items);
            return result;
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    private Map<String, Object> status(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI entry) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("wordnetId", wordnet.wordnetId);
        result.put("entryId", WordnetIdCodec.encode(entry.stringValue()));
        result.put("iri", entry.stringValue());
        result.put("workflowStatus", currentStatus(connection, entry, wordnet.graph));
        List<String> changedBy = literals(connection, entry,
                WordnetVocabulary.STATUS_CHANGED_BY, wordnet.graph);
        if (changedBy.size() > 1) throw new WordnetException(409,
                "INVALID_STATUS_CARDINALITY", "Entry has multiple statusChangedBy values");
        result.put("statusChangedBy", changedBy.isEmpty() ? null : changedBy.get(0));
        result.put("modified", rdf.first(connection, entry, DCTERMS.MODIFIED,
                wordnet.graph));
        return result;
    }

    private String currentStatus(RepositoryConnection connection, IRI entry,
            Resource graph) {
        List<String> values = literals(connection, entry,
                WordnetVocabulary.WORKFLOW_STATUS, graph);
        if (values.size() != 1) throw new WordnetException(409,
                "INVALID_STATUS_CARDINALITY", "Entry must have exactly one workflow status");
        String status = values.get(0);
        if (!supported(status)) throw new WordnetException(409,
                "INVALID_CURRENT_STATUS", "Stored workflow status is unsupported");
        return status;
    }

    private List<String> literals(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        List<String> result = new ArrayList<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (!(value instanceof Literal)) throw new WordnetException(409,
                        "INVALID_CURRENT_STATUS", "Workflow value must be a literal");
                result.add(value.stringValue());
            }
        }
        return result;
    }

    private void requireStatus(String status) {
        if (!supported(status)) throw rdf.invalid("INVALID_WORKFLOW_STATUS",
                "workflow status must be working, completed or revised");
    }

    private boolean supported(String status) {
        return "working".equals(status) || "completed".equals(status)
                || "revised".equals(status);
    }

    private boolean allowed(String from, String to) {
        return ("working".equals(from) && "completed".equals(to))
                || ("completed".equals(from) && ("working".equals(to)
                        || "revised".equals(to)))
                || ("revised".equals(from) && "completed".equals(to));
    }

    private static final class Transition {
        final IRI entry;
        final String id;
        final String from;
        final String to;
        Transition(IRI entry, String id, String from, String to) {
            this.entry = entry; this.id = id; this.from = from; this.to = to;
        }
    }
}
