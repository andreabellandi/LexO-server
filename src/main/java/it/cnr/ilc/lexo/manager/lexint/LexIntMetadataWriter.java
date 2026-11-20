package it.cnr.ilc.lexo.manager.lexint;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Enforces creator/created/modified on every runtime IRI. */
public final class LexIntMetadataWriter {
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final Clock clock;

    public LexIntMetadataWriter() { this(Clock.systemUTC()); }
    public LexIntMetadataWriter(Clock clock) { this.clock = clock; }

    public String createIndividual(RepositoryConnection connection, IRI iri,
            LexIntActor actor, Resource graph) {
        if (connection == null || iri == null || actor == null || graph == null) {
            throw LexIntException.invalid(
                    "IRI, actor, graph and repository connection are required");
        }
        if (hasAnyMetadata(connection, iri, graph)) {
            throw new LexIntException(409, "LEXINT_METADATA_CONFLICT",
                    "Runtime metadata already exists for " + iri.stringValue());
        }
        String timestamp = Instant.now(clock).toString();
        connection.add(iri, DCTERMS.CREATOR,
                vf.createLiteral(actor.username), graph);
        Literal dateTime = vf.createLiteral(timestamp, XSD.DATETIME);
        connection.add(iri, DCTERMS.CREATED, dateTime, graph);
        connection.add(iri, DCTERMS.MODIFIED, dateTime, graph);
        return timestamp;
    }

    public String touch(RepositoryConnection connection, IRI iri,
            LexIntActor actor, Resource graph) {
        validate(connection, iri, graph);
        String previous = literal(connection, iri, DCTERMS.MODIFIED, graph);
        Instant now = Instant.now(clock);
        try {
            Instant old = Instant.parse(previous);
            if (!now.isAfter(old)) {
                now = old.plusMillis(1L);
            }
        } catch (RuntimeException ex) {
            throw new LexIntException(409, "LEXINT_METADATA_CONFLICT",
                    "Stored modified timestamp is invalid");
        }
        String timestamp = now.toString();
        connection.remove(iri, DCTERMS.MODIFIED, null, graph);
        connection.add(iri, DCTERMS.MODIFIED,
                vf.createLiteral(timestamp, XSD.DATETIME), graph);
        return timestamp;
    }

    public void validate(RepositoryConnection connection, IRI iri,
            Resource graph) {
        requireSingle(connection, iri, DCTERMS.CREATOR, false, graph);
        requireSingle(connection, iri, DCTERMS.CREATED, true, graph);
        requireSingle(connection, iri, DCTERMS.MODIFIED, true, graph);
    }

    public String creator(RepositoryConnection connection, IRI iri,
            Resource graph) {
        validate(connection, iri, graph);
        return literal(connection, iri, DCTERMS.CREATOR, graph);
    }

    public String created(RepositoryConnection connection, IRI iri,
            Resource graph) {
        validate(connection, iri, graph);
        return literal(connection, iri, DCTERMS.CREATED, graph);
    }

    public String modified(RepositoryConnection connection, IRI iri,
            Resource graph) {
        validate(connection, iri, graph);
        return literal(connection, iri, DCTERMS.MODIFIED, graph);
    }

    private boolean hasAnyMetadata(RepositoryConnection connection, IRI iri,
            Resource graph) {
        return connection.hasStatement(iri, DCTERMS.CREATOR, null, false, graph)
                || connection.hasStatement(iri, DCTERMS.CREATED, null, false,
                        graph)
                || connection.hasStatement(iri, DCTERMS.MODIFIED, null, false,
                        graph);
    }

    private void requireSingle(RepositoryConnection connection, IRI iri,
            IRI predicate, boolean dateTime, Resource graph) {
        List<Value> values = new ArrayList<Value>();
        try (RepositoryResult<Statement> rows = connection.getStatements(iri,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                values.add(rows.next().getObject());
            }
        }
        if (values.size() != 1 || !(values.get(0) instanceof Literal)) {
            throw new LexIntException(409, "LEXINT_METADATA_CONFLICT",
                    "Exactly one server metadata value is required for "
                    + predicate.stringValue());
        }
        Literal literal = (Literal) values.get(0);
        if (dateTime && !XSD.DATETIME.equals(literal.getDatatype())) {
            throw new LexIntException(409, "LEXINT_METADATA_CONFLICT",
                    "Runtime dates must be xsd:dateTime");
        }
        if (dateTime) {
            try { Instant.parse(literal.getLabel()); }
            catch (RuntimeException ex) {
                throw new LexIntException(409, "LEXINT_METADATA_CONFLICT",
                        "Runtime dates must be UTC parseable dateTimes");
            }
        }
    }

    private String literal(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        try (RepositoryResult<Statement> rows = connection.getStatements(
                subject, predicate, null, false, graph)) {
            if (!rows.hasNext()) {
                throw new LexIntException(409, "LEXINT_METADATA_CONFLICT",
                        "Missing runtime metadata");
            }
            return rows.next().getObject().stringValue();
        }
    }
}
