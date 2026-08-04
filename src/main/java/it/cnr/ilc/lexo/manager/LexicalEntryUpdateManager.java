package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryUpdateRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryUpdateResult;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.SortedSet;
import java.util.TreeSet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Atomically updates the mutable core properties of a lexical entry. */
public final class LexicalEntryUpdateManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LEXINFO =
            "http://www.lexinfo.net/ontology/3.0/lexinfo#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final Repository repository;

    public LexicalEntryUpdateManager() {
        this(null);
    }

    LexicalEntryUpdateManager(Repository repository) {
        this.repository = repository;
    }

    public LexicalEntryUpdateResult update(LexicalEntryUpdateRequest request,
                                            String author) {
        ValidatedRequest input = validate(request);
        String updater = LexiconCrudSupport.author(author);
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            Resource graph = vf.createIRI(
                    LexiconCrudSupport.lexicalGraphUri(input.language));
            Resource schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
            validateTarget(connection, input.entry, graph, schemaGraph);
            if (input.type != null) {
                validateEntryType(connection, input.type, graph, schemaGraph);
            }
            if (input.posPresent && input.pos != null) {
                validatePartOfSpeech(connection, input.pos, graph, schemaGraph);
            }
            validateExpectedModified(connection, input.entry,
                    input.expectedModified, graph);

            if (input.label != null) {
                connection.remove(input.entry, RDFS.LABEL, null, graph);
                connection.add(input.entry, RDFS.LABEL,
                        vf.createLiteral(input.label, input.language), graph);
            }
            if (input.type != null) {
                connection.remove(input.entry, RDF.TYPE, null, graph);
                connection.add(input.entry, RDF.TYPE, input.type, graph);
            }
            if (input.posPresent) {
                IRI predicate = vf.createIRI(LEXINFO + "partOfSpeech");
                connection.remove(input.entry, predicate, null, graph);
                if (input.pos != null) {
                    connection.add(input.entry, predicate, input.pos, graph);
                }
            }
            String modified = LexiconCrudSupport.operationTimestamp();
            connection.remove(input.entry, DCTERMS.MODIFIED, null, graph);
            connection.add(input.entry, DCTERMS.MODIFIED,
                    vf.createLiteral(modified, XSD.DATETIME), graph);

            LexicalEntryUpdateResult result = result(connection, input.entry,
                    input.language, updater, modified, graph, schemaGraph);
            connection.commit();
            return result;
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        } finally {
            release(connection);
        }
    }

    private ValidatedRequest validate(LexicalEntryUpdateRequest request) {
        if (request == null) {
            throw invalid("MISSING_ENTRY_UPDATE", "request body is required");
        }
        IRI entry = requireIri("entry", request.getEntry(), "INVALID_ENTRY_IRI");
        String language = Iso639LanguageValidator.get()
                .requireValid(request.getLanguage());
        if (!request.hasLabel() && !request.hasType() && !request.hasPos()) {
            throw invalid("MISSING_ENTRY_CHANGES",
                    "at least one of label, type, or pos must be supplied");
        }
        String label = null;
        if (request.hasLabel()) {
            requireText(request.getLabel(), "INVALID_ENTRY_LABEL",
                    "label must not be blank");
            label = request.getLabel();
        }
        IRI type = null;
        if (request.hasType()) {
            type = requireIri("type", LexiconCrudSupport.expandLexicalIri(
                    requireText(request.getType(), "INVALID_ENTRY_TYPE_IRI",
                            "type must be an absolute IRI")),
                    "INVALID_ENTRY_TYPE_IRI");
        }
        IRI pos = null;
        if (request.hasPos() && request.getPos() != null) {
            pos = requireIri("pos", LexiconCrudSupport.expandLexicalIri(
                    requireText(request.getPos(), "INVALID_PART_OF_SPEECH_IRI",
                            "pos must be an absolute IRI or null")),
                    "INVALID_PART_OF_SPEECH_IRI");
        }
        String expected = null;
        if (request.hasExpectedModified()) {
            expected = requireText(request.getExpectedModified(),
                    "INVALID_EXPECTED_MODIFIED",
                    "expectedModified must not be blank");
        }
        return new ValidatedRequest(entry, language, label, type, pos,
                request.hasPos(), expected);
    }

    private void validateTarget(RepositoryConnection connection, IRI entry,
                                Resource graph, Resource schemaGraph) {
        if (!connection.hasStatement(entry, null, null, false, graph)) {
            throw failure(404, "ENTRY_NOT_FOUND",
                    entry.stringValue() + " does not exist in the language graph");
        }
        try (RepositoryResult<Statement> types = connection.getStatements(
                entry, RDF.TYPE, null, false, graph)) {
            while (types.hasNext()) {
                Value value = types.next().getObject();
                if (value instanceof IRI && LexiconCrudSupport.isSubclassOf(
                        connection, (IRI) value,
                        vf.createIRI(ONTOLEX + "LexicalEntry"),
                        graph, schemaGraph)) {
                    return;
                }
            }
        }
        throw failure(422, "INVALID_ENTRY_RESOURCE_TYPE",
                entry.stringValue() + " is not a lexical entry");
    }

    private void validateEntryType(RepositoryConnection connection, IRI type,
                                   Resource graph, Resource schemaGraph) {
        if (!LexiconCrudSupport.isSubclassOf(connection, type,
                vf.createIRI(ONTOLEX + "LexicalEntry"), graph, schemaGraph)) {
            throw failure(422, "INVALID_ENTRY_TYPE",
                    "type must be ontolex:LexicalEntry or one of its subclasses");
        }
    }

    private void validatePartOfSpeech(RepositoryConnection connection, IRI pos,
                                      Resource graph, Resource schemaGraph) {
        IRI expected = vf.createIRI(LEXINFO + "PartOfSpeech");
        try (RepositoryResult<Statement> types = connection.getStatements(
                pos, RDF.TYPE, null, false, graph, schemaGraph)) {
            while (types.hasNext()) {
                Value value = types.next().getObject();
                if (value instanceof IRI && LexiconCrudSupport.isSubclassOf(
                        connection, (IRI) value, expected, graph, schemaGraph)) {
                    return;
                }
            }
        }
        throw failure(422, "INVALID_PART_OF_SPEECH",
                pos.stringValue() + " is not a lexinfo:PartOfSpeech individual");
    }

    private void validateExpectedModified(RepositoryConnection connection,
                                          IRI entry, String expected,
                                          Resource graph) {
        if (expected != null && !connection.hasStatement(entry,
                DCTERMS.MODIFIED, vf.createLiteral(expected, XSD.DATETIME),
                false, graph)) {
            throw failure(409, "MODIFIED_MISMATCH",
                    "expectedModified does not match the stored value");
        }
    }

    private LexicalEntryUpdateResult result(RepositoryConnection connection,
                                             IRI entry, String language,
                                             String author, String modified,
                                             Resource graph,
                                             Resource schemaGraph) {
        LexicalEntryUpdateResult result = new LexicalEntryUpdateResult();
        result.entry = entry.stringValue();
        result.language = language;
        result.author = author;
        result.modified = modified;
        result.label = firstLiteral(connection, entry, RDFS.LABEL, graph);
        result.type = firstEntryType(connection, entry, graph, schemaGraph);
        result.pos = firstIri(connection, entry,
                vf.createIRI(LEXINFO + "partOfSpeech"), graph);
        return result;
    }

    private String firstEntryType(RepositoryConnection connection, IRI entry,
                                  Resource graph, Resource schemaGraph) {
        SortedSet<String> values = new TreeSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                entry, RDF.TYPE, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof IRI && LexiconCrudSupport.isSubclassOf(
                        connection, (IRI) value,
                        vf.createIRI(ONTOLEX + "LexicalEntry"),
                        graph, schemaGraph)) {
                    values.add(value.stringValue());
                }
            }
        }
        return values.isEmpty() ? null : values.first();
    }

    private String firstLiteral(RepositoryConnection connection, IRI subject,
                                IRI predicate, Resource graph) {
        SortedSet<String> values = new TreeSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof Literal) {
                    values.add(value.stringValue());
                }
            }
        }
        return values.isEmpty() ? null : values.first();
    }

    private String firstIri(RepositoryConnection connection, IRI subject,
                            IRI predicate, Resource graph) {
        SortedSet<String> values = new TreeSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof IRI) {
                    values.add(value.stringValue());
                }
            }
        }
        return values.isEmpty() ? null : values.first();
    }

    private IRI requireIri(String field, String value, String code) {
        String normalized = requireText(value, code,
                field + " must be an absolute IRI").trim();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw invalid(code, field + " must be an absolute IRI");
            }
            return vf.createIRI(normalized);
        } catch (URISyntaxException e) {
            throw invalid(code, field + " must be an absolute IRI");
        }
    }

    private String requireText(String value, String code, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(code, message);
        }
        return value.trim();
    }

    private IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private EntryUpdateException failure(int status, String code,
                                         String message) {
        return new EntryUpdateException(status, code + ": " + message);
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
    public static final class EntryUpdateException extends RuntimeException {
        public final int httpStatus;

        EntryUpdateException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    private static final class ValidatedRequest {
        final IRI entry;
        final String language;
        final String label;
        final IRI type;
        final IRI pos;
        final boolean posPresent;
        final String expectedModified;

        ValidatedRequest(IRI entry, String language, String label, IRI type,
                         IRI pos, boolean posPresent, String expectedModified) {
            this.entry = entry;
            this.language = language;
            this.label = label;
            this.type = type;
            this.pos = pos;
            this.posPresent = posPresent;
            this.expectedModified = expectedModified;
        }
    }
}
