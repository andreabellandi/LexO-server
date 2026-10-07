package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.service.data.text.input.CollocationPersistRequest;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/** Explicit OntoLex-FrAC persistence, kept separate from search computation. */
public final class CollocationPersistenceManager {

    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final CollocationPersistenceManager INSTANCE =
            new CollocationPersistenceManager();
    private static final Map<String, String> SCORE_PROPERTIES = scoreProperties();
    private final ValueFactory vf = SimpleValueFactory.getInstance();

    public static CollocationPersistenceManager get() {
        return INSTANCE;
    }

    private CollocationPersistenceManager() {
    }

    public String persist(CollocationPersistRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("MISSING_COLLOCATION: request body is required");
        }
        IRI head = absolute(request.head, "head");
        IRI collocate = absolute(request.collocate, "collocate");
        IRI observedIn = absolute(request.observedIn, "observedIn");
        if (request.frequency != null && !Double.isFinite(request.frequency.doubleValue())) {
            throw new IllegalArgumentException("INVALID_FREQUENCY: finite value required");
        }
        String identifier = LexiconCrudSupport.newResourceUri();
        IRI resource = vf.createIRI(identifier);
        String timestamp = LexiconCrudSupport.operationTimestamp();
        Model model = new LinkedHashModel();
        model.add(resource, RDF.TYPE, vf.createIRI(FRAC + "Collocation"));
        model.add(resource, vf.createIRI(FRAC + "head"), head);
        model.add(resource, RDFS.MEMBER, head);
        model.add(resource, RDFS.MEMBER, collocate);
        model.add(resource, vf.createIRI(FRAC + "observedIn"), observedIn);
        model.add(resource, DCTERMS.CREATOR,
                vf.createLiteral(LexiconCrudSupport.author(request.author)));
        model.add(resource, DCTERMS.CREATED, vf.createLiteral(timestamp, XSD.DATETIME));
        model.add(resource, DCTERMS.MODIFIED, vf.createLiteral(timestamp, XSD.DATETIME));
        if (request.frequency != null) {
            model.add(resource, RDF.VALUE, vf.createLiteral(request.frequency.doubleValue()));
        }
        if (request.metrics != null) {
            for (Map.Entry<String, Double> metric : request.metrics.entrySet()) {
                String property = SCORE_PROPERTIES.get(metric.getKey().toUpperCase(Locale.ROOT));
                if (property == null) {
                    throw new IllegalArgumentException(
                            "INVALID_METRIC: " + metric.getKey());
                }
                if (metric.getValue() == null
                        || !Double.isFinite(metric.getValue().doubleValue())) {
                    throw new IllegalArgumentException(
                            "INVALID_METRIC_VALUE: " + metric.getKey());
                }
                model.add(resource, vf.createIRI(property),
                        vf.createLiteral(metric.getValue().doubleValue()));
            }
        }
        String configuredGraph = LexOProperties.getProperty("GraphDb.lexiconNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexica");
        IRI graph = vf.createIRI(configuredGraph);
        RepositoryConnection connection = GraphDbUtil.getConnection(RepositoryTarget.LEXICON);
        try {
            connection.begin();
            connection.add(model, graph);
            connection.commit();
        } catch (RuntimeException error) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw error;
        } finally {
            GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON, connection);
        }
        return identifier;
    }

    private IRI absolute(String value, String field) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException();
            }
            return vf.createIRI(uri.toString());
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("INVALID_IRI: " + field
                    + " must be an absolute IRI");
        }
    }

    private static Map<String, String> scoreProperties() {
        Map<String, String> result = new HashMap<String, String>();
        result.put("RELATIVE_FREQUENCY", FRAC + "rel_freq");
        result.put("PMI", FRAC + "pmi");
        result.put("PMI2", FRAC + "mi2");
        result.put("PMI3", FRAC + "mi3");
        result.put("PMI_LOG_FREQ", FRAC + "pmi_logfreq");
        result.put("DICE", FRAC + "dice");
        result.put("LOG_DICE", FRAC + "logDice");
        result.put("MIN_SENSITIVITY", FRAC + "minSensitivity");
        result.put("T_SCORE", FRAC + "tScore");
        result.put("LOG_LIKELIHOOD", FRAC + "likelihood_ratio");
        result.put("CHI_SQUARE", FRAC + "chi2");
        result.put("SUPPORT", FRAC + "support");
        result.put("CONFIDENCE", FRAC + "confidence");
        result.put("LIFT", FRAC + "lift");
        result.put("CONVICTION", FRAC + "conviction");
        return Collections.unmodifiableMap(result);
    }
}
