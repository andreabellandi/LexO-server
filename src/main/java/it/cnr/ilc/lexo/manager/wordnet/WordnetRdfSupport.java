package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.metadata.MetadataPolicy;
import it.cnr.ilc.lexo.manager.metadata.RdfMetadataCodec;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.NativeCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.NativePatch;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Common transactional RDF, audit, annotation and metadata operations. */
final class WordnetRdfSupport {

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final RdfMetadataCodec codec = new RdfMetadataCodec();

    IRI mint(RepositoryConnection connection) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            IRI candidate = vf.createIRI(LexiconCrudSupport.newResourceUri(
                    new Timestamp(System.currentTimeMillis())));
            if (!connection.hasStatement(candidate, null, null, false)
                    && !connection.hasStatement(null, null, candidate, false)) {
                return candidate;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while minting RDF IRI", ex);
            }
        }
        throw new IllegalStateException("Unable to mint a unique RDF IRI");
    }

    String timestamp() {
        return LexiconCrudSupport.operationTimestamp();
    }

    void addAudit(RepositoryConnection connection, IRI resource, String author,
            String timestamp, Resource graph) {
        connection.add(resource, DCTERMS.CREATOR,
                vf.createLiteral(LexiconCrudSupport.author(author)), graph);
        connection.add(resource, DCTERMS.CREATED,
                vf.createLiteral(timestamp, XSD.DATETIME), graph);
        connection.add(resource, DCTERMS.MODIFIED,
                vf.createLiteral(timestamp, XSD.DATETIME), graph);
    }

    void touch(RepositoryConnection connection, IRI resource, String timestamp,
            Resource graph) {
        connection.remove(resource, DCTERMS.MODIFIED, null, graph);
        connection.add(resource, DCTERMS.MODIFIED,
                vf.createLiteral(timestamp, XSD.DATETIME), graph);
    }

    void checkExpectedModified(RepositoryConnection connection, IRI resource,
            NativePatch request, Resource graph) {
        if (request == null || !request.hasExpectedModified()) {
            return;
        }
        String actual = first(connection, resource, DCTERMS.MODIFIED, graph);
        if (request.getExpectedModified() == null
                || !request.getExpectedModified().equals(actual)) {
            throw new WordnetException(409, "MODIFIED_MISMATCH",
                    "Resource was modified after the supplied precondition");
        }
    }

    void addNativeAndMetadata(RepositoryConnection connection, IRI resource,
            NativeCreate request, Resource graph) {
        if (request == null) {
            return;
        }
        validateNative(request.notes, request.wordnetStatus,
                request.confidenceScore);
        if (request.notes != null) {
            for (String note : distinctStrings(request.notes, "notes")) {
                connection.add(resource, WordnetVocabulary.NOTE,
                        vf.createLiteral(note), graph);
            }
        }
        if (notBlank(request.wordnetStatus)) {
            connection.add(resource, WordnetVocabulary.WORDNET_STATUS,
                    vf.createLiteral(request.wordnetStatus.trim()), graph);
        }
        if (request.confidenceScore != null) {
            connection.add(resource, WordnetVocabulary.CONFIDENCE_SCORE,
                    vf.createLiteral(request.confidenceScore.toPlainString(),
                            XSD.DECIMAL), graph);
        }
        addMetadata(connection, resource, request.metadata, graph);
    }

    boolean patchNativeAndMetadata(RepositoryConnection connection, IRI resource,
            NativePatch request, Resource graph) {
        if (request == null) {
            return false;
        }
        boolean changed = false;
        if (request.hasNotes()) {
            if (request.getNotes() == null) {
                throw invalid("INVALID_PATCH", "notes cannot be null");
            }
            List<String> notes = distinctStrings(request.getNotes(), "notes");
            connection.remove(resource, WordnetVocabulary.NOTE, null, graph);
            for (String note : notes) {
                connection.add(resource, WordnetVocabulary.NOTE,
                        vf.createLiteral(note), graph);
            }
            changed = true;
        }
        if (request.hasWordnetStatus()) {
            connection.remove(resource, WordnetVocabulary.WORDNET_STATUS,
                    null, graph);
            if (request.getWordnetStatus() != null) {
                String value = required(request.getWordnetStatus(),
                        "INVALID_PATCH", "wordnetStatus cannot be blank");
                connection.add(resource, WordnetVocabulary.WORDNET_STATUS,
                        vf.createLiteral(value), graph);
            }
            changed = true;
        }
        if (request.hasConfidenceScore()) {
            connection.remove(resource, WordnetVocabulary.CONFIDENCE_SCORE,
                    null, graph);
            if (request.getConfidenceScore() != null) {
                validateScore(request.getConfidenceScore());
                connection.add(resource, WordnetVocabulary.CONFIDENCE_SCORE,
                        vf.createLiteral(request.getConfidenceScore().toPlainString(),
                                XSD.DECIMAL), graph);
            }
            changed = true;
        }
        if (request.metadata != null) {
            patchMetadata(connection, resource, request.metadata, graph);
            changed = true;
        }
        return changed;
    }

    Map<String, Object> baseResponse(RepositoryConnection connection,
            String wordnetId, String resourceType, IRI resource, Resource graph) {
        return baseResponse(connection, wordnetId, resourceType, resource, graph,
                true);
    }

    Map<String, Object> baseResponse(RepositoryConnection connection,
            String wordnetId, String resourceType, IRI resource, Resource graph,
            boolean includeNativeAnnotations) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", WordnetIdCodec.encode(resource.stringValue()));
        result.put("iri", resource.stringValue());
        result.put("resourceType", resourceType);
        result.put("wordnetId", wordnetId);
        result.put("creator", first(connection, resource, DCTERMS.CREATOR, graph));
        result.put("created", first(connection, resource, DCTERMS.CREATED, graph));
        result.put("modified", first(connection, resource, DCTERMS.MODIFIED, graph));
        if (includeNativeAnnotations) {
            result.put("notes", strings(connection, resource,
                    WordnetVocabulary.NOTE, graph));
            result.put("wordnetStatus", first(connection, resource,
                    WordnetVocabulary.WORDNET_STATUS, graph));
            String score = first(connection, resource,
                    WordnetVocabulary.CONFIDENCE_SCORE, graph);
            result.put("confidenceScore", score == null
                    ? null : new BigDecimal(score));
        }
        return result;
    }

    List<RdfMetadataProperty> readMetadata(RepositoryConnection connection,
            IRI resource, Resource graph) {
        Map<IRI, List<Value>> values = new LinkedHashMap<IRI, List<Value>>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                resource, null, null, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                if (MetadataPolicy.isProtected(statement.getPredicate().stringValue())) {
                    continue;
                }
                List<Value> propertyValues = values.get(statement.getPredicate());
                if (propertyValues == null) {
                    propertyValues = new ArrayList<Value>();
                    values.put(statement.getPredicate(), propertyValues);
                }
                if (!propertyValues.contains(statement.getObject())) {
                    propertyValues.add(statement.getObject());
                }
            }
        }
        return codec.encode(values);
    }

    void addMetadata(RepositoryConnection connection, IRI resource,
            List<RdfMetadataProperty> properties, Resource graph) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        Map<IRI, List<Value>> decoded = codec.decodeProperties(properties,
                false, "metadata");
        for (Map.Entry<IRI, List<Value>> property : decoded.entrySet()) {
            for (Value value : property.getValue()) {
                connection.add(resource, property.getKey(), value, graph);
            }
        }
    }

    void patchMetadata(RepositoryConnection connection, IRI resource,
            MetadataPatchRequest request, Resource graph) {
        LinkedHashMap<IRI, List<Value>> replacements = decodeOptional(
                request.properties, true, "metadata.properties");
        LinkedHashMap<IRI, List<Value>> additions = decodeOptional(
                request.addValues, false, "metadata.addValues");
        LinkedHashMap<IRI, List<Value>> removals = decodeOptional(
                request.removeValues, false, "metadata.removeValues");
        if (replacements.isEmpty() && additions.isEmpty() && removals.isEmpty()) {
            throw invalid("INVALID_PATCH", "metadata contains no operation");
        }
        for (IRI property : replacements.keySet()) {
            if (additions.containsKey(property) || removals.containsKey(property)) {
                throw invalid("CONFLICTING_METADATA_OPERATIONS",
                        property.stringValue());
            }
        }
        for (Map.Entry<IRI, List<Value>> property : additions.entrySet()) {
            List<Value> removed = removals.get(property.getKey());
            if (removed != null) {
                for (Value value : property.getValue()) {
                    if (removed.contains(value)) {
                        throw invalid("CONFLICTING_METADATA_OPERATIONS",
                                property.getKey().stringValue());
                    }
                }
            }
        }
        for (Map.Entry<IRI, List<Value>> property : replacements.entrySet()) {
            connection.remove(resource, property.getKey(), null, graph);
            for (Value value : property.getValue()) {
                connection.add(resource, property.getKey(), value, graph);
            }
        }
        for (Map.Entry<IRI, List<Value>> property : removals.entrySet()) {
            for (Value value : property.getValue()) {
                connection.remove(resource, property.getKey(), value, graph);
            }
        }
        for (Map.Entry<IRI, List<Value>> property : additions.entrySet()) {
            for (Value value : property.getValue()) {
                connection.add(resource, property.getKey(), value, graph);
            }
        }
    }

    IRI resource(RepositoryConnection connection, String id, IRI expectedType,
            Resource graph, String code) {
        IRI resource = vf.createIRI(WordnetIdCodec.decode(id));
        if (!connection.hasStatement(resource, RDF.TYPE, expectedType, false, graph)) {
            throw new WordnetException(404, code,
                    "Resource does not exist in the selected WordNet graph");
        }
        return resource;
    }

    IRI expandedIri(String value, String code, String field) {
        return absoluteIri(LexiconCrudSupport.expandLexicalIri(
                required(value, code, field + " is required")), code, field);
    }

    IRI absoluteIri(String value, String code, String field) {
        try {
            URI parsed = new URI(value);
            if (!parsed.isAbsolute() || parsed.getScheme() == null) {
                throw invalid(code, field + " must be an absolute IRI");
            }
            return vf.createIRI(value);
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw invalid(code, field + " must be an absolute IRI");
        }
    }

    String first(RepositoryConnection connection, Resource resource, IRI predicate,
            Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                resource, predicate, null, false, graph)) {
            return statements.hasNext()
                    ? statements.next().getObject().stringValue() : null;
        }
    }

    Literal firstLiteral(RepositoryConnection connection, Resource resource,
            IRI predicate, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                resource, predicate, null, false, graph)) {
            if (!statements.hasNext()) {
                return null;
            }
            Value value = statements.next().getObject();
            return value instanceof Literal ? (Literal) value : null;
        }
    }

    IRI firstIri(RepositoryConnection connection, Resource resource, IRI predicate,
            Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                resource, predicate, null, false, graph)) {
            if (statements.hasNext()) {
                Value value = statements.next().getObject();
                return value instanceof IRI ? (IRI) value : null;
            }
            return null;
        }
    }

    List<IRI> iris(RepositoryConnection connection, Resource subject, IRI predicate,
            Resource graph) {
        List<IRI> result = new ArrayList<IRI>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof IRI) {
                    result.add((IRI) value);
                }
            }
        }
        Collections.sort(result, (left, right) -> left.stringValue()
                .compareTo(right.stringValue()));
        return result;
    }

    void requireExclusiveIncoming(RepositoryConnection connection, IRI resource,
            IRI owner, Resource graph, IRI... ownershipPredicates) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, null, resource, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                boolean ownership = owner.equals(statement.getSubject());
                if (ownership) {
                    ownership = false;
                    for (IRI predicate : ownershipPredicates) {
                        if (predicate.equals(statement.getPredicate())) {
                            ownership = true;
                            break;
                        }
                    }
                }
                if (!ownership) {
                    throw new WordnetException(409,
                            "GRAPH_INTEGRITY_CONFLICT",
                            "Owned resource is referenced outside its owner");
                }
            }
        }
    }

    String required(String value, String code, String message) {
        if (!notBlank(value)) {
            throw invalid(code, message);
        }
        return value.trim();
    }

    boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    WordnetException invalid(String code, String message) {
        return new WordnetException(400, code, message);
    }

    private List<String> strings(RepositoryConnection connection, Resource resource,
            IRI predicate, Resource graph) {
        List<String> values = new ArrayList<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                resource, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                values.add(statements.next().getObject().stringValue());
            }
        }
        Collections.sort(values);
        return values;
    }

    private void validateNative(List<String> notes, String status,
            BigDecimal score) {
        if (notes != null) {
            distinctStrings(notes, "notes");
        }
        if (status != null) {
            required(status, "INVALID_PATCH", "wordnetStatus cannot be blank");
        }
        if (score != null) {
            validateScore(score);
        }
    }

    private void validateScore(BigDecimal score) {
        if (score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(BigDecimal.ONE) > 0) {
            throw invalid("INVALID_PATCH", "confidenceScore must be between 0 and 1");
        }
    }

    private List<String> distinctStrings(List<String> values, String field) {
        Set<String> distinct = new LinkedHashSet<String>();
        for (String value : values) {
            distinct.add(required(value, "INVALID_PATCH",
                    field + " values cannot be blank"));
        }
        return new ArrayList<String>(distinct);
    }

    private LinkedHashMap<IRI, List<Value>> decodeOptional(
            List<RdfMetadataProperty> properties, boolean allowEmpty,
            String field) {
        return properties == null || properties.isEmpty()
                ? new LinkedHashMap<IRI, List<Value>>()
                : codec.decodeProperties(properties, allowEmpty, field);
    }
}
