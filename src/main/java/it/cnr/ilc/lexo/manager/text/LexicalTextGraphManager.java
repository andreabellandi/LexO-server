package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.AttestationFrequencySupport;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Lifecycle operations for per-text graphs stored in LexOLexica. */
public final class LexicalTextGraphManager {

    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final LexicalTextGraphManager INSTANCE =
            new LexicalTextGraphManager();

    public static LexicalTextGraphManager get() {
        return INSTANCE;
    }

    private LexicalTextGraphManager() {
    }

    /**
     * Atomically removes the attestations belonging to a text, every statement
     * that references them in LexOLexica, synchronizes affected frequencies in
     * other document graphs, and clears both graph families of the text.
     */
    public boolean deleteDocumentGraphs(String fileId) {
        RepositoryConnection connection =
                GraphDbUtil.getConnection(RepositoryTarget.LEXICON);
        try {
            return deleteDocumentGraphs(connection, fileId);
        } finally {
            GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON, connection);
        }
    }

    boolean deleteDocumentGraphs(RepositoryConnection connection, String fileId) {
        IRI attestations = SimpleValueFactory.getInstance().createIRI(
                LexicalNamedGraphs.attestationGraphUri(fileId));
        IRI annotations = SimpleValueFactory.getInstance().createIRI(
                LexicalNamedGraphs.annotationGraphUri(fileId));
        boolean existed = connection.hasStatement(null, null, null, false, attestations)
                || connection.hasStatement(null, null, null, false, annotations);
        try {
            connection.begin();
            Set<Resource> documentAttestations = documentAttestations(
                    connection, attestations);
            Map<String, FrequencyTarget> affectedFrequencies =
                    affectedFrequencies(connection, documentAttestations,
                            attestations);
            for (Resource attestation : documentAttestations) {
                connection.remove(attestation, null, null);
                connection.remove((Resource) null, null, attestation);
            }
            for (FrequencyTarget target : affectedFrequencies.values()) {
                AttestationFrequencySupport.synchronize(connection,
                        target.observable, target.observedIn, target.graph);
            }
            connection.clear(attestations);
            connection.clear(annotations);
            connection.commit();
            return existed;
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        }
    }

    private Set<Resource> documentAttestations(RepositoryConnection connection,
                                               IRI graph) {
        Set<Resource> attestations = new LinkedHashSet<Resource>();
        IRI attestationType = SimpleValueFactory.getInstance().createIRI(
                FRAC + "Attestation");
        IRI attestationRelation = SimpleValueFactory.getInstance().createIRI(
                FRAC + "attestation");
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, RDF.TYPE, attestationType, false, graph)) {
            while (statements.hasNext()) {
                attestations.add(statements.next().getSubject());
            }
        }
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, attestationRelation, null, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                if (statement.getObject() instanceof Resource) {
                    attestations.add((Resource) statement.getObject());
                }
            }
        }
        return attestations;
    }

    private Map<String, FrequencyTarget> affectedFrequencies(
            RepositoryConnection connection, Set<Resource> attestations,
            Resource deletedGraph) {
        Map<String, FrequencyTarget> result =
                new LinkedHashMap<String, FrequencyTarget>();
        IRI attestationRelation = SimpleValueFactory.getInstance().createIRI(
                FRAC + "attestation");
        IRI frequencyRelation = SimpleValueFactory.getInstance().createIRI(
                FRAC + "frequency");
        IRI observedInRelation = SimpleValueFactory.getInstance().createIRI(
                FRAC + "observedIn");
        for (Resource attestation : attestations) {
            try (RepositoryResult<Statement> links = connection.getStatements(
                    null, attestationRelation, attestation, false)) {
                while (links.hasNext()) {
                    Statement link = links.next();
                    Resource graph = link.getContext();
                    if (!(link.getSubject() instanceof IRI)
                            || deletedGraph.equals(graph)
                            || !isAttestationGraph(graph)) {
                        continue;
                    }
                    IRI observable = (IRI) link.getSubject();
                    try (RepositoryResult<Statement> frequencies =
                                 connection.getStatements(observable,
                                         frequencyRelation, null, false, graph)) {
                        while (frequencies.hasNext()) {
                            Value frequency = frequencies.next().getObject();
                            if (!(frequency instanceof Resource)) {
                                continue;
                            }
                            try (RepositoryResult<Statement> observations =
                                         connection.getStatements(
                                                 (Resource) frequency,
                                                 observedInRelation, null,
                                                 false, graph)) {
                                while (observations.hasNext()) {
                                    Value observedIn = observations.next().getObject();
                                    if (observedIn instanceof IRI) {
                                        FrequencyTarget target = new FrequencyTarget(
                                                observable, (IRI) observedIn, graph);
                                        result.put(target.key(), target);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private boolean isAttestationGraph(Resource graph) {
        if (!(graph instanceof IRI)) {
            return false;
        }
        String base = LexicalNamedGraphs.attestationGraphBaseUri();
        String value = graph.stringValue();
        if (!value.startsWith(base)) {
            return false;
        }
        String fileId = value.substring(base.length());
        try {
            return value.equals(LexicalNamedGraphs.attestationGraphUri(fileId));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static final class FrequencyTarget {

        final IRI observable;
        final IRI observedIn;
        final Resource graph;

        FrequencyTarget(IRI observable, IRI observedIn, Resource graph) {
            this.observable = observable;
            this.observedIn = observedIn;
            this.graph = graph;
        }

        String key() {
            return graph.stringValue() + "\n" + observable.stringValue() + "\n"
                    + observedIn.stringValue();
        }
    }
}
