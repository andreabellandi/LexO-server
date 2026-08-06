package it.cnr.ilc.lexo.manager.metadata;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.Manager;
import it.cnr.ilc.lexo.service.data.metadata.MetadataDeleteRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataResult;
import it.cnr.ilc.lexo.service.data.metadata.MetadataTarget;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
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

/** Common metadata CRUD with entity-specific graphs and one global predicate policy. */
public final class MetadataManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final RdfMetadataCodec codec = new RdfMetadataCodec();
    private final Repository repository;

    public MetadataManager() {
        this(null);
    }

    MetadataManager(Repository repository) {
        this.repository = repository;
    }

    public MetadataResult read(MetadataTarget target) {
        ResolvedTarget resolved = resolve(target);
        RepositoryConnection connection = acquire();
        try {
            validateResource(connection, resolved);
            return readResult(connection, resolved, null);
        } finally {
            release(connection);
        }
    }

    public MetadataResult patch(MetadataPatchRequest request) {
        ResolvedTarget resolved = resolve(request);
        LinkedHashMap<IRI, List<Value>> replacements = decodeOptional(
                request.properties, true, "properties");
        LinkedHashMap<IRI, List<Value>> additions = decodeOptional(
                request.addValues, false, "addValues");
        LinkedHashMap<IRI, List<Value>> removals = decodeOptional(
                request.removeValues, false, "removeValues");
        validatePatchOperations(replacements, additions, removals);
        return mutate(resolved, replacements, additions, removals);
    }

    public MetadataResult delete(MetadataDeleteRequest request) {
        ResolvedTarget resolved = resolve(request);
        if (request.properties == null || request.properties.isEmpty()) {
            throw invalid("MISSING_METADATA_PROPERTIES",
                    "at least one property to remove is required");
        }
        LinkedHashMap<IRI, List<Value>> properties =
                new LinkedHashMap<IRI, List<Value>>();
        for (int i = 0; i < request.properties.size(); i++) {
            IRI property = codec.iri(request.properties.get(i),
                    "properties[" + i + "]");
            if (MetadataPolicy.isProtected(property.stringValue())) {
                throw invalid("RESERVED_METADATA_PROPERTY",
                        property.stringValue() + " is managed by the service");
            }
            if (properties.put(property, Collections.<Value>emptyList()) != null) {
                throw invalid("DUPLICATE_METADATA_PROPERTY", property.stringValue());
            }
        }
        return mutate(resolved, properties,
                new LinkedHashMap<IRI, List<Value>>(),
                new LinkedHashMap<IRI, List<Value>>());
    }

    private LinkedHashMap<IRI, List<Value>> decodeOptional(
            List<RdfMetadataProperty> properties, boolean allowEmptyValues,
            String field) {
        if (properties == null || properties.isEmpty()) {
            return new LinkedHashMap<IRI, List<Value>>();
        }
        return codec.decodeProperties(properties, allowEmptyValues, field);
    }

    private void validatePatchOperations(
            LinkedHashMap<IRI, List<Value>> replacements,
            LinkedHashMap<IRI, List<Value>> additions,
            LinkedHashMap<IRI, List<Value>> removals) {
        if (replacements.isEmpty() && additions.isEmpty()
                && removals.isEmpty()) {
            throw invalid("MISSING_METADATA_PROPERTIES",
                    "at least one metadata operation is required");
        }
        for (IRI property : replacements.keySet()) {
            if (additions.containsKey(property) || removals.containsKey(property)) {
                throw invalid("CONFLICTING_METADATA_OPERATIONS",
                        property.stringValue()
                                + " cannot be replaced and changed incrementally");
            }
        }
        for (Map.Entry<IRI, List<Value>> addition : additions.entrySet()) {
            List<Value> removed = removals.get(addition.getKey());
            if (removed == null) {
                continue;
            }
            for (Value value : addition.getValue()) {
                if (removed.contains(value)) {
                    throw invalid("CONFLICTING_METADATA_VALUE",
                            addition.getKey().stringValue()
                                    + " contains a value in both addValues and removeValues");
                }
            }
        }
    }

    private MetadataResult mutate(ResolvedTarget resolved,
                                  LinkedHashMap<IRI, List<Value>> replacements,
                                  LinkedHashMap<IRI, List<Value>> additions,
                                  LinkedHashMap<IRI, List<Value>> removals) {
        RepositoryConnection connection = acquire();
        try {
            validateResource(connection, resolved);
            connection.begin();
            for (Map.Entry<IRI, List<Value>> property : replacements.entrySet()) {
                connection.remove(resolved.resource, property.getKey(), null,
                        resolved.graph);
                for (Value value : property.getValue()) {
                    connection.add(resolved.resource, property.getKey(), value,
                            resolved.graph);
                }
            }
            for (Map.Entry<IRI, List<Value>> property : removals.entrySet()) {
                for (Value value : property.getValue()) {
                    connection.remove(resolved.resource, property.getKey(),
                            value, resolved.graph);
                }
            }
            for (Map.Entry<IRI, List<Value>> property : additions.entrySet()) {
                for (Value value : property.getValue()) {
                    connection.add(resolved.resource, property.getKey(), value,
                            resolved.graph);
                }
            }
            String modified = LexiconCrudSupport.operationTimestamp();
            connection.remove(resolved.resource, DCTERMS.MODIFIED, null,
                    resolved.graph);
            connection.add(resolved.resource, DCTERMS.MODIFIED,
                    vf.createLiteral(modified, XSD.DATETIME), resolved.graph);
            connection.commit();
            return readResult(connection, resolved, modified);
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        } finally {
            release(connection);
        }
    }

    private MetadataResult readResult(RepositoryConnection connection,
                                      ResolvedTarget target, String modified) {
        Map<IRI, List<Value>> values = new LinkedHashMap<IRI, List<Value>>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                target.resource, null, null, false, target.graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                if (MetadataPolicy.isProtected(
                        statement.getPredicate().stringValue())) {
                    continue;
                }
                List<Value> propertyValues = values.get(statement.getPredicate());
                if (propertyValues == null) {
                    propertyValues = new ArrayList<Value>();
                    values.put(statement.getPredicate(), propertyValues);
                }
                propertyValues.add(statement.getObject());
            }
        }
        MetadataResult result = new MetadataResult();
        result.entityType = target.entityType;
        result.resource = target.resource.stringValue();
        result.modified = modified == null
                ? firstModified(connection, target) : modified;
        result.metadata = codec.encode(values);
        return result;
    }

    private String firstModified(RepositoryConnection connection,
                                 ResolvedTarget target) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                target.resource, DCTERMS.MODIFIED, null, false, target.graph)) {
            return statements.hasNext()
                    ? statements.next().getObject().stringValue() : null;
        }
    }

    private void validateResource(RepositoryConnection connection,
                                  ResolvedTarget target) {
        if (!connection.hasStatement(target.resource, null, null, false,
                target.graph)) {
            throw new MetadataException(404, "METADATA_RESOURCE_NOT_FOUND",
                    target.resource.stringValue());
        }
        boolean valid;
        if (target.allowSubclass) {
            valid = false;
            try (RepositoryResult<Statement> types = connection.getStatements(
                    target.resource, RDF.TYPE, null, false, target.graph)) {
                while (types.hasNext() && !valid) {
                    Value type = types.next().getObject();
                    valid = type instanceof IRI && LexiconCrudSupport.isSubclassOf(
                            connection, (IRI) type, target.expectedType,
                            target.graph, vf.createIRI(LexicalNamedGraphs.schemaGraphUri()));
                }
            }
        } else {
            valid = connection.hasStatement(target.resource, RDF.TYPE,
                    target.expectedType, false, target.graph);
        }
        if (!valid) {
            throw new MetadataException(422, "UNSUPPORTED_METADATA_RESOURCE_TYPE",
                    target.resource.stringValue());
        }
    }

    private ResolvedTarget resolve(MetadataTarget target) {
        if (target == null) {
            throw invalid("MISSING_METADATA_TARGET", "request is required");
        }
        String kind = required(target.entityType, "MISSING_METADATA_ENTITY_TYPE",
                "entityType is required").toLowerCase(Locale.ROOT);
        IRI resource = codec.iri(target.resource, "resource");
        if ("lexicalentry".equals(kind)) {
            return languageScopedTarget("lexicalEntry", "LexicalEntry", resource,
                    target.language);
        }
        if ("lexicalsense".equals(kind)) {
            return languageScopedTarget("lexicalSense", "LexicalSense", resource,
                    target.language);
        }
        if ("form".equals(kind)) {
            return languageScopedTarget("form", "Form", resource,
                    target.language);
        }
        if ("lexicalconcept".equals(kind)) {
            return new ResolvedTarget("lexicalConcept", resource,
                    vf.createIRI(LexiconCrudSupport.lexicalConceptGraphUri()),
                    vf.createIRI(ONTOLEX + "LexicalConcept"), false);
        }
        if ("attestation".equals(kind)) {
            String fileId = required(target.fileId, "MISSING_FILE_ID",
                    "fileId is required for attestation");
            return new ResolvedTarget("attestation", resource,
                    vf.createIRI(LexicalNamedGraphs.attestationGraphUri(fileId)),
                    vf.createIRI(FRAC + "Attestation"), false);
        }
        throw invalid("UNSUPPORTED_METADATA_ENTITY_TYPE",
                "entityType must be lexicalEntry, lexicalSense, form, lexicalConcept, or attestation");
    }

    private ResolvedTarget languageScopedTarget(String entityType,
                                                String ontologyType,
                                                IRI resource,
                                                String language) {
        Resource graph = vf.createIRI(LexiconCrudSupport.lexicalGraphUri(
                required(language, "MISSING_LANGUAGE",
                        "language is required for " + entityType)));
        return new ResolvedTarget(entityType, resource, graph,
                vf.createIRI(ONTOLEX + ontologyType), true);
    }

    private String required(String value, String code, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(code, message);
        }
        return value.trim();
    }

    private IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private RepositoryConnection acquire() {
        return repository == null ? GraphDbUtil.getConnection(RepositoryTarget.LEXICON)
                : repository.getConnection();
    }

    private void release(RepositoryConnection connection) {
        if (repository == null) {
            GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON, connection);
        } else {
            connection.close();
        }
    }

    public static final class MetadataException extends RuntimeException {
        public final int httpStatus;

        MetadataException(int httpStatus, String code, String message) {
            super(code + ": " + message);
            this.httpStatus = httpStatus;
        }
    }

    private static final class ResolvedTarget {
        final String entityType;
        final IRI resource;
        final Resource graph;
        final IRI expectedType;
        final boolean allowSubclass;

        ResolvedTarget(String entityType, IRI resource, Resource graph,
                       IRI expectedType, boolean allowSubclass) {
            this.entityType = entityType;
            this.resource = resource;
            this.graph = graph;
            this.expectedType = expectedType;
            this.allowSubclass = allowSubclass;
        }
    }
}
