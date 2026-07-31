package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryStatusChange;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryStatusChangeRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryStatusChangeItem;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryStatusChangeResult;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Atomically changes workflow states for lexical entries in one language graph. */
public final class LexicalEntryStatusManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final Repository repository;

    /** Runtime constructor used by {@link ManagerFactory}. */
    public LexicalEntryStatusManager() {
        this(null);
    }

    LexicalEntryStatusManager(Repository repository) {
        this.repository = repository;
    }

    /** Validates and applies the complete batch in one repository transaction. */
    public LexicalEntryStatusChangeResult change(
            LexicalEntryStatusChangeRequest request, String author) {
        ValidatedBatch batch = validate(request);
        String resolvedAuthor = LexiconCrudSupport.author(author);
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            Resource graph = vf.createIRI(
                    LexiconCrudSupport.lexicalGraphUri(batch.language));
            Resource schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
            List<CurrentChange> changes = new ArrayList<CurrentChange>();
            for (ValidatedChange requested : batch.changes) {
                validateLexicalEntry(connection, requested.entry, graph, schemaGraph);
                LexicalWorkflowStatus current = currentStatus(
                        connection, requested.entry, graph);
                if (current != requested.from) {
                    throw conflict("STATUS_MISMATCH", "entry "
                            + requested.entry.stringValue() + " currently has status "
                            + current.value() + ", not " + requested.from.value());
                }
                if (!current.canTransitionTo(requested.to)) {
                    throw conflict("STATUS_TRANSITION_NOT_ALLOWED", "status "
                            + current.value() + " cannot change to "
                            + requested.to.value());
                }
                changes.add(new CurrentChange(requested.entry, current, requested.to));
            }

            String timestamp = LexiconCrudSupport.operationTimestamp();
            Literal modified = vf.createLiteral(timestamp, XSD.DATETIME);
            Literal changedBy = vf.createLiteral(resolvedAuthor);
            IRI statusProperty = vf.createIRI(LEXO + "status");
            IRI changedByProperty = vf.createIRI(LEXO + "statusChangedBy");
            for (CurrentChange change : changes) {
                connection.remove(change.entry, statusProperty, null, graph);
                connection.remove(change.entry, DCTERMS.MODIFIED, null, graph);
                connection.remove(change.entry, changedByProperty, null, graph);
                connection.add(change.entry, statusProperty,
                        vf.createLiteral(change.to.value()), graph);
                connection.add(change.entry, DCTERMS.MODIFIED, modified, graph);
                connection.add(change.entry, changedByProperty, changedBy, graph);
            }
            connection.commit();
            return result(batch.language, resolvedAuthor, timestamp, changes);
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        } finally {
            release(connection);
        }
    }

    private ValidatedBatch validate(LexicalEntryStatusChangeRequest request) {
        if (request == null) {
            throw invalid("MISSING_STATUS_CHANGE", "request body is required");
        }
        String language = Iso639LanguageValidator.get().requireValid(request.language);
        if (request.entries == null || request.entries.isEmpty()) {
            throw invalid("MISSING_STATUS_ENTRIES",
                    "entries must contain at least one status change");
        }
        Set<String> seen = new HashSet<String>();
        List<ValidatedChange> changes = new ArrayList<ValidatedChange>();
        for (int index = 0; index < request.entries.size(); index++) {
            LexicalEntryStatusChange item = request.entries.get(index);
            if (item == null) {
                throw invalid("INVALID_STATUS_ENTRY",
                        "entries[" + index + "] must be an object");
            }
            IRI entry = requireIri(item.entry, "entries[" + index + "].entry");
            if (!seen.add(entry.stringValue())) {
                throw invalid("DUPLICATE_STATUS_ENTRY",
                        "entry " + entry.stringValue() + " occurs more than once");
            }
            LexicalWorkflowStatus from = LexicalWorkflowStatus.require(
                    item.fromStatus, "entries[" + index + "].fromStatus");
            LexicalWorkflowStatus to = LexicalWorkflowStatus.require(
                    item.toStatus, "entries[" + index + "].toStatus");
            changes.add(new ValidatedChange(entry, from, to));
        }
        return new ValidatedBatch(language, changes);
    }

    private void validateLexicalEntry(RepositoryConnection connection, IRI entry,
                                      Resource graph, Resource schemaGraph) {
        boolean exists = connection.hasStatement(entry, null, null, false, graph);
        if (!exists) {
            throw notFound("ENTRY_NOT_FOUND", "entry " + entry.stringValue()
                    + " does not exist in the selected language graph");
        }
        IRI lexicalEntry = vf.createIRI(ONTOLEX + "LexicalEntry");
        try (RepositoryResult<Statement> types = connection.getStatements(
                entry, RDF.TYPE, null, false, graph)) {
            while (types.hasNext()) {
                Value type = types.next().getObject();
                if (type instanceof IRI && LexiconCrudSupport.isSubclassOf(
                        connection, (IRI) type, lexicalEntry, graph, schemaGraph)) {
                    return;
                }
            }
        }
        throw unprocessable("UNSUPPORTED_STATUS_RESOURCE_TYPE", "resource "
                + entry.stringValue() + " is not an ontolex:LexicalEntry");
    }

    private LexicalWorkflowStatus currentStatus(RepositoryConnection connection,
                                                IRI entry, Resource graph) {
        IRI property = vf.createIRI(LEXO + "status");
        Literal value = null;
        int count = 0;
        try (RepositoryResult<Statement> statuses = connection.getStatements(
                entry, property, null, false, graph)) {
            while (statuses.hasNext()) {
                Value object = statuses.next().getObject();
                count++;
                if (object instanceof Literal) {
                    value = (Literal) object;
                }
            }
        }
        if (count != 1 || value == null) {
            throw conflict("INVALID_STATUS_CARDINALITY", "entry "
                    + entry.stringValue() + " must have exactly one literal lexo:status");
        }
        try {
            return LexicalWorkflowStatus.require(value.getLabel(), "current status");
        } catch (IllegalArgumentException e) {
            throw conflict("INVALID_CURRENT_STATUS", "entry "
                    + entry.stringValue() + " has unsupported status "
                    + value.getLabel());
        }
    }

    private LexicalEntryStatusChangeResult result(
            String language, String author, String timestamp,
            List<CurrentChange> changes) {
        LexicalEntryStatusChangeResult result =
                new LexicalEntryStatusChangeResult();
        result.language = language;
        result.author = author;
        result.modified = timestamp;
        for (CurrentChange change : changes) {
            result.entries.add(new LexicalEntryStatusChangeItem(
                    change.entry.stringValue(), change.from.value(),
                    change.to.value()));
        }
        return result;
    }

    private IRI requireIri(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid("MISSING_ENTRY_IRI", field + " is required");
        }
        String normalized = value.trim();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw invalid("INVALID_ENTRY_IRI", field
                        + " must be an absolute IRI");
            }
            return vf.createIRI(normalized);
        } catch (URISyntaxException e) {
            throw invalid("INVALID_ENTRY_IRI", field
                    + " must be an absolute IRI");
        }
    }

    private IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private StatusChangeException notFound(String code, String message) {
        return new StatusChangeException(404, code + ": " + message);
    }

    private StatusChangeException conflict(String code, String message) {
        return new StatusChangeException(409, code + ": " + message);
    }

    private StatusChangeException unprocessable(String code, String message) {
        return new StatusChangeException(422, code + ": " + message);
    }

    private RepositoryConnection acquire() {
        return repository == null
                ? GraphDbUtil.getConnection(RepositoryTarget.LEXICON)
                : repository.getConnection();
    }

    private void release(RepositoryConnection connection) {
        if (repository == null) {
            GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON, connection);
        } else {
            connection.close();
        }
    }

    /** Domain failure carrying the intended HTTP response status. */
    public static final class StatusChangeException extends RuntimeException {
        public final int httpStatus;

        StatusChangeException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    private static final class ValidatedBatch {
        final String language;
        final List<ValidatedChange> changes;

        ValidatedBatch(String language, List<ValidatedChange> changes) {
            this.language = language;
            this.changes = changes;
        }
    }

    private static final class ValidatedChange {
        final IRI entry;
        final LexicalWorkflowStatus from;
        final LexicalWorkflowStatus to;

        ValidatedChange(IRI entry, LexicalWorkflowStatus from,
                        LexicalWorkflowStatus to) {
            this.entry = entry;
            this.from = from;
            this.to = to;
        }
    }

    private static final class CurrentChange {
        final IRI entry;
        final LexicalWorkflowStatus from;
        final LexicalWorkflowStatus to;

        CurrentChange(IRI entry, LexicalWorkflowStatus from,
                      LexicalWorkflowStatus to) {
            this.entry = entry;
            this.from = from;
            this.to = to;
        }
    }
}
