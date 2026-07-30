package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.service.data.attestation.AttestationMetadataValue;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByObservableInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationFilter;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationLocusUpdate;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationMetadataBatch;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationMetadataProperty;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationMetadataUpdate;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationObservableUpdate;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationOccurrence;
import it.cnr.ilc.lexo.service.data.attestation.output.Attestation;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationDeletionItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationDeletionResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationListItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationLocusUpdateResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationMetadataPatchItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationMetadataPatchResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationObservableUpdateItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationObservableUpdateResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationPage;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Manages FRAC attestations, their metadata, and corresponding NIF loci. */
public class AttestationManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static final String DCMITYPE = "http://purl.org/dc/dcmitype/";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String NO_LABEL = "no label";
    private static final String ATTESTATION_SERVICE = "AttestationService";
    private static final int MAX_FILTER_DEPTH = 5;
    private static final int MAX_FILTER_NODES = 50;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final String DEFAULT_STRUCTURE_NAMESPACE =
            "https://lexo.ilc.cnr.it/vocabulary/nif-structure#";
    private static final Set<String> RESERVED_METADATA_PROPERTIES =
            reservedMetadataProperties();

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final ConnectionSource connections;
    private final String structureNamespace;
    private final String textGraphBase;

    public AttestationManager() {
        this(new GraphDbConnectionSource());
    }

    AttestationManager(Repository lexicalRepository, Repository textRepository) {
        this(new RepositoryConnectionSource(lexicalRepository, textRepository));
    }

    private AttestationManager(ConnectionSource connections) {
        this.connections = connections;
        this.structureNamespace = namespace(System.getProperty(
                "lexo.text.structureNamespace", DEFAULT_STRUCTURE_NAMESPACE));
        this.textGraphBase = trailingSeparator(configured("TextGraphDb.namedGraphBase",
                "https://lexo.ilc.cnr.it/graphs/nif/"));
    }

    /** Manager entry point for one attestation occurrence. */
    public Attestation create(String observableValue, String attestedValue,
                              String startValue,
                              String endValue, String corpusValue,
                              boolean external, String author) throws ManagerException {
        int start = integer("start", startValue);
        int end = integer("end", endValue);
        List<AttestationOccurrence> occurrences = new ArrayList<AttestationOccurrence>();
        occurrences.add(new AttestationOccurrence(attestedValue,
                Integer.valueOf(start), Integer.valueOf(end)));
        return createBatch(observableValue, corpusValue, external, author,
                occurrences).get(0);
    }

    /** Validates a batch and persists all attestations in one transaction per repository. */
    public List<Attestation> createBatch(String observableValue, String corpusValue,
                                         boolean external, String author,
                                         List<AttestationOccurrence> occurrences)
            throws ManagerException {
        String observable = required("observable", observableValue);
        String corpus = required("corpus", corpusValue);
        if (occurrences == null || occurrences.isEmpty()) {
            throw new ManagerException("MISSING_OCCURRENCES: at least one attestation occurrence is required");
        }

        IRI observableIri = iri("observable", observable);
        IRI corpusIri = external ? externalUrl(corpus) : iri("corpus", corpus);
        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            validateObservable(lexical, observableIri);
            IRI lexicalGraph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
            List<String> observableTypes = rdfTypes(lexical, observableIri,
                    lexicalGraph);
            String observableLabel = observableLabel(lexical, observableIri,
                    lexicalGraph);
            List<PendingAttestation> pending = new ArrayList<PendingAttestation>();
            Map<String, Model> batchLoci = new HashMap<String, Model>();
            Set<String> reservedAttestationIris = new HashSet<String>();
            long batchTimestamp = System.currentTimeMillis();

            for (int index = 0; index < occurrences.size(); index++) {
                AttestationOccurrence occurrence = occurrences.get(index);
                if (occurrence == null) {
                    throw new ManagerException("INVALID_OCCURRENCE: occurrence at index "
                            + index + " is null");
                }
                String value = requiredValue("occurrences[" + index + "].value",
                        occurrence.value);
                if (occurrence.start == null || occurrence.end == null) {
                    throw new ManagerException("MISSING_PARAMETER: occurrences[" + index
                            + "].start and occurrences[" + index + "].end are required");
                }
                int start = occurrence.start.intValue();
                int end = occurrence.end.intValue();
                validateOffsets(start, end, index);
                TextLocation location = external
                        ? externalLocation(corpusIri, start, end)
                        : internalLocation(text, corpusIri, value, start, end);

                Timestamp now = new Timestamp(batchTimestamp + index);
                String timestamp = timestamp(now);
                IRI attestationIri = newAttestationIri(lexical, now,
                        reservedAttestationIris);
                IRI locus = vf.createIRI(location.locus);
                IRI attestationGraph = vf.createIRI(
                        LexicalNamedGraphs.attestationGraphUri(location.fileId));
                Model phraseStatements = phraseModel(locus, location.referenceContext,
                        value, start, end, location.language);
                String batchLocusKey = location.textGraph.stringValue()
                        + "|" + location.locus;
                Model existingBatchLocus = batchLoci.get(batchLocusKey);
                Model newPhraseStatements;
                if (existingBatchLocus == null) {
                    newPhraseStatements = newStatements(text, phraseStatements,
                            location.textGraph, locus);
                    batchLoci.put(batchLocusKey, phraseStatements);
                } else if (existingBatchLocus.equals(phraseStatements)) {
                    newPhraseStatements = new LinkedHashModel();
                } else {
                    throw new ManagerException("LOCUS_CONFLICT: occurrences in the batch "
                            + "define different data for " + location.locus);
                }
                Model attestationStatements = attestationModel(attestationIri,
                        observableIri, locus, corpusIri, value,
                        location.language, author, timestamp);
                List<String> locusTypes = resultLocusTypes(text, locus,
                        location.textGraph);
                Attestation result = attestationResult(attestationIri, observable,
                        observableLabel, observableTypes, value, start, end,
                        corpus, location, external, author,
                        timestamp, locusTypes);
                pending.add(new PendingAttestation(attestationGraph,
                        location.textGraph, attestationStatements,
                        newPhraseStatements, result));
            }

            persistBatch(lexical, text, pending);
            List<Attestation> results = new ArrayList<Attestation>();
            for (PendingAttestation item : pending) {
                results.add(item.result);
            }
            return results;
        } catch (ManagerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_CREATE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    /** Validates and creates one attestation per observable at a shared locus. */
    public List<Attestation> createByLocus(String corpusValue, boolean external,
                                           String author,
                                           AttestationByLocusInput input)
            throws ManagerException {
        String corpus = required("corpus", corpusValue);
        if (input == null) {
            throw new ManagerException("MISSING_PARAMETER: locus is required");
        }
        String value = requiredValue("value", input.value);
        if (input.start == null || input.end == null) {
            throw new ManagerException("MISSING_PARAMETER: start and end are required");
        }
        if (input.observables == null || input.observables.isEmpty()) {
            throw new ManagerException("MISSING_OBSERVABLES: at least one observable is required");
        }
        int start = input.start.intValue();
        int end = input.end.intValue();
        validateLocusOffsets(start, end);
        IRI corpusIri = external ? externalUrl(corpus) : iri("corpus", corpus);

        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            IRI lexicalGraph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
            TextLocation location = external
                    ? externalLocation(corpusIri, start, end)
                    : internalLocation(text, corpusIri, value, start, end);
            IRI locus = vf.createIRI(location.locus);
            IRI attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(location.fileId));
            Model phraseStatements = newStatements(text,
                    phraseModel(locus, location.referenceContext, value, start, end,
                            location.language), location.textGraph, locus);
            List<String> locusTypes = resultLocusTypes(text, locus,
                    location.textGraph);
            List<PendingAttestation> pending = new ArrayList<PendingAttestation>();
            Set<String> reservedAttestationIris = new HashSet<String>();
            long batchTimestamp = System.currentTimeMillis();

            for (int index = 0; index < input.observables.size(); index++) {
                String observable = required("observables[" + index + "]",
                        input.observables.get(index));
                IRI observableIri = iri("observables[" + index + "]", observable);
                validateObservable(lexical, observableIri);
                List<String> observableTypes = rdfTypes(lexical, observableIri,
                        lexicalGraph);
                String observableLabel = observableLabel(lexical, observableIri,
                        lexicalGraph);
                Timestamp now = new Timestamp(batchTimestamp + index);
                String created = timestamp(now);
                IRI attestationIri = newAttestationIri(lexical, now,
                        reservedAttestationIris);
                Model attestationStatements = attestationModel(attestationIri,
                        observableIri, locus, corpusIri, value, location.language,
                        author, created);
                Attestation result = attestationResult(attestationIri, observable,
                        observableLabel, observableTypes, value, start, end, corpus,
                        location, external, author, created, locusTypes);
                pending.add(new PendingAttestation(attestationGraph,
                        location.textGraph, attestationStatements,
                        index == 0 ? phraseStatements : new LinkedHashModel(), result));
            }

            persistBatch(lexical, text, pending);
            List<Attestation> results = new ArrayList<Attestation>();
            for (PendingAttestation item : pending) {
                results.add(item.result);
            }
            return results;
        } catch (ManagerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_CREATE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private Attestation attestationResult(IRI attestationIri, String observable,
                                          String observableLabel,
                                          List<String> observableTypes,
                                          String value, int start, int end,
                                          String corpus,
                                          TextLocation location, boolean external,
                                          String author, String timestamp,
                                          List<String> locusTypes) {
        Attestation result = new Attestation();
        result.attestation = attestationIri.stringValue();
        result.observable = observable;
        result.observableLabel = observableLabel;
        result.observableTypes = new ArrayList<String>(observableTypes);
        result.value = value;
        result.start = Integer.valueOf(start);
        result.end = Integer.valueOf(end);
        result.corpus = corpus;
        result.locus = location.locus;
        result.locusTypes = new ArrayList<String>(locusTypes);
        result.language = location.language;
        result.referenceContext = location.referenceContext.stringValue();
        result.fileId = location.fileId;
        result.external = Boolean.valueOf(external);
        result.creator = author;
        result.creationDate = timestamp;
        result.lastUpdate = timestamp;
        return result;
    }

    private void validateOffsets(int start, int end, int index) throws ManagerException {
        if (start < 0 || end < start) {
            throw new ManagerException("INVALID_OFFSETS: occurrences[" + index
                    + "] must satisfy 0 <= start <= end");
        }
    }

    private void validateLocusOffsets(int start, int end) throws ManagerException {
        if (start < 0 || end < start) {
            throw new ManagerException(
                    "INVALID_OFFSETS: start and end must satisfy 0 <= start <= end");
        }
    }

    /** Updates the LexO-generated, unshared NIF locus of one attestation. */
    public AttestationLocusUpdateResult updateLocus(String fileIdValue,
                                                     AttestationLocusUpdate input)
            throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        if (input == null) {
            throw new ManagerException("MISSING_PARAMETER: update is required");
        }
        IRI attestation = iri("attestation",
                required("attestation", input.attestation));
        if (input.start == null || input.end == null) {
            throw new ManagerException(
                    "MISSING_PARAMETER: start and end are required");
        }
        int start = input.start.intValue();
        int end = input.end.intValue();
        validateLocusOffsets(start, end);
        boolean updateGloss = input.updateGloss == null
                || input.updateGloss.booleanValue();
        final IRI attestationGraph;
        final IRI textGraph;
        try {
            attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(fileId));
            textGraph = vf.createIRI(textGraphBase + "documents/" + fileId);
        } catch (IllegalArgumentException e) {
            throw new ManagerException(
                    "INVALID_FILE_ID: fileId contains unsupported characters");
        }

        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        Model oldLocusStatements = null;
        Model newLocusStatements = null;
        IRI oldLocus = null;
        IRI newLocus = null;
        boolean textCommitted = false;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            if (!lexical.hasStatement(attestation, RDF.TYPE,
                    vf.createIRI(FRAC + "Attestation"), false,
                    attestationGraph)) {
                throw new ManagerException("ATTESTATION_NOT_FOUND: "
                        + attestation.stringValue()
                        + " is not an attestation in the graph for fileId");
            }
            oldLocus = uniqueIriObject(lexical, attestation,
                    vf.createIRI(FRAC + "locus"), attestationGraph,
                    "ATTESTATION_LOCUS_MISSING",
                    "ATTESTATION_LOCUS_AMBIGUOUS");
            ensureModifiableLocus(lexical, text, attestation, oldLocus,
                    attestationGraph, textGraph);
            IRI referenceContext = uniqueIriObject(text, oldLocus,
                    vf.createIRI(NIF + "referenceContext"), textGraph,
                    "LOCUS_REFERENCE_CONTEXT_MISSING",
                    "LOCUS_REFERENCE_CONTEXT_AMBIGUOUS");
            Literal canonical = firstLiteral(text, referenceContext,
                    vf.createIRI(NIF + "isString"), textGraph);
            if (canonical == null) {
                throw new ManagerException("CANONICAL_TEXT_NOT_FOUND: the locus "
                        + "reference context has no nif:isString in the text graph");
            }
            String value = unicodeSubstring(canonical.getLabel(), start, end);
            newLocus = vf.createIRI(phraseUri(referenceContext, start, end));
            if (!newLocus.equals(oldLocus)
                    && text.hasStatement(newLocus, null, null, false, textGraph)) {
                throw new ManagerException("LOCUS_CONFLICT: the target NIF locus "
                        + "already exists in the text graph");
            }

            oldLocusStatements = statementsForSubject(text, oldLocus, textGraph);
            Literal oldAnchor = firstLiteral(text, oldLocus,
                    vf.createIRI(NIF + "anchorOf"), textGraph);
            String language = oldAnchor != null && oldAnchor.getLanguage().isPresent()
                    ? oldAnchor.getLanguage().get()
                    : canonical.getLanguage().orElse(null);
            newLocusStatements = movedLocusStatements(oldLocusStatements,
                    newLocus, referenceContext, value, start, end,
                    language);
            String modified = timestamp(new Timestamp(System.currentTimeMillis()));
            Literal attestedValue = blank(language) ? vf.createLiteral(value)
                    : vf.createLiteral(value, language);

            lexical.begin();
            text.begin();
            text.remove(oldLocus, null, null, textGraph);
            text.add(newLocusStatements, textGraph);
            lexical.remove(attestation, vf.createIRI(FRAC + "locus"), null,
                    attestationGraph);
            lexical.add(attestation, vf.createIRI(FRAC + "locus"), newLocus,
                    attestationGraph);
            lexical.remove(attestation, RDF.VALUE, null, attestationGraph);
            lexical.add(attestation, RDF.VALUE, attestedValue, attestationGraph);
            if (updateGloss) {
                lexical.remove(attestation, vf.createIRI(FRAC + "gloss"), null,
                        attestationGraph);
                lexical.add(attestation, vf.createIRI(FRAC + "gloss"),
                        attestedValue, attestationGraph);
            }
            lexical.remove(attestation, DCTERMS.MODIFIED, null, attestationGraph);
            lexical.add(attestation, DCTERMS.MODIFIED, vf.createLiteral(modified),
                    attestationGraph);
            text.commit();
            textCommitted = true;
            lexical.commit();

            AttestationLocusUpdateResult result =
                    new AttestationLocusUpdateResult();
            result.fileId = fileId;
            result.attestation = attestation.stringValue();
            result.previousLocus = oldLocus.stringValue();
            result.locus = newLocus.stringValue();
            result.value = value;
            result.start = Integer.valueOf(start);
            result.end = Integer.valueOf(end);
            result.glossUpdated = updateGloss;
            result.lastUpdate = modified;
            return result;
        } catch (ManagerException e) {
            rollback(lexical);
            rollback(text);
            throw e;
        } catch (RuntimeException e) {
            rollback(lexical);
            rollback(text);
            if (textCommitted && oldLocusStatements != null && newLocus != null) {
                compensateLocusUpdate(text, oldLocusStatements, newLocus,
                        textGraph);
            }
            throw new ManagerException("ATTESTATION_LOCUS_UPDATE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    /** Atomically replaces the observable of one or more attestations. */
    public AttestationObservableUpdateResult updateObservable(String fileIdValue,
            AttestationObservableUpdate input) throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        if (input == null) {
            throw new ManagerException("MISSING_PARAMETER: update is required");
        }
        IRI observable = iri("observable",
                required("observable", input.observable));
        if (input.attestations == null || input.attestations.isEmpty()) {
            throw new ManagerException(
                    "MISSING_ATTESTATIONS: provide at least one attestation");
        }
        final IRI attestationGraph;
        final IRI textGraph;
        try {
            attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(fileId));
            textGraph = vf.createIRI(textGraphBase + "documents/" + fileId);
        } catch (IllegalArgumentException e) {
            throw new ManagerException(
                    "INVALID_FILE_ID: fileId contains unsupported characters");
        }

        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            validateObservable(lexical, observable);
            List<ValidatedObservableUpdate> updates =
                    validateObservableUpdates(lexical, attestationGraph,
                            input.attestations);
            Map<String, FrequencyKey> affected =
                    new LinkedHashMap<String, FrequencyKey>();
            for (ValidatedObservableUpdate update : updates) {
                IRI observedIn = observedText(lexical, text, update.attestation,
                        attestationGraph, textGraph);
                addFrequencyKey(affected, observable, observedIn,
                        attestationGraph);
                for (IRI previous : update.previousObservables) {
                    addFrequencyKey(affected, previous, observedIn,
                            attestationGraph);
                }
            }
            String modified = timestamp(new Timestamp(System.currentTimeMillis()));
            IRI relation = vf.createIRI(FRAC + "attestation");
            lexical.begin();
            for (ValidatedObservableUpdate update : updates) {
                lexical.remove((Resource) null, relation, update.attestation,
                        attestationGraph);
                lexical.add(observable, relation, update.attestation,
                        attestationGraph);
                lexical.remove(update.attestation, DCTERMS.MODIFIED, null,
                        attestationGraph);
                lexical.add(update.attestation, DCTERMS.MODIFIED,
                        vf.createLiteral(modified), attestationGraph);
            }
            Map<String, Integer> frequencies =
                    synchronizeFrequencies(lexical, affected);
            lexical.commit();

            AttestationObservableUpdateResult result =
                    new AttestationObservableUpdateResult();
            result.fileId = fileId;
            result.frequencies.putAll(frequencies);
            for (ValidatedObservableUpdate update : updates) {
                AttestationObservableUpdateItem item =
                        new AttestationObservableUpdateItem();
                item.attestation = update.attestation.stringValue();
                for (IRI previous : update.previousObservables) {
                    item.previousObservables.add(previous.stringValue());
                }
                item.observable = observable.stringValue();
                item.lastUpdate = modified;
                result.updated.add(item);
            }
            return result;
        } catch (ManagerException e) {
            rollback(lexical);
            throw e;
        } catch (RuntimeException e) {
            rollback(lexical);
            throw new ManagerException("ATTESTATION_OBSERVABLE_UPDATE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private List<ValidatedObservableUpdate> validateObservableUpdates(
            RepositoryConnection connection, Resource graph,
            List<String> requested) throws ManagerException {
        List<ValidatedObservableUpdate> result =
                new ArrayList<ValidatedObservableUpdate>();
        Set<String> unique = new HashSet<String>();
        IRI relation = vf.createIRI(FRAC + "attestation");
        for (int index = 0; index < requested.size(); index++) {
            IRI attestation = iri("attestations[" + index + "]",
                    required("attestations[" + index + "]", requested.get(index)));
            if (!unique.add(attestation.stringValue())) {
                throw new ManagerException("DUPLICATE_ATTESTATION: "
                        + attestation.stringValue());
            }
            if (!connection.hasStatement(attestation, RDF.TYPE,
                    vf.createIRI(FRAC + "Attestation"), false, graph)) {
                throw new ManagerException("ATTESTATION_NOT_FOUND: "
                        + attestation.stringValue()
                        + " is not an attestation in the graph for fileId");
            }
            List<IRI> previous = new ArrayList<IRI>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, relation, attestation, false, graph)) {
                while (statements.hasNext()) {
                    Resource subject = statements.next().getSubject();
                    if (subject instanceof IRI && !previous.contains(subject)) {
                        previous.add((IRI) subject);
                    }
                }
            }
            if (previous.isEmpty()) {
                throw new ManagerException("ATTESTATION_OBSERVABLE_MISSING: "
                        + attestation.stringValue());
            }
            Collections.sort(previous, new Comparator<IRI>() {
                @Override
                public int compare(IRI left, IRI right) {
                    return left.stringValue().compareTo(right.stringValue());
                }
            });
            result.add(new ValidatedObservableUpdate(attestation, previous));
        }
        return result;
    }

    /** Atomically deletes selected or all attestations of one observable. */
    public AttestationDeletionResult deleteByObservable(String fileIdValue,
            AttestationDeleteByObservableInput input) throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        if (input == null) {
            throw new ManagerException("MISSING_PARAMETER: deletion is required");
        }
        IRI observable = iri("observable", required("observable", input.observable));
        return deleteAttestations(fileId, observable, null, input.all,
                input.attestations);
    }

    /** Atomically deletes selected or all attestations at one NIF locus. */
    public AttestationDeletionResult deleteByLocus(String fileIdValue,
            AttestationDeleteByLocusInput input) throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        if (input == null) {
            throw new ManagerException("MISSING_PARAMETER: deletion is required");
        }
        IRI locus = iri("locus", required("locus", input.locus));
        return deleteAttestations(fileId, null, locus, input.all,
                input.attestations);
    }

    private AttestationDeletionResult deleteAttestations(String fileId,
            IRI expectedObservable, IRI expectedLocus, Boolean allValue,
            List<String> requestedValues) throws ManagerException {
        final IRI attestationGraph;
        try {
            attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(fileId));
        } catch (IllegalArgumentException e) {
            throw new ManagerException(
                    "INVALID_FILE_ID: fileId contains unsupported characters");
        }
        boolean all = Boolean.TRUE.equals(allValue);
        if (all && requestedValues != null && !requestedValues.isEmpty()) {
            throw new ManagerException("INVALID_DELETE_SELECTION: all=true cannot be combined with an attestation list");
        }
        if (!all && (requestedValues == null || requestedValues.isEmpty())) {
            throw new ManagerException("MISSING_ATTESTATIONS: provide at least one attestation or set all=true");
        }

        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            List<IRI> requested = all
                    ? matchingAttestations(lexical, attestationGraph,
                            expectedObservable, expectedLocus)
                    : explicitAttestations(requestedValues);
            List<PendingDeletion> pending = validateDeletions(lexical,
                    attestationGraph, requested, expectedObservable, expectedLocus);
            Map<String, FrequencyKey> affected =
                    deletionFrequencyKeys(lexical, text, attestationGraph,
                            vf.createIRI(textGraphBase + "documents/" + fileId),
                            pending);
            if (expectedObservable != null) {
                addExistingFrequencyKeys(lexical, affected, expectedObservable,
                        attestationGraph);
            }
            List<PendingLocusDeletion> loci = orphanGeneratedLoci(lexical, text,
                    fileId, pending);
            Map<String, Integer> frequencies = persistDeletion(lexical, text,
                    attestationGraph, pending, loci, affected);
            return deletionResult(fileId, pending, loci, frequencies);
        } catch (ManagerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_DELETE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private List<IRI> explicitAttestations(List<String> values)
            throws ManagerException {
        List<IRI> result = new ArrayList<IRI>();
        Set<String> unique = new HashSet<String>();
        for (int index = 0; index < values.size(); index++) {
            IRI attestation = iri("attestations[" + index + "]",
                    required("attestations[" + index + "]", values.get(index)));
            if (!unique.add(attestation.stringValue())) {
                throw new ManagerException("DUPLICATE_ATTESTATION: "
                        + attestation.stringValue());
            }
            result.add(attestation);
        }
        return result;
    }

    private List<IRI> matchingAttestations(RepositoryConnection connection,
            Resource graph, IRI observable, IRI locus) {
        Set<String> values = new HashSet<String>();
        IRI relation = observable == null ? vf.createIRI(FRAC + "locus")
                : vf.createIRI(FRAC + "attestation");
        Resource subject = observable == null ? null : observable;
        Value object = observable == null ? locus : null;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, relation, object, false, graph)) {
            while (statements.hasNext()) {
                Value candidate = observable == null
                        ? statements.next().getSubject()
                        : statements.next().getObject();
                if (candidate instanceof IRI
                        && connection.hasStatement((IRI) candidate, RDF.TYPE,
                                vf.createIRI(FRAC + "Attestation"), false, graph)) {
                    values.add(candidate.stringValue());
                }
            }
        }
        List<String> sorted = new ArrayList<String>(values);
        Collections.sort(sorted);
        List<IRI> result = new ArrayList<IRI>();
        for (String value : sorted) {
            result.add(vf.createIRI(value));
        }
        return result;
    }

    private List<PendingDeletion> validateDeletions(
            RepositoryConnection connection, Resource graph, List<IRI> requested,
            IRI expectedObservable, IRI expectedLocus) throws ManagerException {
        List<PendingDeletion> result = new ArrayList<PendingDeletion>();
        IRI attestationType = vf.createIRI(FRAC + "Attestation");
        IRI attestationRelation = vf.createIRI(FRAC + "attestation");
        IRI locusRelation = vf.createIRI(FRAC + "locus");
        for (IRI attestation : requested) {
            if (!connection.hasStatement(attestation, RDF.TYPE, attestationType,
                    false, graph)) {
                throw new ManagerException("ATTESTATION_NOT_FOUND: "
                        + attestation.stringValue()
                        + " is not an attestation in the graph for fileId");
            }
            if (expectedObservable != null && !connection.hasStatement(
                    expectedObservable, attestationRelation, attestation, false,
                    graph)) {
                throw new ManagerException("ATTESTATION_OBSERVABLE_MISMATCH: "
                        + attestation.stringValue());
            }
            Value locusValue = firstObject(connection, attestation,
                    locusRelation, graph);
            if (!(locusValue instanceof IRI)) {
                throw new ManagerException("ATTESTATION_LOCUS_MISSING: "
                        + attestation.stringValue());
            }
            IRI locus = (IRI) locusValue;
            if (expectedLocus != null && !expectedLocus.equals(locus)) {
                throw new ManagerException("ATTESTATION_LOCUS_MISMATCH: "
                        + attestation.stringValue());
            }
            List<IRI> observables = observableSubjects(connection,
                    attestationRelation, attestation, graph);
            if (expectedObservable != null && !observables.contains(expectedObservable)) {
                observables.add(expectedObservable);
            }
            PendingDeletion item = new PendingDeletion(attestation,
                    observables, locus);
            result.add(item);
        }
        return result;
    }

    private List<IRI> observableSubjects(RepositoryConnection connection,
                                         IRI predicate, Value object,
                                         Resource graph) {
        List<IRI> result = new ArrayList<IRI>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, predicate, object, false, graph)) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (subject instanceof IRI && !result.contains(subject)) {
                    result.add((IRI) subject);
                }
            }
        }
        return result;
    }

    private List<PendingLocusDeletion> orphanGeneratedLoci(
            RepositoryConnection lexical, RepositoryConnection text, String fileId,
            List<PendingDeletion> pending) {
        Set<String> selected = new HashSet<String>();
        Set<String> locusValues = new HashSet<String>();
        for (PendingDeletion item : pending) {
            selected.add(item.attestation.stringValue());
            locusValues.add(item.locus.stringValue());
        }
        Resource textGraph = vf.createIRI(textGraphBase + "documents/" + fileId);
        IRI generatedBy = vf.createIRI(PROV + "wasGeneratedBy");
        IRI service = attestationServiceIri();
        List<String> sorted = new ArrayList<String>(locusValues);
        Collections.sort(sorted);
        List<PendingLocusDeletion> result =
                new ArrayList<PendingLocusDeletion>();
        for (String value : sorted) {
            IRI locus = vf.createIRI(value);
            if (!hasRemainingAttestation(lexical, locus, selected)
                    && text.hasStatement(locus, generatedBy, service, false,
                            textGraph)) {
                Model statements = new LinkedHashModel();
                try (RepositoryResult<Statement> existing = text.getStatements(
                        locus, null, null, false, textGraph)) {
                    while (existing.hasNext()) {
                        statements.add(existing.next());
                    }
                }
                result.add(new PendingLocusDeletion(locus, textGraph, statements));
            }
        }
        return result;
    }

    private boolean hasRemainingAttestation(RepositoryConnection connection,
            IRI locus, Set<String> selected) {
        String graphBase = LexicalNamedGraphs.attestationGraphBaseUri();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, vf.createIRI(FRAC + "locus"), locus, false)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                Resource context = statement.getContext();
                if (context != null
                        && context.stringValue().startsWith(graphBase)
                        && !selected.contains(statement.getSubject().stringValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private Map<String, Integer> persistDeletion(RepositoryConnection lexical,
            RepositoryConnection text, Resource attestationGraph,
            List<PendingDeletion> pending, List<PendingLocusDeletion> loci,
            Map<String, FrequencyKey> affected)
            throws ManagerException {
        boolean textCommitted = false;
        try {
            lexical.begin();
            text.begin();
            IRI attestationRelation = vf.createIRI(FRAC + "attestation");
            for (PendingDeletion item : pending) {
                lexical.remove((Resource) null, attestationRelation,
                        item.attestation,
                        attestationGraph);
                lexical.remove(item.attestation, null, null, attestationGraph);
            }
            for (PendingLocusDeletion locus : loci) {
                text.remove(locus.locus, null, null, locus.textGraph);
            }
            Map<String, Integer> frequencies =
                    synchronizeFrequencies(lexical, affected);
            text.commit();
            textCommitted = true;
            lexical.commit();
            return frequencies;
        } catch (RuntimeException e) {
            rollback(lexical);
            rollback(text);
            if (textCommitted) {
                compensateDeletedLoci(text, loci);
            }
            throw new ManagerException("ATTESTATION_DELETE_FAILED: "
                    + message(e), e);
        }
    }

    private void compensateDeletedLoci(RepositoryConnection connection,
                                       List<PendingLocusDeletion> loci) {
        try {
            connection.begin();
            for (PendingLocusDeletion locus : loci) {
                connection.add(locus.statements, locus.textGraph);
            }
            connection.commit();
        } catch (RuntimeException compensationFailure) {
            rollback(connection);
            throw compensationFailure;
        }
    }

    private AttestationDeletionResult deletionResult(String fileId,
            List<PendingDeletion> pending, List<PendingLocusDeletion> loci,
            Map<String, Integer> frequencies) {
        Set<String> deletedLoci = new HashSet<String>();
        for (PendingLocusDeletion locus : loci) {
            deletedLoci.add(locus.locus.stringValue());
        }
        AttestationDeletionResult result = new AttestationDeletionResult();
        result.fileId = fileId;
        for (PendingDeletion pendingItem : pending) {
            AttestationDeletionItem item = new AttestationDeletionItem();
            item.attestation = pendingItem.attestation.stringValue();
            item.observable = pendingItem.observables.isEmpty() ? null
                    : pendingItem.observables.get(0).stringValue();
            item.locus = pendingItem.locus.stringValue();
            result.deleted.add(item);
            if (!deletedLoci.contains(item.locus)
                    && !result.retainedLoci.contains(item.locus)) {
                result.retainedLoci.add(item.locus);
            }
        }
        result.deletedCount = result.deleted.size();
        result.deletedLoci.addAll(deletedLoci);
        Collections.sort(result.deletedLoci);
        Collections.sort(result.retainedLoci);
        result.frequencies.putAll(frequencies);
        return result;
    }

    private Map<String, FrequencyKey> deletionFrequencyKeys(
            RepositoryConnection lexical, RepositoryConnection text,
            Resource attestationGraph, Resource textGraph,
            List<PendingDeletion> pending) throws ManagerException {
        Map<String, FrequencyKey> result =
                new LinkedHashMap<String, FrequencyKey>();
        for (PendingDeletion item : pending) {
            IRI observedIn = observedText(lexical, text, item.attestation,
                    item.locus, attestationGraph, textGraph);
            for (IRI observable : item.observables) {
                addFrequencyKey(result, observable, observedIn, attestationGraph);
            }
        }
        return result;
    }

    private IRI observedText(RepositoryConnection lexical,
                             RepositoryConnection text, IRI attestation,
                             Resource attestationGraph, Resource textGraph)
            throws ManagerException {
        Value locus = firstObject(lexical, attestation,
                vf.createIRI(FRAC + "locus"), attestationGraph);
        return observedText(lexical, text, attestation,
                locus instanceof IRI ? (IRI) locus : null,
                attestationGraph, textGraph);
    }

    private IRI observedText(RepositoryConnection lexical,
                             RepositoryConnection text, IRI attestation,
                             IRI locus, Resource attestationGraph,
                             Resource textGraph) throws ManagerException {
        if (locus != null) {
            Value reference = firstObject(text, locus,
                    vf.createIRI(NIF + "referenceContext"), textGraph);
            if (reference instanceof IRI) {
                return (IRI) reference;
            }
        }
        Value fallback = firstObject(lexical, attestation,
                vf.createIRI(FRAC + "observedIn"), attestationGraph);
        if (fallback instanceof IRI) {
            return (IRI) fallback;
        }
        throw new ManagerException("ATTESTATION_TEXT_MISSING: cannot resolve the text observed by "
                + attestation.stringValue());
    }

    private void addFrequencyKey(Map<String, FrequencyKey> target,
                                 IRI observable, IRI observedIn,
                                 Resource graph) {
        FrequencyKey value = new FrequencyKey(observable, observedIn, graph);
        target.put(frequencyKey(observable, observedIn, graph), value);
    }

    private void addExistingFrequencyKeys(RepositoryConnection connection,
                                          Map<String, FrequencyKey> target,
                                          IRI observable, Resource graph) {
        IRI observedInRelation = vf.createIRI(FRAC + "observedIn");
        try (RepositoryResult<Statement> links = connection.getStatements(
                observable, vf.createIRI(FRAC + "frequency"), null, false,
                graph)) {
            while (links.hasNext()) {
                Value frequency = links.next().getObject();
                if (!(frequency instanceof Resource)) {
                    continue;
                }
                try (RepositoryResult<Statement> observations =
                             connection.getStatements((Resource) frequency,
                                     observedInRelation, null, false, graph)) {
                    while (observations.hasNext()) {
                        Value observedIn = observations.next().getObject();
                        if (observedIn instanceof IRI) {
                            addFrequencyKey(target, observable, (IRI) observedIn,
                                    graph);
                        }
                    }
                }
            }
        }
    }

    private Map<String, Integer> synchronizeFrequencies(
            RepositoryConnection connection,
            Map<String, FrequencyKey> affected) {
        Map<String, Integer> result = new LinkedHashMap<String, Integer>();
        for (FrequencyKey key : affected.values()) {
            int frequency = synchronizeFrequency(connection, key.observable,
                    key.observedIn, key.graph);
            result.put(key.observable.stringValue(), Integer.valueOf(frequency));
        }
        return result;
    }

    private int synchronizeFrequency(RepositoryConnection connection,
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

    private int incrementFrequency(RepositoryConnection connection,
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

    private void writeFrequency(RepositoryConnection connection, IRI observable,
                                IRI observedIn, Resource graph,
                                List<Resource> existing, int value) {
        Resource frequency = existing.isEmpty() ? vf.createBNode() : existing.get(0);
        if (existing.size() > 1) {
            removeFrequencyResources(connection, observable,
                    existing.subList(1, existing.size()), graph);
        }
        IRI relation = vf.createIRI(FRAC + "frequency");
        IRI observedInRelation = vf.createIRI(FRAC + "observedIn");
        connection.add(observable, relation, frequency, graph);
        connection.add(frequency, RDF.TYPE, vf.createIRI(FRAC + "Frequency"), graph);
        connection.remove(frequency, observedInRelation, null, graph);
        connection.add(frequency, observedInRelation, observedIn, graph);
        connection.remove(frequency, RDF.VALUE, null, graph);
        connection.add(frequency, RDF.VALUE,
                vf.createLiteral(Integer.toString(value), XSD.INT), graph);
    }

    private void removeFrequencyResources(RepositoryConnection connection,
                                          IRI observable,
                                          List<Resource> frequencies,
                                          Resource graph) {
        IRI relation = vf.createIRI(FRAC + "frequency");
        for (Resource frequency : frequencies) {
            connection.remove(observable, relation, frequency, graph);
            connection.remove(frequency, null, null, graph);
        }
    }

    private List<Resource> frequencyResources(RepositoryConnection connection,
                                              Resource observable,
                                              IRI observedIn, Resource graph) {
        List<Resource> result = new ArrayList<Resource>();
        IRI relation = vf.createIRI(FRAC + "frequency");
        IRI observedInRelation = vf.createIRI(FRAC + "observedIn");
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

    private Integer frequencyValue(RepositoryConnection connection,
                                   Resource observable, IRI observedIn,
                                   Resource graph) {
        List<Resource> frequencies = frequencyResources(connection, observable,
                observedIn, graph);
        return frequencies.size() == 1
                ? frequencyValue(connection, frequencies.get(0), graph) : null;
    }

    private Integer frequencyValue(RepositoryConnection connection,
                                   Resource frequency, Resource graph) {
        Literal value = firstLiteral(connection, frequency, RDF.VALUE, graph);
        if (value == null) {
            return null;
        }
        try {
            int parsed = value.intValue();
            return parsed < 0 ? null : Integer.valueOf(parsed);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int countObservableAttestations(RepositoryConnection connection,
                                            IRI observable, Resource graph) {
        Set<String> attestations = new HashSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                observable, vf.createIRI(FRAC + "attestation"), null, false,
                graph)) {
            while (statements.hasNext()) {
                Value candidate = statements.next().getObject();
                if (candidate instanceof Resource
                        && connection.hasStatement((Resource) candidate, RDF.TYPE,
                                vf.createIRI(FRAC + "Attestation"), false, graph)) {
                    attestations.add(candidate.stringValue());
                }
            }
        }
        return attestations.size();
    }

    private String frequencyKey(IRI observable, IRI observedIn, Resource graph) {
        return graph.stringValue() + "\n" + observable.stringValue() + "\n"
                + observedIn.stringValue();
    }

    private IRI iriUnchecked(String value) {
        return vf.createIRI(value);
    }

    private String timestamp(Timestamp value) {
        return new SimpleDateFormat(configured("manager.operationTimestampFormat",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")).format(value);
    }

    /** Atomically replaces selected metadata properties on multiple attestations. */
    public AttestationMetadataPatchResult patchMetadata(String fileIdValue,
                                                         AttestationMetadataBatch batch)
            throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        final IRI attestationGraph;
        try {
            attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(fileId));
        } catch (IllegalArgumentException e) {
            throw new ManagerException(
                    "INVALID_FILE_ID: fileId contains unsupported characters");
        }
        if (batch == null || batch.updates == null || batch.updates.isEmpty()) {
            throw new ManagerException(
                    "MISSING_METADATA_UPDATES: at least one metadata update is required");
        }

        RepositoryConnection lexical = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            List<ValidatedMetadataUpdate> updates = validateMetadataBatch(
                    lexical, attestationGraph, batch.updates);
            String modified = timestamp(new Timestamp(System.currentTimeMillis()));
            lexical.begin();
            for (ValidatedMetadataUpdate update : updates) {
                for (Map.Entry<IRI, List<Value>> property
                        : update.properties.entrySet()) {
                    lexical.remove(update.attestation, property.getKey(), null,
                            attestationGraph);
                    for (Value value : property.getValue()) {
                        lexical.add(update.attestation, property.getKey(), value,
                                attestationGraph);
                    }
                }
                lexical.remove(update.attestation, DCTERMS.MODIFIED, null,
                        attestationGraph);
                lexical.add(update.attestation, DCTERMS.MODIFIED,
                        vf.createLiteral(modified), attestationGraph);
            }
            lexical.commit();

            AttestationMetadataPatchResult result =
                    new AttestationMetadataPatchResult();
            result.fileId = fileId;
            for (ValidatedMetadataUpdate update : updates) {
                AttestationMetadataPatchItem item =
                        new AttestationMetadataPatchItem();
                item.attestation = update.attestation.stringValue();
                for (IRI property : update.properties.keySet()) {
                    item.properties.add(property.stringValue());
                }
                item.lastUpdate = modified;
                result.updated.add(item);
            }
            return result;
        } catch (ManagerException e) {
            rollback(lexical);
            throw e;
        } catch (RuntimeException e) {
            rollback(lexical);
            throw new ManagerException("ATTESTATION_METADATA_UPDATE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private List<ValidatedMetadataUpdate> validateMetadataBatch(
            RepositoryConnection connection, Resource graph,
            List<AttestationMetadataUpdate> requested) throws ManagerException {
        List<ValidatedMetadataUpdate> result =
                new ArrayList<ValidatedMetadataUpdate>();
        Set<String> attestations = new HashSet<String>();
        for (int updateIndex = 0; updateIndex < requested.size(); updateIndex++) {
            AttestationMetadataUpdate update = requested.get(updateIndex);
            if (update == null) {
                throw new ManagerException("INVALID_METADATA_UPDATE: updates["
                        + updateIndex + "] is null");
            }
            IRI attestation = iri("updates[" + updateIndex + "].attestation",
                    required("updates[" + updateIndex + "].attestation",
                            update.attestation));
            if (!attestations.add(attestation.stringValue())) {
                throw new ManagerException("DUPLICATE_ATTESTATION: "
                        + attestation.stringValue());
            }
            if (!connection.hasStatement(attestation, RDF.TYPE,
                    vf.createIRI(FRAC + "Attestation"), false, graph)) {
                throw new ManagerException("ATTESTATION_NOT_FOUND: "
                        + attestation.stringValue()
                        + " is not an attestation in the graph for fileId");
            }
            if (update.properties == null || update.properties.isEmpty()) {
                throw new ManagerException("MISSING_METADATA_PROPERTIES: updates["
                        + updateIndex + "].properties must not be empty");
            }

            LinkedHashMap<IRI, List<Value>> properties =
                    new LinkedHashMap<IRI, List<Value>>();
            Set<String> propertyIris = new HashSet<String>();
            for (int propertyIndex = 0; propertyIndex < update.properties.size();
                    propertyIndex++) {
                AttestationMetadataProperty property =
                        update.properties.get(propertyIndex);
                String field = "updates[" + updateIndex + "].properties["
                        + propertyIndex + "]";
                if (property == null) {
                    throw new ManagerException("INVALID_METADATA_PROPERTY: "
                            + field + " is null");
                }
                IRI predicate = iri(field + ".property",
                        required(field + ".property", property.property));
                if (RESERVED_METADATA_PROPERTIES.contains(predicate.stringValue())) {
                    throw new ManagerException("RESERVED_METADATA_PROPERTY: "
                            + predicate.stringValue());
                }
                if (!propertyIris.add(predicate.stringValue())) {
                    throw new ManagerException("DUPLICATE_METADATA_PROPERTY: "
                            + predicate.stringValue());
                }
                if (property.values == null) {
                    throw new ManagerException("MISSING_METADATA_VALUES: " + field
                            + ".values is required; use an empty list to remove it");
                }
                List<Value> values = new ArrayList<Value>();
                for (int valueIndex = 0; valueIndex < property.values.size();
                        valueIndex++) {
                    values.add(metadataValue(property.values.get(valueIndex),
                            field + ".values[" + valueIndex + "]"));
                }
                properties.put(predicate, values);
            }
            result.add(new ValidatedMetadataUpdate(attestation, properties));
        }
        return result;
    }

    private Value metadataValue(AttestationMetadataValue item, String field)
            throws ManagerException {
        if (item == null) {
            throw new ManagerException("INVALID_METADATA_VALUE: " + field
                    + " is null");
        }
        String type = required(field + ".type", item.type)
                .toLowerCase(java.util.Locale.ROOT);
        if ("iri".equals(type)) {
            if (!blank(item.language) || !blank(item.datatype)) {
                throw new ManagerException("INVALID_METADATA_VALUE: " + field
                        + " cannot define language or datatype for an IRI");
            }
            return iri(field + ".value", required(field + ".value", item.value));
        }
        if (!"literal".equals(type)) {
            throw new ManagerException("INVALID_METADATA_VALUE: " + field
                    + ".type must be literal or iri");
        }
        if (item.value == null) {
            throw new ManagerException("MISSING_PARAMETER: " + field
                    + ".value is required");
        }
        if (!blank(item.language) && !blank(item.datatype)) {
            throw new ManagerException("INVALID_METADATA_VALUE: " + field
                    + " cannot define both language and datatype");
        }
        if (!blank(item.language)) {
            String language = item.language.trim();
            if (!language.matches("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*")) {
                throw new ManagerException("INVALID_METADATA_LANGUAGE: " + field
                        + ".language is not a valid language tag");
            }
            return vf.createLiteral(item.value, language);
        }
        if (!blank(item.datatype)) {
            return vf.createLiteral(item.value,
                    iri(field + ".datatype", item.datatype.trim()));
        }
        return vf.createLiteral(item.value);
    }

    /** Backward-compatible entry point for the original text filters. */
    public AttestationPage list(String fileIdValue, String observableTypeValue,
                                String authorValue, String limitValue,
                                String offsetValue) throws ManagerException {
        return list(fileIdValue, observableTypeValue, authorValue, null,
                limitValue, offsetValue);
    }

    /** Returns one filtered page of attestations and enriches it with NIF locus data. */
    public AttestationPage list(String fileIdValue, String observableTypeValue,
                                String authorValue, AttestationFilter filter,
                                String limitValue, String offsetValue)
            throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        final IRI attestationGraph;
        try {
            attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(fileId));
        } catch (IllegalArgumentException e) {
            throw new ManagerException("INVALID_FILE_ID: fileId contains unsupported characters");
        }
        AttestationFilter effectiveFilter = combineLegacyFilters(filter,
                observableTypeValue, authorValue);
        validateFilter(effectiveFilter);
        int limit = paginationInteger("limit", limitValue, DEFAULT_PAGE_SIZE, false);
        int offset = paginationInteger("offset", offsetValue, 0, true);

        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            IRI lexicalGraph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
            IRI textGraph = vf.createIRI(textGraphBase + "documents/" + fileId);
            List<AttestationMatch> matches = new ArrayList<AttestationMatch>();
            Map<String, Resource> textSubjects = new HashMap<String, Resource>();
            try (RepositoryResult<Statement> statements = lexical.getStatements(null,
                    RDF.TYPE, vf.createIRI(FRAC + "Attestation"), false,
                    attestationGraph)) {
                while (statements.hasNext()) {
                    Resource resource = statements.next().getSubject();
                    List<Resource> observables = observablesForAttestation(lexical,
                            resource, attestationGraph);
                    if (!matchesFilter(effectiveFilter, lexical, text, resource,
                            observables, attestationGraph, lexicalGraph, textGraph,
                            fileId, textSubjects)) {
                        continue;
                    }
                    matches.add(new AttestationMatch(resource,
                            preferredObservable(lexical, observables, lexicalGraph,
                                    effectiveFilter), attestationGraph, fileId));
                }
            }
            return page(lexical, text, matches, lexicalGraph, limit, offset);
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_LIST_FAILED: " + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    /** Returns attestations of one observable across all per-text graphs. */
    public AttestationPage listByObservable(String observableValue,
                                            AttestationFilter filter,
                                            String limitValue, String offsetValue)
            throws ManagerException {
        IRI observable = iri("observable", required("observable", observableValue));
        validateFilter(filter);
        int limit = paginationInteger("limit", limitValue, DEFAULT_PAGE_SIZE, false);
        int offset = paginationInteger("offset", offsetValue, 0, true);
        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            validateObservable(lexical, observable);
            IRI lexicalGraph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
            IRI attestationRelation = vf.createIRI(FRAC + "attestation");
            String graphBase = LexicalNamedGraphs.attestationGraphBaseUri();
            List<AttestationMatch> matches = new ArrayList<AttestationMatch>();
            Map<String, Resource> textSubjects = new HashMap<String, Resource>();
            try (RepositoryResult<Statement> statements = lexical.getStatements(
                    observable, attestationRelation, null, false)) {
                while (statements.hasNext()) {
                    Statement statement = statements.next();
                    Resource graph = statement.getContext();
                    Value object = statement.getObject();
                    if (graph == null || !(object instanceof Resource)
                            || !graph.stringValue().startsWith(graphBase)) {
                        continue;
                    }
                    String fileId = attestationFileId(graph, graphBase);
                    if (fileId == null || !lexical.hasStatement((Resource) object,
                            RDF.TYPE, vf.createIRI(FRAC + "Attestation"), false, graph)) {
                        continue;
                    }
                    Resource textGraph = vf.createIRI(textGraphBase + "documents/"
                            + fileId);
                    List<Resource> observables = Collections.<Resource>singletonList(
                            observable);
                    if (matchesFilter(filter, lexical, text, (Resource) object,
                            observables, graph, lexicalGraph, textGraph, fileId,
                            textSubjects)) {
                        matches.add(new AttestationMatch((Resource) object, observable,
                                graph, fileId));
                    }
                }
            }
            return page(lexical, text, matches, lexicalGraph, limit, offset);
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_LIST_FAILED: " + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private String attestationFileId(Resource graph, String graphBase) {
        String graphValue = graph.stringValue();
        if (!graphValue.startsWith(graphBase)) {
            return null;
        }
        String fileId = graphValue.substring(graphBase.length());
        try {
            return graphValue.equals(LexicalNamedGraphs.attestationGraphUri(fileId))
                    ? fileId : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AttestationPage page(RepositoryConnection lexical,
                                 RepositoryConnection text,
                                 List<AttestationMatch> matches,
                                 Resource lexicalGraph, int limit, int offset) {
        Collections.sort(matches, new Comparator<AttestationMatch>() {
            @Override
            public int compare(AttestationMatch left, AttestationMatch right) {
                int attestation = left.attestation.stringValue().compareTo(
                        right.attestation.stringValue());
                return attestation != 0 ? attestation
                        : left.graph.stringValue().compareTo(right.graph.stringValue());
            }
        });
        AttestationPage page = new AttestationPage();
        page.totalHits = matches.size();
        page.limit = limit;
        page.offset = offset;
        if (offset >= matches.size()) {
            return page;
        }
        int end = (int) Math.min((long) matches.size(),
                (long) offset + (long) limit);
        Map<String, String> observableLabels = new HashMap<String, String>();
        for (AttestationMatch match : matches.subList(offset, end)) {
            Resource textGraph = vf.createIRI(textGraphBase + "documents/"
                    + match.fileId);
            page.list.add(readAttestation(lexical, text, match.attestation,
                    match.observable, match.graph, lexicalGraph, textGraph,
                    match.fileId, observableLabels));
        }
        return page;
    }

    private List<Resource> observablesForAttestation(RepositoryConnection connection,
                                                     Resource attestation,
                                                     Resource attestationGraph) {
        List<Resource> result = new ArrayList<Resource>();
        try (RepositoryResult<Statement> statements = connection.getStatements(null,
                vf.createIRI(FRAC + "attestation"), attestation, false,
                attestationGraph)) {
            while (statements.hasNext()) {
                Resource candidate = statements.next().getSubject();
                if (!result.contains(candidate)) {
                    result.add(candidate);
                }
            }
        }
        Collections.sort(result, new Comparator<Resource>() {
            @Override
            public int compare(Resource left, Resource right) {
                return left.stringValue().compareTo(right.stringValue());
            }
        });
        return result;
    }

    private AttestationFilter combineLegacyFilters(AttestationFilter filter,
                                                    String observableType,
                                                    String author) {
        List<AttestationFilter> filters = new ArrayList<AttestationFilter>();
        if (filter != null) {
            filters.add(filter);
        }
        if (!blank(observableType)) {
            filters.add(stringFilter("observableType", observableType.trim()));
        }
        if (!blank(author)) {
            filters.add(stringFilter("creator", author));
        }
        if (filters.isEmpty()) {
            return null;
        }
        if (filters.size() == 1) {
            return filters.get(0);
        }
        AttestationFilter result = new AttestationFilter();
        result.operator = "AND";
        result.filters = filters;
        return result;
    }

    private AttestationFilter stringFilter(String field, String value) {
        AttestationFilter result = new AttestationFilter();
        result.operator = "IN";
        result.field = field;
        result.values = Collections.singletonList(value);
        return result;
    }

    private void validateFilter(AttestationFilter filter) throws ManagerException {
        int[] nodes = {0};
        validateFilter(filter, 1, nodes, "filter");
    }

    private void validateFilter(AttestationFilter filter, int depth, int[] nodes,
                                String path) throws ManagerException {
        if (filter == null) {
            return;
        }
        nodes[0]++;
        if (nodes[0] > MAX_FILTER_NODES || depth > MAX_FILTER_DEPTH) {
            throw new ManagerException("FILTER_TOO_COMPLEX: filter supports at most "
                    + MAX_FILTER_NODES + " nodes and depth " + MAX_FILTER_DEPTH);
        }
        String operator = required(path + ".operator", filter.operator)
                .toUpperCase(java.util.Locale.ROOT);
        if ("AND".equals(operator) || "OR".equals(operator)) {
            if (!blank(filter.field) || filter.filters == null
                    || filter.filters.isEmpty()) {
                throw new ManagerException("INVALID_FILTER: " + path
                        + " group requires filters and cannot define field");
            }
            for (int index = 0; index < filter.filters.size(); index++) {
                if (filter.filters.get(index) == null) {
                    throw new ManagerException("INVALID_FILTER: " + path
                            + ".filters[" + index + "] cannot be null");
                }
                validateFilter(filter.filters.get(index), depth + 1, nodes,
                        path + ".filters[" + index + "]");
            }
            return;
        }
        String field = required(path + ".field", filter.field);
        if ("creator".equalsIgnoreCase(field)
                || "observableType".equalsIgnoreCase(field)) {
            if (!("IN".equals(operator) || "EQ".equals(operator))
                    || filter.values == null || filter.values.isEmpty()) {
                throw new ManagerException("INVALID_FILTER: " + path + " " + field
                        + " requires operator IN or EQ and at least one value");
            }
            for (int index = 0; index < filter.values.size(); index++) {
                String value = required(path + ".values[" + index + "]",
                        filter.values.get(index));
                if ("observableType".equalsIgnoreCase(field)) {
                    iri(path + ".values[" + index + "]", value);
                }
            }
            return;
        }
        if (!"textMetadata".equalsIgnoreCase(field)) {
            throw new ManagerException("INVALID_FILTER_FIELD: " + path
                    + ".field must be creator, textMetadata, or observableType");
        }
        iri(path + ".property", required(path + ".property", filter.property));
        if ("EXISTS".equals(operator)) {
            return;
        }
        if (!("EQ".equals(operator) || "IN".equals(operator))
                || filter.rdfValues == null || filter.rdfValues.isEmpty()) {
            throw new ManagerException("INVALID_FILTER: " + path
                    + " textMetadata requires EXISTS or at least one rdfValue");
        }
        for (int index = 0; index < filter.rdfValues.size(); index++) {
            metadataValue(filter.rdfValues.get(index), path + ".rdfValues["
                    + index + "]");
        }
    }

    private boolean matchesFilter(AttestationFilter filter,
                                  RepositoryConnection lexical,
                                  RepositoryConnection text,
                                  Resource attestation,
                                  List<Resource> observables,
                                  Resource attestationGraph,
                                  Resource lexicalGraph,
                                  Resource textGraph,
                                  String fileId,
                                  Map<String, Resource> textSubjects)
            throws ManagerException {
        if (filter == null) {
            return true;
        }
        String operator = filter.operator.toUpperCase(java.util.Locale.ROOT);
        if ("AND".equals(operator)) {
            for (AttestationFilter child : filter.filters) {
                if (!matchesFilter(child, lexical, text, attestation, observables,
                        attestationGraph, lexicalGraph, textGraph, fileId,
                        textSubjects)) {
                    return false;
                }
            }
            return true;
        }
        if ("OR".equals(operator)) {
            for (AttestationFilter child : filter.filters) {
                if (matchesFilter(child, lexical, text, attestation, observables,
                        attestationGraph, lexicalGraph, textGraph, fileId,
                        textSubjects)) {
                    return true;
                }
            }
            return false;
        }
        if ("creator".equalsIgnoreCase(filter.field)) {
            for (String value : filter.values) {
                if (hasStringValue(lexical, attestation, DCTERMS.CREATOR,
                        value, attestationGraph)) {
                    return true;
                }
            }
            return false;
        }
        if ("observableType".equalsIgnoreCase(filter.field)) {
            for (Resource observable : observables) {
                for (String value : filter.values) {
                    if (hasObservableType(lexical, observable, vf.createIRI(value),
                            lexicalGraph)) {
                        return true;
                    }
                }
            }
            return false;
        }
        return matchesTextMetadata(filter, lexical, text, attestation,
                attestationGraph, textGraph, fileId, textSubjects);
    }

    private boolean matchesTextMetadata(AttestationFilter filter,
                                        RepositoryConnection lexical,
                                        RepositoryConnection text,
                                        Resource attestation,
                                        Resource attestationGraph,
                                        Resource textGraph,
                                        String fileId,
                                        Map<String, Resource> textSubjects)
            throws ManagerException {
        IRI property = vf.createIRI(filter.property);
        List<Resource> subjects = new ArrayList<Resource>();
        Value observedIn = firstObject(lexical, attestation,
                vf.createIRI(FRAC + "observedIn"), attestationGraph);
        if (observedIn instanceof Resource) {
            subjects.add((Resource) observedIn);
        }
        Resource textSubject = textSubject(text, fileId, textGraph, textSubjects);
        if (textSubject != null && !subjects.contains(textSubject)) {
            subjects.add(textSubject);
        }
        if ("EXISTS".equalsIgnoreCase(filter.operator)) {
            for (Resource subject : subjects) {
                if (text.hasStatement(subject, property, null, false, textGraph)) {
                    return true;
                }
            }
            return false;
        }
        for (AttestationMetadataValue item : filter.rdfValues) {
            Value expected = metadataValue(item, "filter.rdfValues");
            for (Resource subject : subjects) {
                if (text.hasStatement(subject, property, expected, false, textGraph)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Resource textSubject(RepositoryConnection text, String fileId,
                                 Resource textGraph,
                                 Map<String, Resource> textSubjects) {
        if (textSubjects.containsKey(fileId)) {
            return textSubjects.get(fileId);
        }
        Resource result = null;
        try (RepositoryResult<Statement> statements = text.getStatements(null,
                vf.createIRI(structureNamespace + "fileId"), null, false,
                textGraph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                if (fileId.equals(statement.getObject().stringValue())) {
                    result = statement.getSubject();
                    break;
                }
            }
        }
        textSubjects.put(fileId, result);
        return result;
    }

    private Resource preferredObservable(RepositoryConnection lexical,
                                         List<Resource> observables,
                                         Resource lexicalGraph,
                                         AttestationFilter filter) {
        if (observables.isEmpty()) {
            return null;
        }
        List<String> requestedTypes = new ArrayList<String>();
        collectObservableTypes(filter, requestedTypes);
        for (Resource observable : observables) {
            for (String type : requestedTypes) {
                if (hasObservableType(lexical, observable, vf.createIRI(type),
                        lexicalGraph)) {
                    return observable;
                }
            }
        }
        return observables.get(0);
    }

    private void collectObservableTypes(AttestationFilter filter,
                                        List<String> result) {
        if (filter == null) {
            return;
        }
        if (filter.filters != null) {
            for (AttestationFilter child : filter.filters) {
                collectObservableTypes(child, result);
            }
        } else if ("observableType".equalsIgnoreCase(filter.field)
                && filter.values != null) {
            result.addAll(filter.values);
        }
    }

    private boolean hasObservableType(RepositoryConnection connection,
                                      Resource observable, IRI requestedType,
                                      Resource lexicalGraph) {
        if (connection.hasStatement(observable, RDF.TYPE, requestedType, true,
                lexicalGraph)) {
            return true;
        }
        try (RepositoryResult<Statement> statements = connection.getStatements(
                observable, RDF.TYPE, null, true, lexicalGraph)) {
            while (statements.hasNext()) {
                Value type = statements.next().getObject();
                if (type instanceof IRI && isSubclassOf(connection, (IRI) type,
                        requestedType, new HashSet<String>())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSubclassOf(RepositoryConnection connection, IRI candidate,
                                 IRI requestedType, Set<String> visited) {
        if (candidate.equals(requestedType)) {
            return true;
        }
        if (!visited.add(candidate.stringValue())) {
            return false;
        }
        Resource lexicalGraph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
        Resource schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
        try (RepositoryResult<Statement> statements = connection.getStatements(
                candidate, vf.createIRI(RDFS + "subClassOf"), null, true,
                lexicalGraph, schemaGraph)) {
            while (statements.hasNext()) {
                Value parent = statements.next().getObject();
                if (parent instanceof IRI && isSubclassOf(connection, (IRI) parent,
                        requestedType, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private AttestationListItem readAttestation(RepositoryConnection lexical,
                                                RepositoryConnection text,
                                                Resource attestation,
                                                Resource observable,
                                                Resource attestationGraph,
                                                Resource lexicalGraph,
                                                Resource textGraph, String fileId,
                                                Map<String, String> observableLabels) {
        AttestationListItem result = new AttestationListItem();
        result.attestation = attestation.stringValue();
        result.fileId = fileId;
        result.external = Boolean.valueOf(fileId.startsWith("external-"));
        result.observable = observable == null ? null : observable.stringValue();
        result.observableLabel = NO_LABEL;
        if (observable != null) {
            result.observableTypes = rdfTypes(lexical, observable, lexicalGraph);
            String key = observable.stringValue();
            if (!observableLabels.containsKey(key)) {
                observableLabels.put(key, observableLabel(lexical, observable,
                        lexicalGraph));
            }
            result.observableLabel = observableLabels.get(key);
        }
        result.creator = firstString(lexical, attestation, DCTERMS.CREATOR,
                attestationGraph);
        result.creationDate = firstString(lexical, attestation, DCTERMS.CREATED,
                attestationGraph);
        result.lastUpdate = firstString(lexical, attestation, DCTERMS.MODIFIED,
                attestationGraph);
        result.metadata = readMetadata(lexical, attestation, attestationGraph);
        result.value = firstString(lexical, attestation, RDF.VALUE, attestationGraph);
        if (result.value == null) {
            result.value = firstString(lexical, attestation,
                    vf.createIRI(FRAC + "gloss"), attestationGraph);
        }
        Value corpus = firstObject(lexical, attestation,
                vf.createIRI(FRAC + "observedIn"), attestationGraph);
        result.corpus = corpus == null ? null : corpus.stringValue();
        Value locus = firstObject(lexical, attestation,
                vf.createIRI(FRAC + "locus"), attestationGraph);
        result.locus = locus == null ? null : locus.stringValue();
        if (locus instanceof Resource) {
            Resource locusResource = (Resource) locus;
            result.locusTypes = rdfTypes(text, locusResource, textGraph);
            Literal anchor = firstLiteral(text, locusResource,
                    vf.createIRI(NIF + "anchorOf"), textGraph);
            if (anchor != null) {
                result.value = anchor.getLabel();
                result.language = anchor.getLanguage().orElse(null);
            }
            result.start = firstInteger(text, locusResource,
                    vf.createIRI(NIF + "beginIndex"), textGraph);
            result.end = firstInteger(text, locusResource,
                    vf.createIRI(NIF + "endIndex"), textGraph);
            Value reference = firstObject(text, locusResource,
                    vf.createIRI(NIF + "referenceContext"), textGraph);
            result.referenceContext = reference == null ? null : reference.stringValue();
        }
        if (observable != null) {
            Value frequencyObservedIn = blank(result.referenceContext)
                    ? corpus : vf.createIRI(result.referenceContext);
            if (frequencyObservedIn instanceof IRI) {
                result.frequency = frequencyValue(lexical, observable,
                        (IRI) frequencyObservedIn, attestationGraph);
            }
        }
        return result;
    }

    private Map<String, List<AttestationMetadataValue>> readMetadata(
            RepositoryConnection connection, Resource attestation, Resource graph) {
        Map<String, List<AttestationMetadataValue>> unsorted =
                new HashMap<String, List<AttestationMetadataValue>>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                attestation, null, null, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                String property = statement.getPredicate().stringValue();
                if (RESERVED_METADATA_PROPERTIES.contains(property)) {
                    continue;
                }
                AttestationMetadataValue value =
                        outputMetadataValue(statement.getObject());
                if (value == null) {
                    continue;
                }
                List<AttestationMetadataValue> values = unsorted.get(property);
                if (values == null) {
                    values = new ArrayList<AttestationMetadataValue>();
                    unsorted.put(property, values);
                }
                values.add(value);
            }
        }
        List<String> properties = new ArrayList<String>(unsorted.keySet());
        Collections.sort(properties);
        Map<String, List<AttestationMetadataValue>> result =
                new LinkedHashMap<String, List<AttestationMetadataValue>>();
        for (String property : properties) {
            List<AttestationMetadataValue> values = unsorted.get(property);
            Collections.sort(values, new Comparator<AttestationMetadataValue>() {
                @Override
                public int compare(AttestationMetadataValue left,
                                   AttestationMetadataValue right) {
                    return metadataSortKey(left).compareTo(metadataSortKey(right));
                }
            });
            result.put(property, values);
        }
        return result;
    }

    private AttestationMetadataValue outputMetadataValue(Value value) {
        AttestationMetadataValue result = new AttestationMetadataValue();
        result.value = value.stringValue();
        if (value instanceof IRI) {
            result.type = "iri";
            return result;
        }
        if (!(value instanceof Literal)) {
            return null;
        }
        result.type = "literal";
        Literal literal = (Literal) value;
        result.value = literal.getLabel();
        result.language = literal.getLanguage().orElse(null);
        if (result.language == null
                && !XSD.STRING.equals(literal.getDatatype())) {
            result.datatype = literal.getDatatype().stringValue();
        }
        return result;
    }

    private static String metadataSortKey(AttestationMetadataValue value) {
        return value.type + "|" + value.value + "|"
                + (value.language == null ? "" : value.language) + "|"
                + (value.datatype == null ? "" : value.datatype);
    }

    private String observableLabel(RepositoryConnection connection,
                                   Resource observable, Resource lexicalGraph) {
        if (isLexicalEntry(connection, observable, lexicalGraph)) {
            return firstNonBlank(
                    firstLiteralWithLanguage(connection, observable,
                            vf.createIRI(RDFS + "label"), lexicalGraph),
                    canonicalWrittenRep(connection, observable, lexicalGraph),
                    NO_LABEL);
        }
        if (hasType(connection, observable, "Form", lexicalGraph)) {
            return firstNonBlank(
                    firstLiteralWithLanguage(connection, observable,
                            vf.createIRI(ONTOLEX + "writtenRep"), lexicalGraph),
                    firstLiteralWithLanguage(connection, observable,
                            vf.createIRI(RDFS + "label"), lexicalGraph),
                    NO_LABEL);
        }
        if (hasType(connection, observable, "LexicalSense", lexicalGraph)) {
            String definition = firstLiteralWithLanguage(connection, observable,
                    vf.createIRI(SKOS + "definition"), lexicalGraph);
            if (blank(definition)) {
                return NO_LABEL;
            }
            Value entryValue = firstObject(connection, observable,
                    vf.createIRI(ONTOLEX + "isSenseOf"), lexicalGraph);
            String entryLabel = null;
            if (entryValue instanceof Resource) {
                Resource entry = (Resource) entryValue;
                entryLabel = firstNonBlank(
                        firstLiteralWithLanguage(connection, entry,
                                vf.createIRI(RDFS + "label"), lexicalGraph),
                        canonicalWrittenRep(connection, entry, lexicalGraph), null);
            }
            return blank(entryLabel) ? definition : entryLabel + " - " + definition;
        }
        if (hasType(connection, observable, "LexicalConcept", lexicalGraph)) {
            return firstNonBlank(
                    firstLiteralWithLanguage(connection, observable,
                            vf.createIRI(SKOS + "prefLabel"), lexicalGraph),
                    firstLiteralWithLanguage(connection, observable,
                            vf.createIRI(RDFS + "label"), lexicalGraph),
                    NO_LABEL);
        }
        return NO_LABEL;
    }

    private String canonicalWrittenRep(RepositoryConnection connection,
                                       Resource entry, Resource lexicalGraph) {
        Value form = firstObject(connection, entry,
                vf.createIRI(ONTOLEX + "canonicalForm"), lexicalGraph);
        if (!(form instanceof Resource)) {
            return null;
        }
        return firstLiteralWithLanguage(connection, (Resource) form,
                vf.createIRI(ONTOLEX + "writtenRep"), lexicalGraph);
    }

    private String firstLiteralWithLanguage(RepositoryConnection connection,
                                            Resource subject, IRI predicate,
                                            Resource graph) {
        Literal literal = firstLiteral(connection, subject, predicate, graph);
        if (literal == null) {
            return null;
        }
        String language = literal.getLanguage().orElse(null);
        return blank(language) ? literal.getLabel()
                : literal.getLabel() + "@" + language;
    }

    private boolean hasType(RepositoryConnection connection, Resource observable,
                            String localType, Resource lexicalGraph) {
        return connection.hasStatement(observable, RDF.TYPE,
                vf.createIRI(ONTOLEX + localType), true, lexicalGraph);
    }

    private boolean isLexicalEntry(RepositoryConnection connection,
                                   Resource observable, Resource lexicalGraph) {
        IRI lexicalEntry = vf.createIRI(ONTOLEX + "LexicalEntry");
        if (connection.hasStatement(observable, RDF.TYPE, lexicalEntry, true,
                lexicalGraph)) {
            return true;
        }
        try (RepositoryResult<Statement> statements = connection.getStatements(
                observable, RDF.TYPE, null, false, lexicalGraph)) {
            while (statements.hasNext()) {
                Value type = statements.next().getObject();
                if (type instanceof IRI && isSubclassOf(connection, (IRI) type,
                        lexicalEntry, lexicalGraph, new HashSet<String>())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSubclassOf(RepositoryConnection connection, IRI candidate,
                                 IRI expected, Resource lexicalGraph,
                                 Set<String> visited) {
        if (candidate.equals(expected)) {
            return true;
        }
        if (!visited.add(candidate.stringValue())) {
            return false;
        }
        IRI schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
        try (RepositoryResult<Statement> statements = connection.getStatements(
                candidate, vf.createIRI(RDFS + "subClassOf"), null, true,
                lexicalGraph, schemaGraph)) {
            while (statements.hasNext()) {
                Value parent = statements.next().getObject();
                if (parent instanceof IRI && isSubclassOf(connection, (IRI) parent,
                        expected, lexicalGraph, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String firstNonBlank(String first, String second,
                                        String fallback) {
        if (!blank(first)) {
            return first;
        }
        return blank(second) ? fallback : second;
    }

    private List<String> rdfTypes(RepositoryConnection connection, Resource subject,
                                  Resource graph) {
        List<String> types = new ArrayList<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, RDF.TYPE, null, true, graph)) {
            while (statements.hasNext()) {
                Value type = statements.next().getObject();
                if (type instanceof IRI && !types.contains(type.stringValue())) {
                    types.add(type.stringValue());
                }
            }
        }
        Collections.sort(types);
        return types;
    }

    private boolean hasStringValue(RepositoryConnection connection, Resource subject,
                                   IRI predicate, String expected, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                if (expected.equals(statements.next().getObject().stringValue())) {
                    return true;
                }
            }
            return false;
        }
    }

    private String firstString(RepositoryConnection connection, Resource subject,
                               IRI predicate, Resource graph) {
        Value value = firstObject(connection, subject, predicate, graph);
        return value == null ? null : value.stringValue();
    }

    private Value firstObject(RepositoryConnection connection, Resource subject,
                              IRI predicate, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            return statements.hasNext() ? statements.next().getObject() : null;
        }
    }

    private Integer firstInteger(RepositoryConnection connection, Resource subject,
                                 IRI predicate, Resource graph) {
        Value value = firstObject(connection, subject, predicate, graph);
        if (!(value instanceof Literal)) {
            return null;
        }
        try {
            return Integer.valueOf(((Literal) value).intValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int paginationInteger(String field, String value, int fallback,
                                  boolean zeroAllowed) throws ManagerException {
        if (blank(value)) {
            return fallback;
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ManagerException("INVALID_INTEGER: " + field + " must be an integer");
        }
        if (parsed < 0 || (!zeroAllowed && parsed == 0)) {
            throw new ManagerException("INVALID_PAGINATION: " + field
                    + (zeroAllowed ? " must be zero or greater" : " must be greater than zero"));
        }
        return parsed;
    }

    private void validateObservable(RepositoryConnection connection, IRI observable)
            throws ManagerException {
        IRI graph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
        String[] types = {"LexicalEntry", "Form", "LexicalSense", "LexicalConcept"};
        for (String type : types) {
            if (hasObservableType(connection, observable,
                    vf.createIRI(ONTOLEX + type), graph)) {
                return;
            }
        }
        throw new ManagerException("INVALID_OBSERVABLE: observable must identify an "
                + "ontolex:LexicalEntry, ontolex:Form, ontolex:LexicalSense, "
                + "or ontolex:LexicalConcept in the lexical graph");
    }

    private TextLocation internalLocation(RepositoryConnection connection, IRI corpus,
                                          String value, int start, int end)
            throws ManagerException {
        List<Resource> contexts = namedContexts(connection, corpus, RDF.TYPE);
        boolean allowedType = false;
        ManagerException validationFailure = null;
        for (Resource graph : contexts) {
            if (hasAllowedCorpusType(connection, corpus, graph)) {
                allowedType = true;
                try {
                    TextLocation location = locationInGraph(connection, corpus, graph,
                            value, start, end);
                    if (location != null) {
                        return location;
                    }
                } catch (ManagerException e) {
                    validationFailure = e;
                }
            }
        }
        if (!allowedType) {
            throw new ManagerException("INVALID_CORPUS: corpus must identify a "
                    + "dcmitype:Collection, dcmitype:Dataset, or dcmitype:Text in a LexOTexts named graph");
        }
        if (validationFailure != null) {
            throw validationFailure;
        }
        throw new ManagerException("INVALID_LOCUS: corpus has no matching nif:Context "
                + "with a fileId and canonical nif:isString in the same named graph");
    }

    private TextLocation locationInGraph(RepositoryConnection connection, IRI corpus,
                                         Resource graph, String value, int start, int end)
            throws ManagerException {
        List<IRI> candidates = new ArrayList<IRI>();
        candidates.add(corpus);
        try (RepositoryResult<Statement> members = connection.getStatements(corpus,
                DCTERMS.HAS_PART, null, false, graph)) {
            while (members.hasNext()) {
                Value member = members.next().getObject();
                if (member instanceof IRI) {
                    candidates.add((IRI) member);
                }
            }
        }
        try (RepositoryResult<Statement> derivations = connection.getStatements(null,
                vf.createIRI(PROV + "wasDerivedFrom"), corpus, false, graph)) {
            while (derivations.hasNext()) {
                Resource derived = derivations.next().getSubject();
                if (derived instanceof IRI && !candidates.contains(derived)) {
                    candidates.add((IRI) derived);
                }
            }
        }
        ManagerException validationFailure = null;
        for (IRI context : candidates) {
            for (Resource contextGraph : namedContexts(connection, context, RDF.TYPE)) {
                if (!connection.hasStatement(context, RDF.TYPE,
                        vf.createIRI(NIF + "Context"), true, contextGraph)) {
                    continue;
                }
                Literal canonical = firstLiteral(connection, context,
                        vf.createIRI(NIF + "isString"), contextGraph);
                Literal fileId = firstLiteral(connection, context,
                        vf.createIRI(structureNamespace + "fileId"), contextGraph);
                if (canonical == null || fileId == null) {
                    continue;
                }
                try {
                    validateAnchor(canonical.getLabel(), value, start, end);
                    Value languageValue = firstObject(connection, context,
                            DCTERMS.LANGUAGE, contextGraph);
                    String language = languageValue instanceof Literal
                            ? ((Literal) languageValue).getLabel().trim() : null;
                    if (blank(language)) {
                        language = null;
                    }
                    return new TextLocation(fileId.getLabel(),
                            phraseUri(context, start, end), contextGraph, context,
                            language);
                } catch (ManagerException e) {
                    validationFailure = e;
                }
            }
        }
        if (validationFailure != null) {
            throw validationFailure;
        }
        return null;
    }

    private TextLocation externalLocation(IRI corpus, int start, int end) {
        String fileId = "external-" + sha256(corpus.stringValue());
        return new TextLocation(fileId, phraseUri(corpus, start, end),
                vf.createIRI(textGraphBase + "documents/" + fileId), corpus, null);
    }

    private void validateAnchor(String canonical, String value, int start, int end)
            throws ManagerException {
        int length = canonical.codePointCount(0, canonical.length());
        if (end > length) {
            throw new ManagerException("INVALID_OFFSETS: end exceeds the canonical text length");
        }
        int beginIndex = canonical.offsetByCodePoints(0, start);
        int endIndex = canonical.offsetByCodePoints(0, end);
        if (!canonical.substring(beginIndex, endIndex).equals(value)) {
            throw new ManagerException("VALUE_MISMATCH: value does not match nif:isString "
                    + "between the supplied Unicode code-point offsets");
        }
    }

    private Model phraseModel(IRI locus, IRI referenceContext, String value,
                              int start, int end, String language) {
        Model model = new LinkedHashModel();
        model.add(locus, RDF.TYPE, vf.createIRI(NIF + "Phrase"));
        model.add(locus, RDF.TYPE, vf.createIRI(NIF + "RFC5147String"));
        model.add(locus, vf.createIRI(PROV + "wasGeneratedBy"),
                attestationServiceIri());
        model.add(locus, vf.createIRI(NIF + "anchorOf"), language == null
                ? vf.createLiteral(value) : vf.createLiteral(value, language));
        model.add(locus, vf.createIRI(NIF + "beginIndex"),
                vf.createLiteral(Integer.toString(start), XSD.NON_NEGATIVE_INTEGER));
        model.add(locus, vf.createIRI(NIF + "endIndex"),
                vf.createLiteral(Integer.toString(end), XSD.NON_NEGATIVE_INTEGER));
        model.add(locus, vf.createIRI(NIF + "referenceContext"), referenceContext);
        return model;
    }

    private IRI attestationServiceIri() {
        return vf.createIRI(namespace(configured("repository.lexicon.namespace",
                "https://lexo.ilc.cnr.it#")) + ATTESTATION_SERVICE);
    }

    private Model attestationModel(IRI attestation, IRI observable, IRI locus,
                                   IRI corpus, String value,
                                   String language, String author, String timestamp) {
        Model model = new LinkedHashModel();
        model.add(attestation, RDF.TYPE, vf.createIRI(FRAC + "Attestation"));
        model.add(attestation, DCTERMS.CREATOR,
                vf.createLiteral(author == null ? "anonymous" : author));
        model.add(attestation, DCTERMS.CREATED, vf.createLiteral(timestamp));
        model.add(attestation, DCTERMS.MODIFIED, vf.createLiteral(timestamp));
        Literal attestedValue = blank(language)
                ? vf.createLiteral(value) : vf.createLiteral(value, language);
        model.add(attestation, vf.createIRI(FRAC + "gloss"), attestedValue);
        model.add(attestation, RDF.VALUE, attestedValue);
        model.add(attestation, vf.createIRI(FRAC + "locus"), locus);
        model.add(attestation, vf.createIRI(FRAC + "observedIn"), corpus);
        model.add(observable, vf.createIRI(FRAC + "attestation"), attestation);
        return model;
    }

    private void persistBatch(RepositoryConnection lexical,
                              RepositoryConnection text,
                              List<PendingAttestation> pending)
            throws ManagerException {
        boolean textCommitted = false;
        try {
            lexical.begin();
            text.begin();
            for (PendingAttestation item : pending) {
                lexical.add(item.attestationStatements, item.attestationGraph);
                text.add(item.phraseStatements, item.textGraph);
            }
            Map<String, List<PendingAttestation>> frequencyGroups =
                    new LinkedHashMap<String, List<PendingAttestation>>();
            for (PendingAttestation item : pending) {
                String key = frequencyKey(iriUnchecked(item.result.observable),
                        iriUnchecked(item.result.referenceContext),
                        item.attestationGraph);
                List<PendingAttestation> group = frequencyGroups.get(key);
                if (group == null) {
                    group = new ArrayList<PendingAttestation>();
                    frequencyGroups.put(key, group);
                }
                group.add(item);
            }
            for (List<PendingAttestation> group : frequencyGroups.values()) {
                PendingAttestation sample = group.get(0);
                int frequency = incrementFrequency(lexical,
                        iriUnchecked(sample.result.observable),
                        iriUnchecked(sample.result.referenceContext),
                        sample.attestationGraph, group.size());
                for (PendingAttestation item : group) {
                    item.result.frequency = Integer.valueOf(frequency);
                }
            }
            text.commit();
            textCommitted = true;
            lexical.commit();
        } catch (RuntimeException e) {
            rollback(lexical);
            rollback(text);
            if (textCommitted) {
                compensate(text, pending);
            }
            throw new ManagerException("ATTESTATION_CREATE_FAILED: " + message(e), e);
        }
    }

    private Model newStatements(RepositoryConnection connection, Model expected,
                                Resource graph, IRI locus) throws ManagerException {
        boolean locusExists = connection.hasStatement(locus, null, null, false, graph);
        Model missing = new LinkedHashModel();
        for (Statement statement : expected) {
            if (!connection.hasStatement(statement.getSubject(), statement.getPredicate(),
                    statement.getObject(), false, graph)) {
                missing.add(statement);
            }
        }
        if (!locusExists) {
            return missing;
        }
        validateExistingLocus(connection, expected, graph, locus);
        return new LinkedHashModel();
    }

    private void validateExistingLocus(RepositoryConnection connection,
                                       Model expected, Resource graph, IRI locus)
            throws ManagerException {
        IRI[] identityPredicates = {
            vf.createIRI(NIF + "anchorOf"),
            vf.createIRI(NIF + "beginIndex"),
            vf.createIRI(NIF + "endIndex"),
            vf.createIRI(NIF + "referenceContext")
        };
        for (IRI predicate : identityPredicates) {
            Set<Value> expectedValues = new HashSet<Value>(
                    expected.filter(locus, predicate, null).objects());
            Set<Value> actualValues = new HashSet<Value>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    locus, predicate, null, false, graph)) {
                while (statements.hasNext()) {
                    actualValues.add(statements.next().getObject());
                }
            }
            if (!actualValues.equals(expectedValues)) {
                throw new ManagerException("LOCUS_CONFLICT: the NIF locus already exists with different data");
            }
        }
    }

    private List<String> resultLocusTypes(RepositoryConnection connection,
                                          IRI locus, Resource graph) {
        List<String> types = rdfTypes(connection, locus, graph);
        if (types.isEmpty()) {
            types.add(NIF + "Phrase");
            types.add(NIF + "RFC5147String");
        }
        return types;
    }

    private void compensate(RepositoryConnection connection,
                            List<PendingAttestation> pending) {
        try {
            connection.begin();
            for (PendingAttestation item : pending) {
                connection.remove(item.phraseStatements, item.textGraph);
            }
            connection.commit();
        } catch (RuntimeException compensationFailure) {
            rollback(connection);
            throw compensationFailure;
        }
    }

    private IRI newAttestationIri(RepositoryConnection connection,
                                  Timestamp timestamp, Set<String> reserved)
            throws ManagerException {
        String namespace = configured("repository.lexicon.namespace",
                "https://lexo.ilc.cnr.it#");
        String prefix = configured("repository.instance.id", "LexO_");
        String local = (prefix + timestamp.toString()).replaceAll("\\s+", "")
                .replace(':', '_').replace('.', '_');
        IRI iri = vf.createIRI(namespace(namespace) + local);
        if (reserved.contains(iri.stringValue())
                || connection.hasStatement(iri, null, null, false)) {
            throw new ManagerException("ATTESTATION_ID_CONFLICT: generated attestation IRI already exists");
        }
        reserved.add(iri.stringValue());
        return iri;
    }

    private boolean hasAllowedCorpusType(RepositoryConnection connection, IRI corpus,
                                         Resource graph) {
        String[] types = {"Collection", "Dataset", "Text"};
        for (String type : types) {
            if (connection.hasStatement(corpus, RDF.TYPE,
                    vf.createIRI(DCMITYPE + type), true, graph)) {
                return true;
            }
        }
        return false;
    }

    private List<Resource> namedContexts(RepositoryConnection connection,
                                         Resource subject, IRI predicate) {
        List<Resource> contexts = new ArrayList<Resource>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false)) {
            while (statements.hasNext()) {
                Resource context = statements.next().getContext();
                if (context != null && !contexts.contains(context)) {
                    contexts.add(context);
                }
            }
        }
        return contexts;
    }

    private IRI uniqueIriObject(RepositoryConnection connection, Resource subject,
                                IRI predicate, Resource graph,
                                String missingCode, String ambiguousCode)
            throws ManagerException {
        IRI result = null;
        int count = 0;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                count++;
                if (!(value instanceof IRI)) {
                    throw new ManagerException(ambiguousCode + ": "
                            + predicate.stringValue()
                            + " must have exactly one IRI value");
                }
                result = (IRI) value;
            }
        }
        if (count == 0) {
            throw new ManagerException(missingCode + ": "
                    + predicate.stringValue() + " is required");
        }
        if (count != 1) {
            throw new ManagerException(ambiguousCode + ": "
                    + predicate.stringValue()
                    + " must have exactly one IRI value");
        }
        return result;
    }

    private void ensureModifiableLocus(RepositoryConnection lexical,
                                       RepositoryConnection text,
                                       IRI attestation, IRI locus,
                                       Resource attestationGraph,
                                       Resource textGraph)
            throws ManagerException {
        if (!text.hasStatement(locus, vf.createIRI(PROV + "wasGeneratedBy"),
                attestationServiceIri(), false, textGraph)) {
            throw new ManagerException("LOCUS_NOT_MODIFIABLE: the locus was not "
                    + "generated by LexO AttestationService");
        }
        String graphBase = LexicalNamedGraphs.attestationGraphBaseUri();
        try (RepositoryResult<Statement> statements = lexical.getStatements(null,
                vf.createIRI(FRAC + "locus"), locus, false)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                Resource graph = statement.getContext();
                if (graph != null && graph.stringValue().startsWith(graphBase)
                        && (!attestation.equals(statement.getSubject())
                        || !attestationGraph.equals(graph))) {
                    throw new ManagerException("LOCUS_NOT_MODIFIABLE: the locus "
                            + "is shared by another attestation");
                }
            }
        }
    }

    private Model statementsForSubject(RepositoryConnection connection,
                                       Resource subject, Resource graph) {
        Model result = new LinkedHashModel();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, null, null, false, graph)) {
            while (statements.hasNext()) {
                result.add(statements.next());
            }
        }
        return result;
    }

    private Model movedLocusStatements(Model existing, IRI newLocus,
                                       IRI referenceContext,
                                       String value, int start, int end,
                                       String language) {
        Model result = new LinkedHashModel();
        Set<String> replaced = new HashSet<String>();
        replaced.add(NIF + "anchorOf");
        replaced.add(NIF + "beginIndex");
        replaced.add(NIF + "endIndex");
        replaced.add(NIF + "referenceContext");
        for (Statement statement : existing) {
            if (!replaced.contains(statement.getPredicate().stringValue())) {
                result.add(newLocus, statement.getPredicate(),
                        statement.getObject());
            }
        }
        if (!result.contains(newLocus, RDF.TYPE, vf.createIRI(NIF + "Phrase"))) {
            result.add(newLocus, RDF.TYPE, vf.createIRI(NIF + "Phrase"));
        }
        if (!result.contains(newLocus, RDF.TYPE,
                vf.createIRI(NIF + "RFC5147String"))) {
            result.add(newLocus, RDF.TYPE, vf.createIRI(NIF + "RFC5147String"));
        }
        Literal anchor = blank(language) ? vf.createLiteral(value)
                : vf.createLiteral(value, language);
        result.add(newLocus, vf.createIRI(NIF + "anchorOf"), anchor);
        result.add(newLocus, vf.createIRI(NIF + "beginIndex"),
                vf.createLiteral(Integer.toString(start),
                        XSD.NON_NEGATIVE_INTEGER));
        result.add(newLocus, vf.createIRI(NIF + "endIndex"),
                vf.createLiteral(Integer.toString(end),
                        XSD.NON_NEGATIVE_INTEGER));
        result.add(newLocus, vf.createIRI(NIF + "referenceContext"),
                referenceContext);
        return result;
    }

    private String unicodeSubstring(String canonical, int start, int end)
            throws ManagerException {
        int length = canonical.codePointCount(0, canonical.length());
        if (end > length) {
            throw new ManagerException(
                    "INVALID_OFFSETS: end exceeds the canonical text length");
        }
        int beginIndex = canonical.offsetByCodePoints(0, start);
        int endIndex = canonical.offsetByCodePoints(0, end);
        return canonical.substring(beginIndex, endIndex);
    }

    private void compensateLocusUpdate(RepositoryConnection connection,
                                       Model oldStatements, IRI newLocus,
                                       Resource textGraph) {
        try {
            connection.begin();
            connection.remove(newLocus, null, null, textGraph);
            connection.add(oldStatements, textGraph);
            connection.commit();
        } catch (RuntimeException compensationFailure) {
            rollback(connection);
            throw compensationFailure;
        }
    }

    private Literal firstLiteral(RepositoryConnection connection, Resource subject,
                                 IRI predicate, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            if (!statements.hasNext()) {
                return null;
            }
            Value value = statements.next().getObject();
            return value instanceof Literal ? (Literal) value : null;
        }
    }

    private IRI iri(String field, String value) throws ManagerException {
        try {
            URI parsed = new URI(value);
            if (!parsed.isAbsolute() || parsed.getScheme() == null) {
                throw new URISyntaxException(value, "IRI must be absolute");
            }
            return vf.createIRI(value);
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw new ManagerException("INVALID_IRI: " + field + " must be a valid absolute IRI");
        }
    }

    private IRI externalUrl(String value) throws ManagerException {
        IRI result = iri("corpus", value);
        URI parsed = URI.create(value);
        String scheme = parsed.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new ManagerException("INVALID_EXTERNAL_URL: corpus must be an HTTP or HTTPS URL");
        }
        if (blank(parsed.getRawAuthority())) {
            throw new ManagerException("INVALID_EXTERNAL_URL: corpus URL must include a host");
        }
        return result;
    }

    private int integer(String field, String value) throws ManagerException {
        String required = required(field, value);
        try {
            return Integer.parseInt(required);
        } catch (NumberFormatException e) {
            throw new ManagerException("INVALID_INTEGER: " + field + " must be an integer");
        }
    }

    private String required(String field, String value) throws ManagerException {
        if (blank(value)) {
            throw new ManagerException("MISSING_PARAMETER: " + field + " is required");
        }
        return value.trim();
    }

    private String requiredValue(String field, String value) throws ManagerException {
        if (value == null || value.isEmpty()) {
            throw new ManagerException("MISSING_PARAMETER: " + field + " is required");
        }
        return value;
    }

    private static String phraseUri(IRI context, int start, int end) {
        String value = context.stringValue();
        int fragment = value.indexOf('#');
        String base = fragment < 0 ? value : value.substring(0, fragment);
        return base + "#char=" + start + "," + end;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void rollback(RepositoryConnection connection) {
        if (connection != null && connection.isActive()) {
            connection.rollback();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Set<String> reservedMetadataProperties() {
        Set<String> result = new HashSet<String>();
        result.add(RDF.TYPE.stringValue());
        result.add(RDF.VALUE.stringValue());
        result.add(DCTERMS.CREATOR.stringValue());
        result.add(DCTERMS.CREATED.stringValue());
        result.add(DCTERMS.MODIFIED.stringValue());
        result.add(DCTERMS.DESCRIPTION.stringValue());
        result.add(FRAC + "attestation");
        result.add(FRAC + "gloss");
        result.add(FRAC + "locus");
        result.add(FRAC + "observedIn");
        return Collections.unmodifiableSet(result);
    }

    private static String namespace(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "#";
    }

    private static String trailingSeparator(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "/";
    }

    private static String configured(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        return blank(value) || value.contains("${") ? fallback : value.trim();
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static final class TextLocation {
        final String fileId;
        final String locus;
        final Resource textGraph;
        final IRI referenceContext;
        final String language;

        TextLocation(String fileId, String locus, Resource textGraph,
                     IRI referenceContext, String language) {
            this.fileId = fileId;
            this.locus = locus;
            this.textGraph = textGraph;
            this.referenceContext = referenceContext;
            this.language = language;
        }
    }

    private static final class ValidatedMetadataUpdate {
        final IRI attestation;
        final LinkedHashMap<IRI, List<Value>> properties;

        ValidatedMetadataUpdate(IRI attestation,
                                LinkedHashMap<IRI, List<Value>> properties) {
            this.attestation = attestation;
            this.properties = properties;
        }
    }

    private static final class ValidatedObservableUpdate {
        final IRI attestation;
        final List<IRI> previousObservables;

        ValidatedObservableUpdate(IRI attestation,
                                  List<IRI> previousObservables) {
            this.attestation = attestation;
            this.previousObservables = previousObservables;
        }
    }

    private static final class PendingAttestation {
        final Resource attestationGraph;
        final Resource textGraph;
        final Model attestationStatements;
        final Model phraseStatements;
        final Attestation result;

        PendingAttestation(Resource attestationGraph, Resource textGraph,
                           Model attestationStatements, Model phraseStatements,
                           Attestation result) {
            this.attestationGraph = attestationGraph;
            this.textGraph = textGraph;
            this.attestationStatements = attestationStatements;
            this.phraseStatements = phraseStatements;
            this.result = result;
        }
    }

    private static final class PendingDeletion {
        final IRI attestation;
        final List<IRI> observables;
        final IRI locus;

        PendingDeletion(IRI attestation, List<IRI> observables, IRI locus) {
            this.attestation = attestation;
            this.observables = observables;
            this.locus = locus;
        }
    }

    private static final class FrequencyKey {
        final IRI observable;
        final IRI observedIn;
        final Resource graph;

        FrequencyKey(IRI observable, IRI observedIn, Resource graph) {
            this.observable = observable;
            this.observedIn = observedIn;
            this.graph = graph;
        }
    }

    private static final class PendingLocusDeletion {
        final IRI locus;
        final Resource textGraph;
        final Model statements;

        PendingLocusDeletion(IRI locus, Resource textGraph, Model statements) {
            this.locus = locus;
            this.textGraph = textGraph;
            this.statements = statements;
        }
    }

    private static final class AttestationMatch {
        final Resource attestation;
        final Resource observable;
        final Resource graph;
        final String fileId;

        AttestationMatch(Resource attestation, Resource observable,
                         Resource graph, String fileId) {
            this.attestation = attestation;
            this.observable = observable;
            this.graph = graph;
            this.fileId = fileId;
        }
    }

    private interface ConnectionSource {
        RepositoryConnection acquire(RepositoryTarget target);
        void release(RepositoryTarget target, RepositoryConnection connection);
    }

    private static final class GraphDbConnectionSource implements ConnectionSource {
        @Override
        public RepositoryConnection acquire(RepositoryTarget target) {
            return GraphDbUtil.getConnection(target);
        }

        @Override
        public void release(RepositoryTarget target, RepositoryConnection connection) {
            GraphDbUtil.releaseConnection(target, connection);
        }
    }

    private static final class RepositoryConnectionSource implements ConnectionSource {
        private final Repository lexical;
        private final Repository text;

        RepositoryConnectionSource(Repository lexical, Repository text) {
            this.lexical = lexical;
            this.text = text;
        }

        @Override
        public RepositoryConnection acquire(RepositoryTarget target) {
            return (target == RepositoryTarget.LEXICON ? lexical : text).getConnection();
        }

        @Override
        public void release(RepositoryTarget target, RepositoryConnection connection) {
            if (connection != null) {
                connection.close();
            }
        }
    }
}
