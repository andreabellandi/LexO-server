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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

/** Common metadata CRUD with entity-specific graph and protected-predicate policies. */
public final class MetadataManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String LIME = "http://www.w3.org/ns/lemon/lime#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";

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
        LinkedHashMap<IRI, List<Value>> properties = codec.decodeProperties(
                request.properties, resolved.reserved, true);
        return mutate(resolved, properties);
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
            if (resolved.reserved.contains(property.stringValue())) {
                throw invalid("RESERVED_METADATA_PROPERTY",
                        property.stringValue() + " is managed by the service");
            }
            if (properties.put(property, Collections.<Value>emptyList()) != null) {
                throw invalid("DUPLICATE_METADATA_PROPERTY", property.stringValue());
            }
        }
        return mutate(resolved, properties);
    }

    private MetadataResult mutate(ResolvedTarget resolved,
                                  LinkedHashMap<IRI, List<Value>> properties) {
        RepositoryConnection connection = acquire();
        try {
            validateResource(connection, resolved);
            connection.begin();
            for (Map.Entry<IRI, List<Value>> property : properties.entrySet()) {
                connection.remove(resolved.resource, property.getKey(), null,
                        resolved.graph);
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
                if (target.reserved.contains(statement.getPredicate().stringValue())) {
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
            Resource graph = vf.createIRI(LexiconCrudSupport.lexicalGraphUri(
                    required(target.language, "MISSING_LANGUAGE",
                            "language is required for lexicalEntry")));
            return new ResolvedTarget("lexicalEntry", resource, graph,
                    vf.createIRI(ONTOLEX + "LexicalEntry"), true,
                    lexicalEntryReservedProperties());
        }
        if ("lexicalconcept".equals(kind)) {
            return new ResolvedTarget("lexicalConcept", resource,
                    vf.createIRI(LexiconCrudSupport.lexicalConceptGraphUri()),
                    vf.createIRI(ONTOLEX + "LexicalConcept"), false,
                    lexicalConceptReservedProperties());
        }
        if ("attestation".equals(kind)) {
            String fileId = required(target.fileId, "MISSING_FILE_ID",
                    "fileId is required for attestation");
            return new ResolvedTarget("attestation", resource,
                    vf.createIRI(LexicalNamedGraphs.attestationGraphUri(fileId)),
                    vf.createIRI(FRAC + "Attestation"), false,
                    attestationReservedProperties());
        }
        throw invalid("UNSUPPORTED_METADATA_ENTITY_TYPE",
                "entityType must be lexicalEntry, lexicalConcept, or attestation");
    }

    private static Set<String> commonReserved() {
        return new HashSet<String>(Arrays.asList(RDF.TYPE.stringValue(),
                RDF.VALUE.stringValue(), DCTERMS.CREATOR.stringValue(),
                DCTERMS.CREATED.stringValue(), DCTERMS.MODIFIED.stringValue()));
    }

    public static Set<String> lexicalEntryReservedProperties() {
        Set<String> result = commonReserved();
        result.addAll(Arrays.asList(RDFS + "label", ONTOLEX + "otherForm",
                ONTOLEX + "canonicalForm", ONTOLEX + "sense",
                ONTOLEX + "denotes", ONTOLEX + "evokes", LEXO + "status",
                LIME + "entry"));
        return result;
    }

    public static Set<String> lexicalConceptReservedProperties() {
        Set<String> result = commonReserved();
        result.addAll(Arrays.asList(SKOS + "prefLabel", SKOS + "alternativeLabel",
                SKOS + "hiddenLabel", SKOS + "definition", SKOS + "broader",
                SKOS + "inScheme", ONTOLEX + "isLexicalizedSenseOf"));
        return result;
    }

    public static Set<String> attestationReservedProperties() {
        Set<String> result = commonReserved();
        result.addAll(Arrays.asList(FRAC + "locus", FRAC + "observedIn",
                FRAC + "gloss", FRAC + "frequency", FRAC + "attestation",
                DCTERMS.DESCRIPTION.stringValue()));
        return result;
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
        final Set<String> reserved;

        ResolvedTarget(String entityType, IRI resource, Resource graph,
                       IRI expectedType, boolean allowSubclass,
                       Set<String> reserved) {
            this.entityType = entityType;
            this.resource = resource;
            this.graph = graph;
            this.expectedType = expectedType;
            this.allowSubclass = allowSubclass;
            this.reserved = Collections.unmodifiableSet(reserved);
        }
    }
}
