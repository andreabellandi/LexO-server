package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.metadata.MetadataPolicy;
import it.cnr.ilc.lexo.manager.metadata.RdfMetadataCodec;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptDetailsResult;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptLinkedResource;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptRelation;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptTypedLabel;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Builds the complete read model of one lexical concept. */
public final class LexicalConceptDetailsManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final List<String> LABEL_PROPERTIES = Arrays.asList(
            RDFS.LABEL.stringValue(), SKOS + "prefLabel",
            SKOS + "altLabel", SKOS + "alternativeLabel",
            SKOS + "hiddenLabel");

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final RdfMetadataCodec metadataCodec = new RdfMetadataCodec();
    private final Repository repository;

    /** Runtime constructor used by {@link ManagerFactory}. */
    public LexicalConceptDetailsManager() {
        this(null);
    }

    LexicalConceptDetailsManager(Repository repository) {
        this.repository = repository;
    }

    /** Returns every supported property and relation of one lexical concept. */
    public LexicalConceptDetailsResult get(String lexicalConcept) {
        IRI concept = requireIri(lexicalConcept);
        RepositoryConnection connection = acquire();
        try {
            Resource conceptGraph = vf.createIRI(
                    LexiconCrudSupport.lexicalConceptGraphUri());
            Resource schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
            validateConcept(connection, concept, conceptGraph, schemaGraph);
            List<Resource> lexicalGraphs = lexicalGraphs(connection);

            LexicalConceptDetailsResult result =
                    new LexicalConceptDetailsResult();
            result.lexicalConcept = concept.stringValue();
            result.labels.addAll(labels(connection, concept, conceptGraph));
            result.definitions.addAll(literals(connection, concept,
                    vf.createIRI(SKOS + "definition"), conceptGraph));
            result.conceptSets.addAll(iriObjects(connection, concept,
                    vf.createIRI(SKOS + "inScheme"), conceptGraph));

            result.lexicalEntries.addAll(linkedEntries(connection, concept,
                    conceptGraph, schemaGraph, lexicalGraphs));
            result.lexicalSenses.addAll(linkedSenses(connection, concept,
                    conceptGraph, schemaGraph, lexicalGraphs));

            result.children.direct.addAll(relatedConcepts(connection, concept,
                    vf.createIRI(SKOS + "broader"),
                    vf.createIRI(SKOS + "narrower"), conceptGraph, schemaGraph));
            result.children.transitive.addAll(relatedConcepts(connection, concept,
                    vf.createIRI(SKOS + "broaderTransitive"),
                    vf.createIRI(SKOS + "narrowerTransitive"), conceptGraph,
                    schemaGraph));
            result.parents.direct.addAll(relatedConcepts(connection, concept,
                    vf.createIRI(SKOS + "narrower"),
                    vf.createIRI(SKOS + "broader"), conceptGraph, schemaGraph));
            result.parents.transitive.addAll(relatedConcepts(connection, concept,
                    vf.createIRI(SKOS + "narrowerTransitive"),
                    vf.createIRI(SKOS + "broaderTransitive"), conceptGraph,
                    schemaGraph));
            result.metadata = metadata(connection, concept, conceptGraph);
            return result;
        } finally {
            release(connection);
        }
    }

    private List<LexicalConceptLinkedResource> linkedEntries(
            RepositoryConnection connection, IRI concept, Resource conceptGraph,
            Resource schemaGraph, List<Resource> lexicalGraphs) {
        SortedSet<String> iris = linkedIris(connection, concept,
                vf.createIRI(ONTOLEX + "isEvokedBy"),
                vf.createIRI(ONTOLEX + "evokes"), conceptGraph, lexicalGraphs);
        List<LexicalConceptLinkedResource> result =
                new ArrayList<LexicalConceptLinkedResource>();
        IRI expected = vf.createIRI(ONTOLEX + "LexicalEntry");
        for (String value : iris) {
            IRI entry = vf.createIRI(value);
            Resource graph = resourceGraph(connection, entry, expected,
                    schemaGraph, lexicalGraphs);
            if (graph != null) {
                LexicalConceptLinkedResource item =
                        new LexicalConceptLinkedResource(value);
                item.labels.addAll(entryLabels(connection, entry, graph));
                result.add(item);
            }
        }
        return result;
    }

    private List<LexicalConceptLinkedResource> linkedSenses(
            RepositoryConnection connection, IRI concept, Resource conceptGraph,
            Resource schemaGraph, List<Resource> lexicalGraphs) {
        SortedSet<String> iris = linkedIris(connection, concept,
                vf.createIRI(ONTOLEX + "lexicalizedSense"),
                vf.createIRI(ONTOLEX + "isLexicalizedSenseOf"),
                conceptGraph, lexicalGraphs);
        List<LexicalConceptLinkedResource> result =
                new ArrayList<LexicalConceptLinkedResource>();
        IRI expected = vf.createIRI(ONTOLEX + "LexicalSense");
        for (String value : iris) {
            IRI sense = vf.createIRI(value);
            Resource graph = resourceGraph(connection, sense, expected,
                    schemaGraph, lexicalGraphs);
            if (graph != null) {
                LexicalConceptLinkedResource item =
                        new LexicalConceptLinkedResource(value);
                item.labels.addAll(senseLabels(connection, sense, graph,
                        schemaGraph));
                result.add(item);
            }
        }
        return result;
    }

    private SortedSet<String> linkedIris(
            RepositoryConnection connection, IRI concept, IRI outgoing,
            IRI incoming, Resource conceptGraph, List<Resource> lexicalGraphs) {
        SortedSet<String> result = new TreeSet<String>();
        List<Resource> graphs = new ArrayList<Resource>();
        graphs.add(conceptGraph);
        graphs.addAll(lexicalGraphs);
        Resource[] contexts = graphs.toArray(new Resource[graphs.size()]);
        try (RepositoryResult<Statement> statements = connection.getStatements(
                concept, outgoing, null, false, contexts)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof IRI) {
                    result.add(value.stringValue());
                }
            }
        }
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, incoming, concept, false, contexts)) {
            while (statements.hasNext()) {
                result.add(statements.next().getSubject().stringValue());
            }
        }
        return result;
    }

    private List<RdfMetadataValue> entryLabels(RepositoryConnection connection,
                                                IRI entry, Resource graph) {
        List<RdfMetadataValue> result = literals(connection, entry,
                RDFS.LABEL, graph);
        if (!result.isEmpty()) {
            return result;
        }
        result = formWrittenRepresentations(connection, entry,
                vf.createIRI(ONTOLEX + "canonicalForm"), graph);
        return result.isEmpty()
                ? formWrittenRepresentations(connection, entry,
                        vf.createIRI(ONTOLEX + "otherForm"), graph)
                : result;
    }

    private List<RdfMetadataValue> senseLabels(
            RepositoryConnection connection, IRI sense, Resource graph,
            Resource schemaGraph) {
        List<RdfMetadataValue> result = literals(connection, sense,
                vf.createIRI(SKOS + "definition"), graph);
        if (!result.isEmpty()) {
            return result;
        }
        result = literals(connection, sense, RDFS.LABEL, graph);
        if (!result.isEmpty()) {
            return result;
        }
        SortedSet<String> entries = new TreeSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                sense, vf.createIRI(ONTOLEX + "isSenseOf"), null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof IRI) {
                    entries.add(value.stringValue());
                }
            }
        }
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, vf.createIRI(ONTOLEX + "sense"), sense, false, graph)) {
            while (statements.hasNext()) {
                entries.add(statements.next().getSubject().stringValue());
            }
        }
        IRI expected = vf.createIRI(ONTOLEX + "LexicalEntry");
        for (String entryValue : entries) {
            IRI entry = vf.createIRI(entryValue);
            if (hasType(connection, entry, expected, graph, schemaGraph)) {
                result.addAll(entryLabels(connection, entry, graph));
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }
        return result;
    }

    private List<RdfMetadataValue> formWrittenRepresentations(
            RepositoryConnection connection, IRI entry, IRI relation,
            Resource graph) {
        Set<Value> values = new LinkedHashSet<Value>();
        try (RepositoryResult<Statement> forms = connection.getStatements(
                entry, relation, null, false, graph)) {
            while (forms.hasNext()) {
                Value form = forms.next().getObject();
                if (!(form instanceof Resource)) {
                    continue;
                }
                try (RepositoryResult<Statement> representations =
                        connection.getStatements((Resource) form,
                                vf.createIRI(ONTOLEX + "writtenRep"), null,
                                false, graph)) {
                    while (representations.hasNext()) {
                        Value value = representations.next().getObject();
                        if (value instanceof Literal) {
                            values.add(value);
                        }
                    }
                }
            }
        }
        return encodedLiterals(values);
    }

    private List<LexicalConceptRelation> relatedConcepts(
            RepositoryConnection connection, IRI concept, IRI incomingPredicate,
            IRI outgoingInverse, Resource graph, Resource schemaGraph) {
        List<LexicalConceptRelation> result =
                new ArrayList<LexicalConceptRelation>();
        IRI expected = vf.createIRI(ONTOLEX + "LexicalConcept");
        SortedSet<String> relatedValues = new TreeSet<String>();
        relatedValues.addAll(iriSubjects(connection, incomingPredicate, concept,
                graph));
        relatedValues.addAll(iriObjects(connection, concept, outgoingInverse,
                graph));
        for (String relatedValue : relatedValues) {
            IRI related = vf.createIRI(relatedValue);
            if (hasType(connection, related, expected, graph, schemaGraph)) {
                LexicalConceptRelation item =
                        new LexicalConceptRelation(relatedValue);
                item.labels.addAll(labels(connection, related, graph));
                result.add(item);
            }
        }
        return result;
    }

    private List<String> iriSubjects(RepositoryConnection connection,
                                     IRI predicate, IRI object, Resource graph) {
        SortedSet<String> result = new TreeSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, predicate, object, false, graph)) {
            while (statements.hasNext()) {
                result.add(statements.next().getSubject().stringValue());
            }
        }
        return new ArrayList<String>(result);
    }

    private List<LexicalConceptTypedLabel> labels(
            RepositoryConnection connection, IRI subject, Resource graph) {
        List<LexicalConceptTypedLabel> result =
                new ArrayList<LexicalConceptTypedLabel>();
        for (String property : LABEL_PROPERTIES) {
            IRI predicate = vf.createIRI(property);
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    subject, predicate, null, false, graph)) {
                while (statements.hasNext()) {
                    Value value = statements.next().getObject();
                    if (value instanceof Literal) {
                        Literal literal = (Literal) value;
                        result.add(new LexicalConceptTypedLabel(property,
                                literal.getLabel(),
                                literal.getLanguage().orElse(null),
                                datatype(literal)));
                    }
                }
            }
        }
        Collections.sort(result, Comparator.comparing(this::labelSortKey));
        return result;
    }

    private List<RdfMetadataValue> literals(
            RepositoryConnection connection, Resource subject, IRI predicate,
            Resource graph) {
        Set<Value> values = new LinkedHashSet<Value>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof Literal) {
                    values.add(value);
                }
            }
        }
        return encodedLiterals(values);
    }

    private List<RdfMetadataValue> encodedLiterals(Set<Value> values) {
        List<RdfMetadataValue> result = new ArrayList<RdfMetadataValue>();
        for (Value value : values) {
            RdfMetadataValue encoded = metadataCodec.encode(value);
            if (encoded != null) {
                result.add(encoded);
            }
        }
        Collections.sort(result, Comparator.comparing(this::valueSortKey));
        return result;
    }

    private List<it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty> metadata(
            RepositoryConnection connection, IRI concept, Resource graph) {
        Map<IRI, List<Value>> properties = new LinkedHashMap<IRI, List<Value>>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                concept, null, null, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                IRI predicate = statement.getPredicate();
                Value object = statement.getObject();
                if (MetadataPolicy.isProtected(predicate.stringValue())
                        || (!(object instanceof IRI) && !(object instanceof Literal))) {
                    continue;
                }
                List<Value> values = properties.get(predicate);
                if (values == null) {
                    values = new ArrayList<Value>();
                    properties.put(predicate, values);
                }
                if (!values.contains(object)) {
                    values.add(object);
                }
            }
        }
        return metadataCodec.encode(properties);
    }

    private List<String> iriObjects(RepositoryConnection connection,
                                    IRI subject, IRI predicate, Resource graph) {
        SortedSet<String> result = new TreeSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof IRI) {
                    result.add(value.stringValue());
                }
            }
        }
        return new ArrayList<String>(result);
    }

    private Resource resourceGraph(RepositoryConnection connection, IRI resource,
                                   IRI expected, Resource schemaGraph,
                                   List<Resource> lexicalGraphs) {
        for (Resource graph : lexicalGraphs) {
            if (hasType(connection, resource, expected, graph, schemaGraph)) {
                return graph;
            }
        }
        return null;
    }

    private boolean hasType(RepositoryConnection connection, IRI resource,
                            IRI expected, Resource graph, Resource schemaGraph) {
        try (RepositoryResult<Statement> types = connection.getStatements(
                resource, RDF.TYPE, null, false, graph)) {
            while (types.hasNext()) {
                Value type = types.next().getObject();
                if (type instanceof IRI && LexiconCrudSupport.isSubclassOf(
                        connection, (IRI) type, expected, graph, schemaGraph)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void validateConcept(RepositoryConnection connection, IRI concept,
                                 Resource graph, Resource schemaGraph) {
        if (hasType(connection, concept,
                vf.createIRI(ONTOLEX + "LexicalConcept"), graph, schemaGraph)) {
            return;
        }
        if (!connection.hasStatement(concept, null, null, false, graph)) {
            throw failure(404, "LEXICAL_CONCEPT_NOT_FOUND",
                    concept.stringValue());
        }
        throw failure(422, "INVALID_LEXICAL_CONCEPT_TYPE",
                concept.stringValue());
    }

    private List<Resource> lexicalGraphs(RepositoryConnection connection) {
        SortedSet<String> graphUris = new TreeSet<String>();
        try (RepositoryResult<Resource> contexts = connection.getContextIDs()) {
            while (contexts.hasNext()) {
                Resource graph = contexts.next();
                String graphUri = graph.stringValue();
                if (!graphUri.startsWith(LexiconCrudSupport.LEXICAL_GRAPH_BASE_URI)) {
                    continue;
                }
                String language = graphUri.substring(
                        LexiconCrudSupport.LEXICAL_GRAPH_BASE_URI.length());
                try {
                    if (graphUri.equals(LexiconCrudSupport.lexicalGraphUri(language))) {
                        graphUris.add(graphUri);
                    }
                } catch (IllegalArgumentException e) {
                    // Ignore contexts outside the validated language graph family.
                }
            }
        }
        List<Resource> result = new ArrayList<Resource>();
        for (String graphUri : graphUris) {
            result.add(vf.createIRI(graphUri));
        }
        return result;
    }

    private IRI requireIri(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "MISSING_LEXICAL_CONCEPT_IRI: lexicalConcept is required");
        }
        String normalized = value.trim();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw invalidIri();
            }
            return vf.createIRI(normalized);
        } catch (URISyntaxException e) {
            throw invalidIri();
        }
    }

    private IllegalArgumentException invalidIri() {
        return new IllegalArgumentException(
                "INVALID_LEXICAL_CONCEPT_IRI: lexicalConcept must be an absolute IRI");
    }

    private String datatype(Literal literal) {
        return literal.getLanguage().isPresent()
                || "http://www.w3.org/2001/XMLSchema#string".equals(
                        literal.getDatatype().stringValue())
                ? null : literal.getDatatype().stringValue();
    }

    private String labelSortKey(LexicalConceptTypedLabel value) {
        return value.property + "|" + nullSafe(value.language) + "|"
                + value.value + "|" + nullSafe(value.datatype);
    }

    private String valueSortKey(RdfMetadataValue value) {
        return nullSafe(value.language) + "|" + value.value + "|"
                + nullSafe(value.datatype);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private DetailsException failure(int status, String code, String message) {
        return new DetailsException(status, code + ": " + message);
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
    public static final class DetailsException extends RuntimeException {
        public final int httpStatus;

        DetailsException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }
}
