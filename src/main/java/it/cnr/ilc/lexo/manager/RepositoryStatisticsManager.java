package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.Corpus;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.LexicalRepository;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.LexicalResource;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.RdfValue;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.RepositorySummary;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.Text;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.TextRepository;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;

/** Reads administration statistics directly from the two configured repositories. */
public class RepositoryStatisticsManager implements Manager {

    private static final String RDF_TYPE = RDF.TYPE.stringValue();
    private static final String DCTERMS_NS = DCTERMS.NAMESPACE;
    private static final String LIME_NS = "http://www.w3.org/ns/lemon/lime#";
    private static final String ONTOLEX_NS = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LEXICOG_NS = "http://www.w3.org/ns/lemon/lexicog#";
    private static final String FRAC_NS = "http://www.w3.org/ns/lemon/frac#";
    private static final String NIF_NS =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";

    private static final List<IRI> TEXT_METADATA_PREDICATES;
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    static {
        TEXT_METADATA_PREDICATES = Collections.unmodifiableList(Arrays.asList(
                DCTERMS.IDENTIFIER, DCTERMS.TITLE, DCTERMS.CREATOR,
                DCTERMS.CREATED, DCTERMS.LANGUAGE, DCTERMS.FORMAT,
                DCTERMS.IS_PART_OF));
    }

    public RepositoryStatistics getStatistics() throws ManagerException {
        String lexicalUrl = configured("GraphDb.url", "http://localhost:7200");
        String lexicalName = configured("GraphDb.repository", "LexOLexica");
        String textUrl = configured("TextGraphDb.url", lexicalUrl);
        String textName = configured("TextGraphDb.repository", "LexOTexts");

        RemoteRepositoryManager lexicalManager = null;
        RemoteRepositoryManager textManager = null;
        try {
            lexicalManager = remoteManager(lexicalUrl);
            Repository lexicalRepository = requiredRepository(lexicalManager, lexicalName);
            Repository textRepository;
            if (lexicalUrl.equals(textUrl)) {
                textRepository = requiredRepository(lexicalManager, textName);
            } else {
                textManager = remoteManager(textUrl);
                textRepository = requiredRepository(textManager, textName);
            }
            return getStatistics(lexicalRepository, lexicalName, textRepository, textName);
        } catch (RuntimeException e) {
            throw new ManagerException("Unable to read GraphDB repository statistics: "
                    + e.getMessage(), e);
        } finally {
            shutdown(textManager);
            shutdown(lexicalManager);
        }
    }

    /** Visible for tests and for callers that already own repository instances. */
    public RepositoryStatistics getStatistics(Repository lexicalRepository,
                                              String lexicalName,
                                              Repository textRepository,
                                              String textName) {
        RepositoryStatistics result = new RepositoryStatistics();
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection texts = textRepository.getConnection()) {
            result.lexicalRepository = lexicalStatistics(lexical, lexicalName);
            result.textRepository = textStatistics(texts, textName);
        }
        return result;
    }

    private LexicalRepository lexicalStatistics(RepositoryConnection connection,
                                                String name) {
        LexicalRepository statistics = new LexicalRepository();
        IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri());
        fillSummary(connection, name, statistics);
        statistics.lexicons = lexicalResources(connection, LIME_NS + "Lexicon",
                DCTERMS.DESCRIPTION, iri(LIME_NS + "language"), lexicalGraph);
        statistics.lexiconCount = statistics.lexicons.size();
        statistics.lexicalEntryCount = countInstances(connection,
                ONTOLEX_NS + "LexicalEntry", lexicalGraph);
        statistics.lexicalSenseCount = countInstances(connection,
                ONTOLEX_NS + "LexicalSense", lexicalGraph);
        statistics.dictionaries = lexicalResources(connection,
                LEXICOG_NS + "LexicographicResource", DCTERMS.DESCRIPTION,
                DCTERMS.LANGUAGE, lexicalGraph);
        statistics.dictionaryCount = statistics.dictionaries.size();
        statistics.dictionaryEntryCount = countInstances(connection,
                LEXICOG_NS + "Entry", lexicalGraph);
        statistics.attestationCount = countInstances(connection,
                FRAC_NS + "Attestation", attestationGraph);
        return statistics;
    }

    private TextRepository textStatistics(RepositoryConnection connection, String name) {
        TextRepository statistics = new TextRepository();
        fillSummary(connection, name, statistics);

        Map<String, Text> allTexts = new LinkedHashMap<String, Text>();
        for (Resource resource : instances(connection, NIF_NS + "Context")) {
            Text text = new Text();
            text.iri = resource.stringValue();
            text.metadata = metadata(connection, resource);
            allTexts.put(text.iri, text);
        }
        statistics.textCount = allTexts.size();

        Set<String> assignedTexts = new LinkedHashSet<String>();
        for (Resource resource : instances(connection, NIF_NS + "ContextCollection")) {
            Corpus corpus = new Corpus();
            corpus.iri = resource.stringValue();
            corpus.metadata = metadata(connection, resource);
            try (RepositoryResult<Statement> members = connection.getStatements(
                    resource, DCTERMS.HAS_PART, null, true)) {
                while (members.hasNext()) {
                    Value member = members.next().getObject();
                    Text text = allTexts.get(member.stringValue());
                    if (text != null) {
                        corpus.texts.add(text);
                        assignedTexts.add(text.iri);
                    }
                }
            }
            sortTexts(corpus.texts);
            corpus.textCount = corpus.texts.size();
            statistics.corpora.add(corpus);
        }
        Collections.sort(statistics.corpora, new Comparator<Corpus>() {
            @Override
            public int compare(Corpus left, Corpus right) {
                return left.iri.compareTo(right.iri);
            }
        });
        statistics.corpusCount = statistics.corpora.size();

        for (Text text : allTexts.values()) {
            if (!assignedTexts.contains(text.iri)) {
                statistics.unassignedTexts.add(text);
            }
        }
        sortTexts(statistics.unassignedTexts);
        statistics.unassignedTextCount = statistics.unassignedTexts.size();
        return statistics;
    }

    private void fillSummary(RepositoryConnection connection, String name,
                             RepositorySummary summary) {
        summary.name = name;
        summary.explicitStatements = connection.size();
        summary.totalStatements = count(connection,
                "SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }");
        summary.inferredStatements = Math.max(0,
                summary.totalStatements - summary.explicitStatements);
        if (summary.explicitStatements > 0) {
            summary.expansionRatio = BigDecimal.valueOf(summary.totalStatements)
                    .divide(BigDecimal.valueOf(summary.explicitStatements), 4,
                            RoundingMode.HALF_UP)
                    .doubleValue();
        }
    }

    private long countInstances(RepositoryConnection connection, String classIri) {
        return countInstances(connection, classIri, null);
    }

    private long countInstances(RepositoryConnection connection, String classIri,
                                IRI graph) {
        String pattern = "?resource <" + RDF_TYPE + "> <" + classIri + ">";
        if (graph != null) {
            pattern = "GRAPH <" + graph.stringValue() + "> { " + pattern + " }";
        }
        return count(connection, "SELECT (COUNT(DISTINCT ?resource) AS ?count) WHERE { "
                + pattern + " }");
    }

    private long count(RepositoryConnection connection, String sparql) {
        TupleQuery query = connection.prepareTupleQuery(QueryLanguage.SPARQL, sparql);
        query.setIncludeInferred(true);
        try (TupleQueryResult result = query.evaluate()) {
            if (!result.hasNext()) {
                return 0;
            }
            Value value = result.next().getValue("count");
            return value == null ? 0 : Long.parseLong(value.stringValue());
        }
    }

    private List<Resource> instances(RepositoryConnection connection, String classIri) {
        return instances(connection, classIri, null);
    }

    private List<Resource> instances(RepositoryConnection connection, String classIri,
                                     IRI graph) {
        List<Resource> resources = new ArrayList<Resource>();
        String pattern = "?resource <" + RDF_TYPE + "> <" + classIri + ">";
        if (graph != null) {
            pattern = "GRAPH <" + graph.stringValue() + "> { " + pattern + " }";
        }
        String queryString = "SELECT DISTINCT ?resource WHERE { " + pattern
                + " } ORDER BY ?resource";
        TupleQuery query = connection.prepareTupleQuery(QueryLanguage.SPARQL, queryString);
        query.setIncludeInferred(true);
        try (TupleQueryResult result = query.evaluate()) {
            while (result.hasNext()) {
                BindingSet row = result.next();
                Value value = row.getValue("resource");
                if (value instanceof Resource) {
                    resources.add((Resource) value);
                }
            }
        }
        return resources;
    }

    private List<LexicalResource> lexicalResources(RepositoryConnection connection,
                                                   String classIri,
                                                   IRI descriptionPredicate,
                                                   IRI languagePredicate,
                                                   IRI graph) {
        List<LexicalResource> resources = new ArrayList<LexicalResource>();
        for (Resource resource : instances(connection, classIri, graph)) {
            LexicalResource item = new LexicalResource();
            item.iri = resource.stringValue();
            item.descriptions = values(connection, resource, descriptionPredicate, graph);
            item.languages = values(connection, resource, languagePredicate, graph);
            resources.add(item);
        }
        return resources;
    }

    private Map<String, List<RdfValue>> metadata(RepositoryConnection connection,
                                                 Resource resource) {
        Map<String, List<RdfValue>> metadata =
                new LinkedHashMap<String, List<RdfValue>>();
        for (IRI predicate : TEXT_METADATA_PREDICATES) {
            List<RdfValue> values = values(connection, resource, predicate);
            if (!values.isEmpty()) {
                metadata.put(predicate.stringValue().substring(DCTERMS_NS.length()), values);
            }
        }
        return metadata;
    }

    private List<RdfValue> values(RepositoryConnection connection, Resource subject,
                                  IRI predicate) {
        return values(connection, subject, predicate, null);
    }

    private List<RdfValue> values(RepositoryConnection connection, Resource subject,
                                  IRI predicate, IRI graph) {
        List<RdfValue> values = new ArrayList<RdfValue>();
        Set<String> seen = new LinkedHashSet<String>();
        RepositoryResult<Statement> selected = graph == null
                ? connection.getStatements(subject, predicate, null, true)
                : connection.getStatements(subject, predicate, null, true, graph);
        try (RepositoryResult<Statement> statements = selected) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                RdfValue item = rdfValue(value);
                String key = item.kind + "\u0000" + item.value + "\u0000"
                        + safe(item.language) + "\u0000" + safe(item.datatype);
                if (seen.add(key)) {
                    values.add(item);
                }
            }
        }
        Collections.sort(values, new Comparator<RdfValue>() {
            @Override
            public int compare(RdfValue left, RdfValue right) {
                return left.value.compareTo(right.value);
            }
        });
        return values;
    }

    private RdfValue rdfValue(Value value) {
        RdfValue result = new RdfValue();
        result.value = value.stringValue();
        if (value instanceof IRI) {
            result.kind = "IRI";
        } else if (value instanceof Literal) {
            Literal literal = (Literal) value;
            result.kind = "LITERAL";
            if (literal.getLanguage().isPresent()) {
                result.language = literal.getLanguage().get();
            }
            if (literal.getDatatype() != null) {
                result.datatype = literal.getDatatype().stringValue();
            }
        } else {
            result.kind = "BNODE";
        }
        return result;
    }

    private static void sortTexts(List<Text> texts) {
        Collections.sort(texts, new Comparator<Text>() {
            @Override
            public int compare(Text left, Text right) {
                return left.iri.compareTo(right.iri);
            }
        });
    }

    private static RemoteRepositoryManager remoteManager(String url) {
        RemoteRepositoryManager manager = new RemoteRepositoryManager(url);
        manager.init();
        return manager;
    }

    private static Repository requiredRepository(RemoteRepositoryManager manager,
                                                 String name) {
        Repository repository = manager.getRepository(name);
        if (repository == null) {
            throw new IllegalStateException("GraphDB repository does not exist: " + name);
        }
        return repository;
    }

    private static void shutdown(RemoteRepositoryManager manager) {
        if (manager != null) {
            manager.shutDown();
        }
    }

    private static IRI iri(String value) {
        return VF.createIRI(value);
    }

    private static String configured(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        if (value == null || value.trim().isEmpty() || value.contains("${")) {
            return fallback;
        }
        return value.trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
