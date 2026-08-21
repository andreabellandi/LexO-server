package it.cnr.ilc.lexo.manager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Shared persistence rules for per-observable, per-text FRAC frequencies. */
public final class AttestationFrequencySupport {

    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private AttestationFrequencySupport() {
    }

    public static int synchronize(RepositoryConnection connection,
                                  IRI observable, IRI observedIn,
                                  Resource graph) {
        int value = countObservableAttestations(connection, observable, graph);
        List<Resource> frequencies = frequencyResources(connection, observable,
                observedIn, graph);
        if (value == 0) {
            removeFrequencyResources(connection, observable, frequencies, graph);
            return 0;
        }
        writeFrequency(connection, observable, observedIn, graph, frequencies,
                value);
        return value;
    }

    public static int increment(RepositoryConnection connection,
                                IRI observable, IRI observedIn,
                                Resource graph, int increment) {
        List<Resource> frequencies = frequencyResources(connection, observable,
                observedIn, graph);
        Integer current = frequencies.size() == 1
                ? frequencyValue(connection, frequencies.get(0), graph) : null;
        int value = current == null
                ? countObservableAttestations(connection, observable, graph)
                : current.intValue() + increment;
        writeFrequency(connection, observable, observedIn, graph, frequencies,
                value);
        return value;
    }

    private static void writeFrequency(RepositoryConnection connection,
                                       IRI observable, IRI observedIn,
                                       Resource graph,
                                       List<Resource> existing, int value) {
        Resource frequency = existing.isEmpty() ? VF.createBNode() : existing.get(0);
        if (existing.size() > 1) {
            removeFrequencyResources(connection, observable,
                    existing.subList(1, existing.size()), graph);
        }
        IRI relation = VF.createIRI(FRAC + "frequency");
        IRI observedInRelation = VF.createIRI(FRAC + "observedIn");
        connection.add(observable, relation, frequency, graph);
        connection.add(frequency, RDF.TYPE, VF.createIRI(FRAC + "Frequency"), graph);
        connection.remove(frequency, observedInRelation, null, graph);
        connection.add(frequency, observedInRelation, observedIn, graph);
        connection.remove(frequency, RDF.VALUE, null, graph);
        connection.add(frequency, RDF.VALUE,
                VF.createLiteral(Integer.toString(value), XSD.INT), graph);
    }

    private static void removeFrequencyResources(RepositoryConnection connection,
                                                 IRI observable,
                                                 List<Resource> frequencies,
                                                 Resource graph) {
        IRI relation = VF.createIRI(FRAC + "frequency");
        for (Resource frequency : frequencies) {
            connection.remove(observable, relation, frequency, graph);
            connection.remove(frequency, null, null, graph);
        }
    }

    private static List<Resource> frequencyResources(
            RepositoryConnection connection, Resource observable,
            IRI observedIn, Resource graph) {
        List<Resource> result = new ArrayList<Resource>();
        IRI relation = VF.createIRI(FRAC + "frequency");
        IRI observedInRelation = VF.createIRI(FRAC + "observedIn");
        try (RepositoryResult<Statement> statements = connection.getStatements(
                observable, relation, null, false, graph)) {
            while (statements.hasNext()) {
                Value candidate = statements.next().getObject();
                if (candidate instanceof Resource
                        && connection.hasStatement((Resource) candidate,
                                observedInRelation, observedIn, false, graph)
                        && !result.contains(candidate)) {
                    result.add((Resource) candidate);
                }
            }
        }
        return result;
    }

    private static Integer frequencyValue(RepositoryConnection connection,
                                          Resource frequency, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                frequency, RDF.VALUE, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof Literal) {
                    try {
                        int parsed = ((Literal) value).intValue();
                        return parsed < 0 ? null : Integer.valueOf(parsed);
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static int countObservableAttestations(
            RepositoryConnection connection, IRI observable, Resource graph) {
        Set<String> attestations = new HashSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                observable, VF.createIRI(FRAC + "attestation"), null, false,
                graph)) {
            while (statements.hasNext()) {
                Value candidate = statements.next().getObject();
                if (candidate instanceof Resource
                        && connection.hasStatement((Resource) candidate, RDF.TYPE,
                                VF.createIRI(FRAC + "Attestation"), false, graph)) {
                    attestations.add(candidate.stringValue());
                }
            }
        }
        return attestations.size();
    }
}
