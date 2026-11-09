package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.RelationCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.RelationPatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Named VarTrans relation CRUD for Synset and Sense scopes. */
public final class WordnetRelationManager extends AbstractWordnetManager {

    private final WordnetSynsetManager synsetManager;
    private final WordnetSenseManager senseManager;

    public WordnetRelationManager() {
        this(null);
    }

    public WordnetRelationManager(Repository repository) {
        super(repository);
        this.synsetManager = new WordnetSynsetManager(repository);
        this.senseManager = new WordnetSenseManager(repository);
    }

    public Map<String, Object> create(String wordnetId, RelationCreate request,
            String author) {
        if (request == null) throw rdf.invalid("MISSING_REQUIRED_FIELD", "request is required");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            RelationValues values = validate(connection, request.scope,
                    request.sourceId, request.targetId, request.category, wordnet);
            connection.begin();
            requireUnique(connection, null, values, wordnet.graph);
            IRI relation = rdf.mint(connection);
            String timestamp = rdf.timestamp();
            connection.add(relation, RDF.TYPE, values.relationType, wordnet.graph);
            connection.add(relation, WordnetVocabulary.SOURCE, values.source, wordnet.graph);
            connection.add(relation, WordnetVocabulary.TARGET, values.target, wordnet.graph);
            connection.add(relation, WordnetVocabulary.RELATION_CATEGORY,
                    values.category, wordnet.graph);
            rdf.addAudit(connection, relation, author, timestamp, wordnet.graph);
            rdf.addNativeAndMetadata(connection, relation, request, wordnet.graph);
            rdf.touch(connection, values.source, timestamp, wordnet.graph);
            rdf.touch(connection, values.target, timestamp, wordnet.graph);
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
            return response(connection, wordnet, relation, null);
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> get(String wordnetId, String id, String sections) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI relation = relation(connection, id, wordnet.graph);
            return response(connection, wordnet, relation, sections);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> list(String wordnetId, String scope,
            String sourceId, String targetId, String category,
            int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0)
            throw rdf.invalid("INVALID_PAGINATION", "Invalid limit or offset");
        String normalizedScope = scope == null ? null : requireScope(scope);
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI source = sourceId == null ? null : endpoint(connection,
                    sourceId, normalizedScope, wordnet.graph);
            IRI target = targetId == null ? null : endpoint(connection,
                    targetId, normalizedScope, wordnet.graph);
            if (source != null) {
                validateFilterEndpoint(connection, normalizedScope, source,
                        wordnet);
            }
            if (target != null) {
                validateFilterEndpoint(connection, normalizedScope, target,
                        wordnet);
            }
            IRI categoryIri = category == null ? null : rdf.expandedIri(category,
                    "INVALID_RELATION_CATEGORY", "category");
            if (categoryIri != null && !validCategory(connection, categoryIri,
                    normalizedScope)) {
                throw new WordnetException(422, "INVALID_RELATION_CATEGORY",
                        "Relation category is not valid for the selected scope");
            }
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            collect(connection, wordnet, WordnetVocabulary.CONCEPTUAL_RELATION,
                    normalizedScope, source, target, categoryIri, matches);
            collect(connection, wordnet, WordnetVocabulary.SENSE_RELATION,
                    normalizedScope, source, target, categoryIri, matches);
            matches.sort((left, right) -> ((String) left.get("iri"))
                    .compareTo((String) right.get("iri")));
            int from = Math.min(offset, matches.size());
            int to = Math.min(from + limit, matches.size());
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("items", new ArrayList<Map<String, Object>>(matches.subList(from, to)));
            result.put("total", Integer.valueOf(matches.size()));
            result.put("limit", Integer.valueOf(limit));
            result.put("offset", Integer.valueOf(offset));
            return result;
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> patch(String wordnetId, String id,
            RelationPatch request, String author) {
        if (request == null || request.hasScope())
            throw rdf.invalid("INVALID_PATCH", "Relation scope is immutable");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI relation = relation(connection, id, wordnet.graph);
            String scope = scope(connection, relation, wordnet.graph);
            IRI oldSource = requiredIri(connection, relation,
                    WordnetVocabulary.SOURCE, wordnet.graph);
            IRI oldTarget = requiredIri(connection, relation,
                    WordnetVocabulary.TARGET, wordnet.graph);
            IRI oldCategory = requiredIri(connection, relation,
                    WordnetVocabulary.RELATION_CATEGORY, wordnet.graph);
            connection.begin();
            rdf.checkExpectedModified(connection, relation, request, wordnet.graph);
            RelationValues values = validate(connection, scope,
                    request.hasSourceId() ? request.getSourceId()
                            : WordnetIdCodec.encode(oldSource.stringValue()),
                    request.hasTargetId() ? request.getTargetId()
                            : WordnetIdCodec.encode(oldTarget.stringValue()),
                    request.hasCategory() ? request.getCategory()
                            : oldCategory.stringValue(), wordnet);
            requireUnique(connection, relation, values, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, relation,
                    request, wordnet.graph);
            if (request.hasSourceId()) {
                connection.remove(relation, WordnetVocabulary.SOURCE, null, wordnet.graph);
                connection.add(relation, WordnetVocabulary.SOURCE, values.source, wordnet.graph);
                changed = true;
            }
            if (request.hasTargetId()) {
                connection.remove(relation, WordnetVocabulary.TARGET, null, wordnet.graph);
                connection.add(relation, WordnetVocabulary.TARGET, values.target, wordnet.graph);
                changed = true;
            }
            if (request.hasCategory()) {
                connection.remove(relation, WordnetVocabulary.RELATION_CATEGORY,
                        null, wordnet.graph);
                connection.add(relation, WordnetVocabulary.RELATION_CATEGORY,
                        values.category, wordnet.graph);
                changed = true;
            }
            if (!changed) throw rdf.invalid("INVALID_PATCH", "No effective change supplied");
            String timestamp = rdf.timestamp();
            rdf.touch(connection, relation, timestamp, wordnet.graph);
            rdf.touch(connection, oldSource, timestamp, wordnet.graph);
            rdf.touch(connection, oldTarget, timestamp, wordnet.graph);
            rdf.touch(connection, values.source, timestamp, wordnet.graph);
            rdf.touch(connection, values.target, timestamp, wordnet.graph);
            connection.commit();
            return response(connection, wordnet, relation, null);
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    public void delete(String wordnetId, String id, String expectedModified) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI relation = relation(connection, id, wordnet.graph);
            IRI source = requiredIri(connection, relation, WordnetVocabulary.SOURCE, wordnet.graph);
            IRI target = requiredIri(connection, relation, WordnetVocabulary.TARGET, wordnet.graph);
            connection.begin();
            if (expectedModified != null && !expectedModified.equals(rdf.first(connection,
                    relation, org.eclipse.rdf4j.model.vocabulary.DCTERMS.MODIFIED,
                    wordnet.graph))) throw new WordnetException(409,
                            "MODIFIED_MISMATCH", "Relation was modified after the precondition");
            connection.remove(relation, null, null, wordnet.graph);
            String timestamp = rdf.timestamp();
            rdf.touch(connection, source, timestamp, wordnet.graph);
            rdf.touch(connection, target, timestamp, wordnet.graph);
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    Map<String, Object> response(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI relation,
            String sections) {
        Set<String> selected = WordnetSectionParser.parse(sections, "provenance");
        boolean core = selected.contains("core");
        Map<String, Object> result = rdf.baseResponse(connection, wordnet.wordnetId,
                "relation", relation, wordnet.graph, core);
        String scope = scope(connection, relation, wordnet.graph);
        IRI source = requiredIri(connection, relation,
                WordnetVocabulary.SOURCE, wordnet.graph);
        IRI target = requiredIri(connection, relation,
                WordnetVocabulary.TARGET, wordnet.graph);
        IRI category = requiredIri(connection, relation,
                WordnetVocabulary.RELATION_CATEGORY, wordnet.graph);
        validateStoredRelation(connection, scope, source, target, category,
                wordnet);
        if (core) {
            result.put("scope", scope);
            result.put("sourceId", WordnetIdCodec.encode(
                    source.stringValue()));
            result.put("targetId", WordnetIdCodec.encode(
                    target.stringValue()));
            result.put("category", category.stringValue());
        }
        if (selected.contains("metadata")) result.put("metadata",
                rdf.readMetadata(connection, relation, wordnet.graph));
        if (selected.contains("provenance")) result.put("provenance",
                rdf.readMetadata(connection, relation, wordnet.graph));
        return result;
    }

    private RelationValues validate(RepositoryConnection connection, String scope,
            String sourceId, String targetId, String category,
            WordnetGraphResolver.ResolvedWordnet wordnet) {
        Resource graph = wordnet.graph;
        String normalized = requireScope(scope);
        IRI expectedEndpoint = "synset".equals(normalized)
                ? WordnetVocabulary.SYNSET : WordnetVocabulary.SENSE;
        IRI relationType = "synset".equals(normalized)
                ? WordnetVocabulary.CONCEPTUAL_RELATION : WordnetVocabulary.SENSE_RELATION;
        IRI categoryType = "synset".equals(normalized)
                ? WordnetVocabulary.SYNSET_REL_TYPE : WordnetVocabulary.SENSE_REL_TYPE;
        IRI source = rdf.resource(connection, sourceId, expectedEndpoint, graph,
                "synset".equals(normalized) ? "SYNSET_NOT_FOUND" : "SENSE_NOT_FOUND");
        IRI target = rdf.resource(connection, targetId, expectedEndpoint, graph,
                "synset".equals(normalized) ? "SYNSET_NOT_FOUND" : "SENSE_NOT_FOUND");
        validateEndpointIntegrity(connection, normalized, source, wordnet);
        validateEndpointIntegrity(connection, normalized, target, wordnet);
        IRI categoryIri = rdf.expandedIri(category, "INVALID_RELATION_CATEGORY", "category");
        if (!connection.hasStatement(categoryIri, RDF.TYPE, categoryType, false,
                resolver.schemaGraph())) throw new WordnetException(422,
                        "INVALID_RELATION_CATEGORY",
                        "Relation category is not valid for the selected scope");
        return new RelationValues(normalized, relationType, source, target, categoryIri);
    }

    private IRI endpoint(RepositoryConnection connection, String id, String scope,
            Resource graph) {
        if (scope == null) {
            IRI endpoint = SimpleEndpoint.decode(id);
            boolean synset = connection.hasStatement(endpoint, RDF.TYPE,
                    WordnetVocabulary.SYNSET, false, graph);
            boolean sense = connection.hasStatement(endpoint, RDF.TYPE,
                    WordnetVocabulary.SENSE, false, graph);
            if (synset == sense) {
                throw new WordnetException(422, "INVALID_REFERENCED_ENTITY",
                        "Relation endpoint must identify one entity in this WordNet");
            }
            return endpoint;
        }
        return rdf.resource(connection, id, "synset".equals(scope)
                ? WordnetVocabulary.SYNSET : WordnetVocabulary.SENSE, graph,
                "INVALID_REFERENCED_ENTITY");
    }

    private boolean validCategory(RepositoryConnection connection, IRI category,
            String scope) {
        if ("synset".equals(scope)) {
            return connection.hasStatement(category, RDF.TYPE,
                    WordnetVocabulary.SYNSET_REL_TYPE, false,
                    resolver.schemaGraph());
        }
        if ("sense".equals(scope)) {
            return connection.hasStatement(category, RDF.TYPE,
                    WordnetVocabulary.SENSE_REL_TYPE, false,
                    resolver.schemaGraph());
        }
        return connection.hasStatement(category, RDF.TYPE,
                WordnetVocabulary.SYNSET_REL_TYPE, false,
                resolver.schemaGraph())
                || connection.hasStatement(category, RDF.TYPE,
                        WordnetVocabulary.SENSE_REL_TYPE, false,
                        resolver.schemaGraph());
    }

    private void validateStoredRelation(RepositoryConnection connection,
            String scope, IRI source, IRI target, IRI category,
            WordnetGraphResolver.ResolvedWordnet wordnet) {
        Resource graph = wordnet.graph;
        IRI endpointType = "synset".equals(scope)
                ? WordnetVocabulary.SYNSET : WordnetVocabulary.SENSE;
        IRI categoryType = "synset".equals(scope)
                ? WordnetVocabulary.SYNSET_REL_TYPE
                : WordnetVocabulary.SENSE_REL_TYPE;
        if (!connection.hasStatement(source, RDF.TYPE, endpointType, false, graph)
                || !connection.hasStatement(target, RDF.TYPE, endpointType,
                        false, graph)
                || !connection.hasStatement(category, RDF.TYPE, categoryType,
                        false, resolver.schemaGraph())) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Stored relation endpoints or category are invalid");
        }
        validateEndpointIntegrity(connection, scope, source, wordnet);
        validateEndpointIntegrity(connection, scope, target, wordnet);
    }

    private void validateEndpointIntegrity(RepositoryConnection connection,
            String scope, IRI endpoint,
            WordnetGraphResolver.ResolvedWordnet wordnet) {
        if ("synset".equals(scope)) {
            synsetManager.validateCore(connection, wordnet, endpoint);
        } else {
            senseManager.validateIntegrity(connection, endpoint, wordnet);
        }
    }

    private void validateFilterEndpoint(RepositoryConnection connection,
            String scope, IRI endpoint,
            WordnetGraphResolver.ResolvedWordnet wordnet) {
        if (scope != null) {
            validateEndpointIntegrity(connection, scope, endpoint, wordnet);
            return;
        }
        String detected = connection.hasStatement(endpoint, RDF.TYPE,
                WordnetVocabulary.SYNSET, false, wordnet.graph)
                ? "synset" : "sense";
        validateEndpointIntegrity(connection, detected, endpoint, wordnet);
    }

    private void requireUnique(RepositoryConnection connection, IRI excluded,
            RelationValues values, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(null,
                RDF.TYPE, values.relationType, false, graph)) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (!(subject instanceof IRI) || subject.equals(excluded)) continue;
                IRI relation = (IRI) subject;
                if (values.source.equals(rdf.firstIri(connection, relation,
                        WordnetVocabulary.SOURCE, graph))
                        && values.target.equals(rdf.firstIri(connection, relation,
                                WordnetVocabulary.TARGET, graph))
                        && values.category.equals(rdf.firstIri(connection, relation,
                                WordnetVocabulary.RELATION_CATEGORY, graph)))
                    throw new WordnetException(409, "RELATION_ALREADY_EXISTS",
                            "The explicit relation tuple already exists");
            }
        }
    }

    private IRI relation(RepositoryConnection connection, String id, Resource graph) {
        IRI relation = SimpleEndpoint.decode(id);
        boolean conceptual = connection.hasStatement(relation, RDF.TYPE,
                WordnetVocabulary.CONCEPTUAL_RELATION, false, graph);
        boolean sense = connection.hasStatement(relation, RDF.TYPE,
                WordnetVocabulary.SENSE_RELATION, false, graph);
        if (conceptual == sense) throw new WordnetException(conceptual ? 409 : 404,
                conceptual ? "GRAPH_INTEGRITY_CONFLICT" : "RELATION_NOT_FOUND",
                "Relation must have exactly one supported explicit type");
        return relation;
    }

    private String scope(RepositoryConnection connection, IRI relation, Resource graph) {
        return connection.hasStatement(relation, RDF.TYPE,
                WordnetVocabulary.CONCEPTUAL_RELATION, false, graph)
                ? "synset" : "sense";
    }

    private String requireScope(String scope) {
        String value = rdf.required(scope, "INVALID_RELATION_SCOPE", "scope is required");
        if (!"synset".equals(value) && !"sense".equals(value))
            throw new WordnetException(422, "INVALID_RELATION_SCOPE",
                    "scope must be synset or sense");
        return value;
    }

    private IRI requiredIri(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        List<IRI> values = rdf.iris(connection, subject, predicate, graph);
        if (values.size() != 1) throw new WordnetException(409,
                "GRAPH_INTEGRITY_CONFLICT", "Relation property cardinality is invalid");
        return values.get(0);
    }

    private void collect(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI type,
            String scopeFilter, IRI source, IRI target, IRI category,
            List<Map<String, Object>> result) {
        String currentScope = WordnetVocabulary.CONCEPTUAL_RELATION.equals(type)
                ? "synset" : "sense";
        if (scopeFilter != null && !scopeFilter.equals(currentScope)) return;
        try (RepositoryResult<Statement> statements = connection.getStatements(null,
                RDF.TYPE, type, false, wordnet.graph)) {
            while (statements.hasNext()) {
                IRI relation = (IRI) statements.next().getSubject();
                IRI itemSource = rdf.firstIri(connection, relation,
                        WordnetVocabulary.SOURCE, wordnet.graph);
                IRI itemTarget = rdf.firstIri(connection, relation,
                        WordnetVocabulary.TARGET, wordnet.graph);
                IRI itemCategory = rdf.firstIri(connection, relation,
                        WordnetVocabulary.RELATION_CATEGORY, wordnet.graph);
                if (source != null && !source.equals(itemSource)) continue;
                if (target != null && !target.equals(itemTarget)) continue;
                if (category != null && !category.equals(itemCategory)) continue;
                Map<String, Object> item = response(connection, wordnet, relation, "core");
                result.add(item);
            }
        }
    }

    private static final class SimpleEndpoint {
        static IRI decode(String id) {
            return org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance()
                    .createIRI(WordnetIdCodec.decode(id));
        }
    }

    private static final class RelationValues {
        final String scope;
        final IRI relationType;
        final IRI source;
        final IRI target;
        final IRI category;
        RelationValues(String scope, IRI relationType, IRI source, IRI target,
                IRI category) {
            this.scope = scope;
            this.relationType = relationType;
            this.source = source;
            this.target = target;
            this.category = category;
        }
    }
}
