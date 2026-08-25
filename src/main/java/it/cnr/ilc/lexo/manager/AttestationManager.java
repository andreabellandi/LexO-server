package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.metadata.MetadataPolicy;
import it.cnr.ilc.lexo.manager.metadata.RdfMetadataCodec;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.manager.text.model.JsonTextImport;
import it.cnr.ilc.lexo.service.data.attestation.AttestationMetadataValue;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationByLocusObservableInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByObservableInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationFilter;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationLocusUpdate;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationObservableUpdate;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationOccurrence;
import it.cnr.ilc.lexo.service.data.attestation.output.Attestation;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationDeletionItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationDeletionResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationListItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationLocusUpdateResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationObservableUpdateItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationObservableUpdateResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationPage;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
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
import java.util.function.LongSupplier;
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
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.rio.helpers.NTriplesUtil;

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
    private static final LongSupplier SYSTEM_CURRENT_TIME = new LongSupplier() {
        @Override
        public long getAsLong() {
            return System.currentTimeMillis();
        }
    };
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final RdfMetadataCodec metadataCodec = new RdfMetadataCodec();
    private final ConnectionSource connections;
    private final LongSupplier currentTimeMillis;
    private final String structureNamespace;
    private final String textGraphBase;
    private long lastAttestationEpochMillis = Long.MIN_VALUE;

    public AttestationManager() {
        this(new GraphDbConnectionSource(), SYSTEM_CURRENT_TIME);
    }

    AttestationManager(Repository lexicalRepository, Repository textRepository) {
        this(lexicalRepository, textRepository, SYSTEM_CURRENT_TIME);
    }

    AttestationManager(Repository lexicalRepository, Repository textRepository,
                       LongSupplier currentTimeMillis) {
        this(new RepositoryConnectionSource(lexicalRepository, textRepository),
                currentTimeMillis);
    }

    private AttestationManager(ConnectionSource connections,
                               LongSupplier currentTimeMillis) {
        this.connections = connections;
        this.currentTimeMillis = currentTimeMillis;
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
            Resource lexicalGraph = resolveObservableGraph(lexical, observableIri);
            List<String> observableTypes = rdfTypes(lexical, observableIri,
                    lexicalGraph);
            String observableLabel = observableLabel(lexical, observableIri,
                    lexicalGraph);
            List<PendingAttestation> pending = new ArrayList<PendingAttestation>();
            Map<String, Model> batchLoci = new HashMap<String, Model>();
            Set<String> reservedAttestationIris = new HashSet<String>();
            long batchTimestamp = currentTimeMillis.getAsLong();

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

                AttestationIdentity identity = newAttestationIdentity(lexical,
                        new Timestamp(batchTimestamp + index),
                        reservedAttestationIris);
                String timestamp = timestamp(identity.timestamp);
                IRI attestationIri = identity.iri;
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
            long batchTimestamp = currentTimeMillis.getAsLong();

            for (int index = 0; index < input.observables.size(); index++) {
                String path = "observables[" + index + "]";
                AttestationByLocusObservableInput observableInput =
                        input.observables.get(index);
                if (observableInput == null) {
                    throw new ManagerException("INVALID_OBSERVABLE: " + path
                            + " must be an object");
                }
                String observable = required(path + ".observable",
                        observableInput.observable);
                IRI observableIri = iri(path + ".observable", observable);
                Resource lexicalGraph = resolveObservableGraph(lexical, observableIri);
                LinkedHashMap<IRI, List<Value>> metadata =
                        new LinkedHashMap<IRI, List<Value>>();
                if (observableInput.metadata != null) {
                    metadata = metadataCodec.decodeProperties(
                            observableInput.metadata, false);
                }
                List<String> observableTypes = rdfTypes(lexical, observableIri,
                        lexicalGraph);
                String observableLabel = observableLabel(lexical, observableIri,
                        lexicalGraph);
                AttestationIdentity identity = newAttestationIdentity(lexical,
                        new Timestamp(batchTimestamp + index),
                        reservedAttestationIris);
                String created = timestamp(identity.timestamp);
                IRI attestationIri = identity.iri;
                Model attestationStatements = attestationModel(attestationIri,
                        observableIri, locus, corpusIri, value, location.language,
                        author, created);
                addMetadata(attestationStatements, attestationIri, metadata);
                Attestation result = attestationResult(attestationIri, observable,
                        observableLabel, observableTypes, value, start, end, corpus,
                        location, external, author, created, locusTypes);
                result.metadata = outputMetadata(metadata);
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
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_CREATE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    /**
     * Creates one attestation supplied by the strict JSON text import. Unlike
     * the interactive API, the declared observable type is mandatory and the
     * lookup is restricted to the upload language graph (or the fixed lexical
     * concept graph). Each invocation is an independent transaction so an
     * invalid imported attestation does not prevent later items from being
     * attempted.
     */
    public synchronized Attestation createImported(String expectedFileIdValue,
                                                    String evidenceValue,
                                                    String languageValue,
                                                    JsonTextImport.AttestationInput input)
            throws ManagerException {
        String expectedFileId = required("fileId", expectedFileIdValue);
        String evidence = required("evidence", evidenceValue);
        String language = Iso639LanguageValidator.get().requireValid(languageValue);
        if (input == null) {
            throw new ManagerException(
                    "INVALID_IMPORTED_ATTESTATION: attestation must be an object");
        }
        String observable = required("observable", input.observable);
        String observableType = required("type", input.type);
        String value = requiredValue("value", input.value);
        String gloss = requiredValue("gloss", input.gloss);
        if (input.start == null || input.end == null) {
            throw new ManagerException(
                    "MISSING_PARAMETER: start_char and end_char are required");
        }
        int start = input.start.intValue();
        int end = input.end.intValue();
        validateLocusOffsets(start, end);

        IRI observableIri = iri("observable", observable);
        IRI declaredType = iri("type", observableType);
        IRI evidenceIri = iri("evidence", evidence);
        String localType = importedObservableType(declaredType);
        IRI expectedLexicalGraph = vf.createIRI("LexicalConcept".equals(localType)
                ? LexiconCrudSupport.lexicalConceptGraphUri()
                : LexiconCrudSupport.lexicalGraphUri(language));

        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            if (!lexical.hasStatement(observableIri, null, null, false,
                    expectedLexicalGraph)) {
                throw new ManagerException("OBSERVABLE_NOT_FOUND: " + observable
                        + " does not exist in " + expectedLexicalGraph.stringValue());
            }
            if (!lexical.hasStatement(observableIri, RDF.TYPE, declaredType, false,
                    expectedLexicalGraph)) {
                throw new ManagerException("OBSERVABLE_TYPE_MISMATCH: " + observable
                        + " does not have rdf:type " + observableType + " in "
                        + expectedLexicalGraph.stringValue());
            }

            TextLocation location = internalLocation(text, evidenceIri, value,
                    start, end);
            if (!expectedFileId.equals(location.fileId)) {
                throw new ManagerException("TEXT_FILE_MISMATCH: the resolved locus belongs to "
                        + location.fileId + " instead of " + expectedFileId);
            }
            if (blank(location.language)
                    || !language.equalsIgnoreCase(location.language)) {
                throw new ManagerException("TEXT_LANGUAGE_MISMATCH: the resolved text language "
                        + location.language + " does not match " + language);
            }

            LinkedHashMap<IRI, List<Value>> metadata =
                    new LinkedHashMap<IRI, List<Value>>();
            if (input.metadata != null && !input.metadata.isEmpty()) {
                metadata = metadataCodec.decodeProperties(input.metadata, false,
                        "metadata");
            }

            IRI locus = vf.createIRI(location.locus);
            IRI attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(location.fileId));
            Model phraseStatements = newStatements(text,
                    phraseModel(locus, location.referenceContext, value, start, end,
                            location.language), location.textGraph, locus);
            AttestationIdentity identity = newAttestationIdentity(lexical,
                    new Timestamp(currentTimeMillis.getAsLong()),
                    new HashSet<String>());
            String created = timestamp(identity.timestamp);
            Model attestationStatements = attestationModel(identity.iri,
                    observableIri, locus, evidenceIri, value, gloss,
                    location.language, "imported", created);
            addMetadata(attestationStatements, identity.iri, metadata);

            List<String> observableTypes = rdfTypes(lexical, observableIri,
                    expectedLexicalGraph);
            String observableLabel = observableLabel(lexical, observableIri,
                    expectedLexicalGraph);
            List<String> locusTypes = resultLocusTypes(text, locus,
                    location.textGraph);
            Attestation result = attestationResult(identity.iri, observable,
                    observableLabel, observableTypes, value, start, end, evidence,
                    location, false, "imported", created, locusTypes);
            result.metadata = outputMetadata(metadata);
            List<PendingAttestation> pending =
                    new ArrayList<PendingAttestation>();
            pending.add(new PendingAttestation(attestationGraph,
                    location.textGraph, attestationStatements,
                    phraseStatements, result));
            persistBatch(lexical, text, pending);
            return result;
        } catch (ManagerException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_CREATE_FAILED: "
                    + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private String importedObservableType(IRI declaredType)
            throws ManagerException {
        String value = declaredType.stringValue();
        String[] supported = {"LexicalEntry", "Form", "LexicalSense",
            "LexicalConcept"};
        for (String local : supported) {
            if ((ONTOLEX + local).equals(value)) {
                return local;
            }
        }
        throw new ManagerException("INVALID_OBSERVABLE_TYPE: type must be one of "
                + "ontolex:LexicalEntry, ontolex:Form, ontolex:LexicalSense, "
                + "or ontolex:LexicalConcept");
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

    /** Relinks one attestation to a validated NIF locus at new offsets. */
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
        Model removedOldLocusStatements = new LinkedHashModel();
        Model addedNewLocusStatements = new LinkedHashModel();
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
            Literal oldAnchor = firstLiteral(text, oldLocus,
                    vf.createIRI(NIF + "anchorOf"), textGraph);
            String language = oldAnchor != null && oldAnchor.getLanguage().isPresent()
                    ? oldAnchor.getLanguage().get()
                    : canonical.getLanguage().orElse(null);
            Model expectedNewLocus = phraseModel(newLocus, referenceContext,
                    value, start, end, language);
            addedNewLocusStatements = newStatements(text, expectedNewLocus,
                    textGraph, newLocus);
            if (!newLocus.equals(oldLocus)
                    && isGeneratedLocus(text, oldLocus, textGraph)
                    && !hasRemainingAttestation(lexical, oldLocus,
                            Collections.singleton(attestation.stringValue()))) {
                removedOldLocusStatements = statementsForSubject(text, oldLocus,
                        textGraph);
            }
            String modified = timestamp(new Timestamp(System.currentTimeMillis()));
            Literal attestedValue = blank(language) ? vf.createLiteral(value)
                    : vf.createLiteral(value, language);

            lexical.begin();
            text.begin();
            if (!removedOldLocusStatements.isEmpty()) {
                text.remove(oldLocus, null, null, textGraph);
            }
            text.add(addedNewLocusStatements, textGraph);
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
            if (textCommitted) {
                compensateLocusRelink(text, removedOldLocusStatements,
                        addedNewLocusStatements, textGraph);
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
            resolveObservableGraph(lexical, observable);
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
        return AttestationFrequencySupport.synchronize(connection, observable,
                observedIn, graph);
    }

    private int incrementFrequency(RepositoryConnection connection,
                                   IRI observable, IRI observedIn,
                                   Resource graph, int increment) {
        return AttestationFrequencySupport.increment(connection, observable,
                observedIn, graph, increment);
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

    private Value metadataValue(RdfMetadataValue item, String field)
            throws ManagerException {
        try {
            return metadataCodec.decode(item, field);
        } catch (IllegalArgumentException e) {
            throw new ManagerException(e.getMessage(), e);
        }
    }

    /** Backward-compatible entry point for the original text filters. */
    public AttestationPage list(String fileIdValue, String observableTypeValue,
                                String authorValue, String limitValue,
                                String offsetValue) throws ManagerException {
        return list(fileIdValue, null, observableTypeValue, authorValue, null,
                limitValue, offsetValue);
    }

    /** Returns one filtered page of attestations and enriches it with NIF locus data. */
    public AttestationPage list(String fileIdValue, String observableTypeValue,
                                String authorValue, AttestationFilter filter,
                                String limitValue, String offsetValue)
            throws ManagerException {
        return list(fileIdValue, null, observableTypeValue, authorValue, filter,
                limitValue, offsetValue);
    }

    /** Returns one filtered page, optionally restricted to one exact observable. */
    public AttestationPage list(String fileIdValue, String observableValue,
                                String observableTypeValue, String authorValue,
                                AttestationFilter filter, String limitValue,
                                String offsetValue) throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        IRI requestedObservable = blank(observableValue) ? null
                : iri("observable", observableValue.trim());
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
            IRI textGraph = vf.createIRI(textGraphBase + "documents/" + fileId);
            List<AttestationMatch> matches = new ArrayList<AttestationMatch>();
            Map<String, Resource> textSubjects = new HashMap<String, Resource>();
            Map<String, Resource> observableGraphs = new HashMap<String, Resource>();
            Resource requestedObservableGraph = null;
            if (requestedObservable != null) {
                requestedObservableGraph = resolveObservableGraph(lexical,
                        requestedObservable);
                observableGraphs.put(requestedObservable.stringValue(),
                        requestedObservableGraph);
            }
            if (effectiveFilter == null) {
                return queryPage(lexical, text, attestationGraph, fileId,
                        requestedObservable, requestedObservableGraph,
                        observableGraphs, limit, offset);
            }
            if (requestedObservable != null) {
                IRI relation = vf.createIRI(FRAC + "attestation");
                try (RepositoryResult<Statement> statements = lexical.getStatements(
                        requestedObservable, relation, null, false,
                        attestationGraph)) {
                    while (statements.hasNext()) {
                        Value value = statements.next().getObject();
                        if (!(value instanceof Resource)
                                || !lexical.hasStatement((Resource) value,
                                        RDF.TYPE, vf.createIRI(FRAC + "Attestation"),
                                        false, attestationGraph)) {
                            continue;
                        }
                        List<Resource> observables =
                                Collections.<Resource>singletonList(
                                        requestedObservable);
                        if (matchesFilter(effectiveFilter, lexical, text,
                                (Resource) value, observables, attestationGraph,
                                textGraph, fileId, textSubjects,
                                observableGraphs)) {
                            matches.add(new AttestationMatch((Resource) value,
                                    requestedObservable, attestationGraph, fileId,
                                    requestedObservableGraph));
                        }
                    }
                }
                return page(lexical, text, matches, limit, offset);
            }
            try (RepositoryResult<Statement> statements = lexical.getStatements(null,
                    RDF.TYPE, vf.createIRI(FRAC + "Attestation"), false,
                    attestationGraph)) {
                while (statements.hasNext()) {
                    Resource resource = statements.next().getSubject();
                    List<Resource> observables = observablesForAttestation(lexical,
                            resource, attestationGraph);
                    if (requestedObservable != null
                            && !observables.contains(requestedObservable)) {
                        continue;
                    }
                    List<Resource> filteredObservables = requestedObservable == null
                            ? observables : Collections.<Resource>singletonList(
                                    requestedObservable);
                    if (!matchesFilter(effectiveFilter, lexical, text, resource,
                            filteredObservables, attestationGraph, textGraph, fileId,
                            textSubjects, observableGraphs)) {
                        continue;
                    }
                    Resource preferred = requestedObservable == null
                            ? preferredObservable(lexical, observables,
                                    effectiveFilter, observableGraphs)
                            : requestedObservable;
                    matches.add(new AttestationMatch(resource, preferred,
                            attestationGraph, fileId,
                            requestedObservable == null
                                    ? observableGraph(lexical, preferred,
                                            observableGraphs)
                                    : requestedObservableGraph));
                }
            }
            return page(lexical, text, matches, limit, offset);
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_LIST_FAILED: " + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private AttestationPage queryPage(RepositoryConnection lexical,
                                      RepositoryConnection text,
                                      IRI attestationGraph, String fileId,
                                      IRI requestedObservable,
                                      Resource requestedObservableGraph,
                                      Map<String, Resource> observableGraphs,
                                      int limit, int offset)
            throws ManagerException {
        int totalHits = countPageCandidates(lexical, attestationGraph,
                requestedObservable);
        AttestationPage page = new AttestationPage();
        page.totalHits = totalHits;
        page.limit = limit;
        page.offset = offset;
        if (offset >= totalHits) {
            return page;
        }
        List<AttestationMatch> matches = selectPageCandidates(lexical,
                attestationGraph, fileId, requestedObservable,
                requestedObservableGraph, observableGraphs, limit, offset);
        return fillPage(lexical, text, matches, page);
    }

    private int countPageCandidates(RepositoryConnection connection,
                                    Resource graph, IRI requestedObservable)
            throws ManagerException {
        StringBuilder sparql = new StringBuilder();
        sparql.append("SELECT (COUNT(DISTINCT ?attestation) AS ?totalHits) WHERE { ")
                .append("GRAPH ?attestationGraph { ")
                .append("?attestation ?typePredicate ?attestationType . ");
        if (requestedObservable != null) {
            sparql.append("?requestedObservable ?relation ?attestation . ");
        }
        sparql.append("} }");
        TupleQuery query = connection.prepareTupleQuery(QueryLanguage.SPARQL,
                sparql.toString());
        bindAttestationSelection(query, graph, requestedObservable);
        query.setIncludeInferred(false);
        try (TupleQueryResult result = query.evaluate()) {
            if (!result.hasNext()) {
                return 0;
            }
            Value total = result.next().getValue("totalHits");
            if (!(total instanceof Literal)) {
                return 0;
            }
            return ((Literal) total).intValue();
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_COUNT_FAILED: " + message(e), e);
        }
    }

    private List<AttestationMatch> selectPageCandidates(
            RepositoryConnection connection, Resource graph, String fileId,
            IRI requestedObservable, Resource requestedObservableGraph,
            Map<String, Resource> observableGraphs, int limit, int offset)
            throws ManagerException {
        StringBuilder sparql = new StringBuilder();
        if (requestedObservable == null) {
            sparql.append("SELECT ?attestation (MIN(STR(?candidate)) AS ?observableValue) ");
        } else {
            sparql.append("SELECT ?attestation ");
        }
        sparql.append("WHERE { GRAPH ?attestationGraph { ")
                .append("?attestation ?typePredicate ?attestationType . ");
        if (requestedObservable == null) {
            sparql.append("OPTIONAL { ?candidate ?relation ?attestation . } ");
        } else {
            sparql.append("?requestedObservable ?relation ?attestation . ");
        }
        sparql.append("} } ");
        if (requestedObservable == null) {
            sparql.append("GROUP BY ?attestation ");
        }
        sparql.append("ORDER BY STR(?attestation) LIMIT ")
                .append(limit).append(" OFFSET ").append(offset);
        TupleQuery query = connection.prepareTupleQuery(QueryLanguage.SPARQL,
                sparql.toString());
        bindAttestationSelection(query, graph, requestedObservable);
        query.setIncludeInferred(false);
        List<PageCandidate> candidates = new ArrayList<PageCandidate>();
        try (TupleQueryResult result = query.evaluate()) {
            while (result.hasNext()) {
                BindingSet row = result.next();
                Value attestationValue = row.getValue("attestation");
                if (!(attestationValue instanceof Resource)) {
                    continue;
                }
                Resource observable = requestedObservable;
                if (observable == null) {
                    Value observableValue = row.getValue("observableValue");
                    if (observableValue instanceof Literal
                            && !blank(observableValue.stringValue())) {
                        observable = vf.createIRI(observableValue.stringValue());
                    }
                }
                candidates.add(new PageCandidate((Resource) attestationValue,
                        observable));
            }
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_PAGE_FAILED: " + message(e), e);
        }
        if (requestedObservable == null) {
            List<Resource> observables = new ArrayList<Resource>();
            for (PageCandidate candidate : candidates) {
                if (candidate.observable != null
                        && !observables.contains(candidate.observable)) {
                    observables.add(candidate.observable);
                }
            }
            resolveObservableGraphs(connection, observables, observableGraphs);
        }
        List<AttestationMatch> matches = new ArrayList<AttestationMatch>();
        for (PageCandidate candidate : candidates) {
            Resource observableGraph = requestedObservableGraph;
            if (requestedObservable == null && candidate.observable != null) {
                observableGraph = observableGraphs.get(
                        candidate.observable.stringValue());
            }
            matches.add(new AttestationMatch(candidate.attestation,
                    candidate.observable, graph, fileId, observableGraph));
        }
        return matches;
    }

    private void bindAttestationSelection(TupleQuery query, Resource graph,
                                          IRI requestedObservable) {
        query.setBinding("attestationGraph", graph);
        query.setBinding("typePredicate", RDF.TYPE);
        query.setBinding("attestationType", vf.createIRI(FRAC + "Attestation"));
        query.setBinding("relation", vf.createIRI(FRAC + "attestation"));
        if (requestedObservable != null) {
            query.setBinding("requestedObservable", requestedObservable);
        }
    }

    private void resolveObservableGraphs(RepositoryConnection connection,
                                         List<Resource> observables,
                                         Map<String, Resource> cache)
            throws ManagerException {
        List<Resource> unresolved = new ArrayList<Resource>();
        for (Resource observable : observables) {
            if (!cache.containsKey(observable.stringValue())) {
                unresolved.add(observable);
            }
        }
        if (unresolved.isEmpty()) {
            return;
        }
        String sparql = "SELECT DISTINCT ?observable ?type ?graph WHERE { "
                + "VALUES ?observable { " + sparqlValues(unresolved)
                + " } GRAPH ?graph { ?observable <" + RDF.TYPE.stringValue()
                + "> ?type . } }";
        TupleQuery query = connection.prepareTupleQuery(QueryLanguage.SPARQL,
                sparql);
        query.setIncludeInferred(true);
        try (TupleQueryResult result = query.evaluate()) {
            while (result.hasNext()) {
                BindingSet row = result.next();
                Value observableValue = row.getValue("observable");
                Value type = row.getValue("type");
                Value graphValue = row.getValue("graph");
                if (!(observableValue instanceof Resource)
                        || !(type instanceof IRI)
                        || !(graphValue instanceof Resource)
                        || !isObservableGraph((Resource) graphValue)
                        || !isDirectObservableType((IRI) type,
                                (Resource) graphValue)) {
                    continue;
                }
                String key = observableValue.stringValue();
                Resource previous = cache.get(key);
                if (previous != null && !previous.equals(graphValue)) {
                    throw new ManagerException("AMBIGUOUS_OBSERVABLE_GRAPH: observable "
                            + key + " is defined in more than one supported named graph");
                }
                cache.put(key, (Resource) graphValue);
            }
        }
        for (Resource observable : unresolved) {
            if (!cache.containsKey(observable.stringValue())) {
                Resource graph = findObservableGraph(connection, observable);
                cache.put(observable.stringValue(), graph);
            }
        }
    }

    private boolean isDirectObservableType(IRI type, Resource graph) {
        if (graph.stringValue().equals(LexiconCrudSupport.lexicalConceptGraphUri())) {
            return type.stringValue().equals(ONTOLEX + "LexicalConcept");
        }
        String value = type.stringValue();
        return value.equals(ONTOLEX + "LexicalEntry")
                || value.equals(ONTOLEX + "Form")
                || value.equals(ONTOLEX + "LexicalSense");
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
            Resource lexicalGraph = resolveObservableGraph(lexical, observable);
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
                            observables, graph, textGraph, fileId, textSubjects,
                            Collections.singletonMap(observable.stringValue(), lexicalGraph))) {
                        matches.add(new AttestationMatch((Resource) object, observable,
                                graph, fileId, lexicalGraph));
                    }
                }
            }
            return page(lexical, text, matches, limit, offset);
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
                                 int limit, int offset) {
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
        return fillPage(lexical, text,
                new ArrayList<AttestationMatch>(matches.subList(offset, end)),
                page);
    }

    private AttestationPage fillPage(RepositoryConnection lexical,
                                     RepositoryConnection text,
                                     List<AttestationMatch> matches,
                                     AttestationPage page) {
        PageModels models = loadPageModels(lexical, text, matches);
        Map<String, String> observableLabels = new HashMap<String, String>();
        Map<String, List<String>> observableTypes =
                new HashMap<String, List<String>>();
        for (AttestationMatch match : matches) {
            Resource textGraph = vf.createIRI(textGraphBase + "documents/"
                    + match.fileId);
            page.list.add(readAttestation(lexical, match.attestation,
                    match.observable, match.observableGraph, match.fileId,
                    observableLabels, observableTypes,
                    models.lexical(match.graph), models.text(textGraph)));
        }
        return page;
    }

    private PageModels loadPageModels(RepositoryConnection lexical,
                                      RepositoryConnection text,
                                      List<AttestationMatch> matches) {
        PageModels result = new PageModels();
        Map<String, ResourceGroup> attestationGroups =
                new LinkedHashMap<String, ResourceGroup>();
        for (AttestationMatch match : matches) {
            addResource(attestationGroups, match.graph, match.attestation);
        }
        for (ResourceGroup group : attestationGroups.values()) {
            Model model = loadAttestationModel(lexical, group.graph,
                    group.resources);
            result.lexical.put(group.graph.stringValue(), model);
            for (Statement statement : model.filter(null,
                    vf.createIRI(FRAC + "locus"), null)) {
                if (statement.getObject() instanceof Resource) {
                    String fileId = fileIdForAttestation(matches,
                            statement.getSubject(), group.graph);
                    if (fileId != null) {
                        Resource textGraph = vf.createIRI(textGraphBase
                                + "documents/" + fileId);
                        addResource(result.locusGroups, textGraph,
                                (Resource) statement.getObject());
                    }
                }
            }
        }
        for (ResourceGroup group : result.locusGroups.values()) {
            result.text.put(group.graph.stringValue(), loadResourceModel(text,
                    group.graph, group.resources));
        }
        return result;
    }

    private void addResource(Map<String, ResourceGroup> groups, Resource graph,
                             Resource resource) {
        String key = graph.stringValue();
        ResourceGroup group = groups.get(key);
        if (group == null) {
            group = new ResourceGroup(graph);
            groups.put(key, group);
        }
        if (!group.resources.contains(resource)) {
            group.resources.add(resource);
        }
    }

    private String fileIdForAttestation(List<AttestationMatch> matches,
                                        Resource attestation, Resource graph) {
        for (AttestationMatch match : matches) {
            if (match.graph.equals(graph)
                    && match.attestation.equals(attestation)) {
                return match.fileId;
            }
        }
        return null;
    }

    private Model loadAttestationModel(RepositoryConnection connection,
                                       Resource graph,
                                       List<Resource> attestations) {
        if (attestations.isEmpty()) {
            return new LinkedHashModel();
        }
        String values = sparqlValues(attestations);
        String sparql = "CONSTRUCT { "
                + "?attestation ?property ?value . "
                + "?observable <" + FRAC + "attestation> ?attestation . "
                + "?observable <" + FRAC + "frequency> ?frequency . "
                + "?frequency ?frequencyProperty ?frequencyValue . "
                + "} WHERE { GRAPH ?graph { VALUES ?attestation { " + values
                + " } { ?attestation ?property ?value . } UNION { "
                + "?observable <" + FRAC + "attestation> ?attestation . "
                + "} UNION { ?observable <" + FRAC
                + "attestation> ?attestation ; <" + FRAC
                + "frequency> ?frequency . "
                + "?frequency ?frequencyProperty ?frequencyValue . } } }";
        return evaluateGraphQuery(connection, graph, sparql);
    }

    private Model loadResourceModel(RepositoryConnection connection,
                                    Resource graph,
                                    List<Resource> resources) {
        if (resources.isEmpty()) {
            return new LinkedHashModel();
        }
        String sparql = "CONSTRUCT { ?resource ?property ?value . } "
                + "WHERE { GRAPH ?graph { VALUES ?resource { "
                + sparqlValues(resources)
                + " } ?resource ?property ?value . } }";
        return evaluateGraphQuery(connection, graph, sparql);
    }

    private Model evaluateGraphQuery(RepositoryConnection connection,
                                     Resource graph, String sparql) {
        GraphQuery query = connection.prepareGraphQuery(QueryLanguage.SPARQL,
                sparql);
        query.setBinding("graph", graph);
        query.setIncludeInferred(false);
        Model result = new LinkedHashModel();
        try (GraphQueryResult statements = query.evaluate()) {
            while (statements.hasNext()) {
                result.add(statements.next());
            }
        }
        return result;
    }

    private String sparqlValues(List<Resource> resources) {
        StringBuilder result = new StringBuilder();
        for (Resource resource : resources) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(NTriplesUtil.toNTriplesString(resource));
        }
        return result.toString();
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
                                  Resource textGraph,
                                  String fileId,
                                  Map<String, Resource> textSubjects,
                                  Map<String, Resource> observableGraphs)
            throws ManagerException {
        if (filter == null) {
            return true;
        }
        String operator = filter.operator.toUpperCase(java.util.Locale.ROOT);
        if ("AND".equals(operator)) {
            for (AttestationFilter child : filter.filters) {
                if (!matchesFilter(child, lexical, text, attestation, observables,
                        attestationGraph, textGraph, fileId, textSubjects,
                        observableGraphs)) {
                    return false;
                }
            }
            return true;
        }
        if ("OR".equals(operator)) {
            for (AttestationFilter child : filter.filters) {
                if (matchesFilter(child, lexical, text, attestation, observables,
                        attestationGraph, textGraph, fileId, textSubjects,
                        observableGraphs)) {
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
                    Resource graph = observableGraph(lexical, observable,
                            observableGraphs);
                    if (graph != null && hasObservableType(lexical, observable,
                            vf.createIRI(value), graph)) {
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
        for (RdfMetadataValue item : filter.rdfValues) {
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
                                         AttestationFilter filter,
                                         Map<String, Resource> observableGraphs)
            throws ManagerException {
        if (observables.isEmpty()) {
            return null;
        }
        List<String> requestedTypes = new ArrayList<String>();
        collectObservableTypes(filter, requestedTypes);
        for (Resource observable : observables) {
            for (String type : requestedTypes) {
                Resource graph = observableGraph(lexical, observable,
                        observableGraphs);
                if (graph != null && hasObservableType(lexical, observable,
                        vf.createIRI(type), graph)) {
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
                        requestedType, lexicalGraph, new HashSet<String>())) {
                    return true;
                }
            }
        }
        return false;
    }

    private AttestationListItem readAttestation(
            RepositoryConnection lexical, Resource attestation,
            Resource observable, Resource lexicalGraph, String fileId,
            Map<String, String> observableLabels,
            Map<String, List<String>> observableTypes,
            Model attestationModel, Model textModel) {
        AttestationListItem result = new AttestationListItem();
        result.attestation = attestation.stringValue();
        result.fileId = fileId;
        result.external = Boolean.valueOf(fileId.startsWith("external-"));
        result.observable = observable == null ? null : observable.stringValue();
        result.observableLabel = NO_LABEL;
        if (observable != null && lexicalGraph != null) {
            String key = observable.stringValue();
            if (!observableTypes.containsKey(key)) {
                observableTypes.put(key, rdfTypes(lexical, observable,
                        lexicalGraph));
            }
            result.observableTypes = new ArrayList<String>(
                    observableTypes.get(key));
            if (!observableLabels.containsKey(key)) {
                observableLabels.put(key, observableLabel(lexical, observable,
                        lexicalGraph));
            }
            result.observableLabel = observableLabels.get(key);
        }
        result.creator = firstString(attestationModel, attestation,
                DCTERMS.CREATOR);
        result.creationDate = firstString(attestationModel, attestation,
                DCTERMS.CREATED);
        result.lastUpdate = firstString(attestationModel, attestation,
                DCTERMS.MODIFIED);
        result.metadata = readMetadata(attestationModel, attestation);
        result.value = firstString(attestationModel, attestation, RDF.VALUE);
        if (result.value == null) {
            result.value = firstString(attestationModel, attestation,
                    vf.createIRI(FRAC + "gloss"));
        }
        Value corpus = firstObject(attestationModel, attestation,
                vf.createIRI(FRAC + "observedIn"));
        result.corpus = corpus == null ? null : corpus.stringValue();
        Value locus = firstObject(attestationModel, attestation,
                vf.createIRI(FRAC + "locus"));
        result.locus = locus == null ? null : locus.stringValue();
        if (locus instanceof Resource) {
            Resource locusResource = (Resource) locus;
            result.locusTypes = rdfTypes(textModel, locusResource);
            Literal anchor = firstLiteral(textModel, locusResource,
                    vf.createIRI(NIF + "anchorOf"));
            if (anchor != null) {
                result.value = anchor.getLabel();
                result.language = anchor.getLanguage().orElse(null);
            }
            result.start = firstInteger(textModel, locusResource,
                    vf.createIRI(NIF + "beginIndex"));
            result.end = firstInteger(textModel, locusResource,
                    vf.createIRI(NIF + "endIndex"));
            Value reference = firstObject(textModel, locusResource,
                    vf.createIRI(NIF + "referenceContext"));
            result.referenceContext = reference == null ? null : reference.stringValue();
        }
        if (observable != null) {
            Value frequencyObservedIn = blank(result.referenceContext)
                    ? corpus : vf.createIRI(result.referenceContext);
            if (frequencyObservedIn instanceof IRI) {
                result.frequency = frequencyValue(attestationModel, observable,
                        (IRI) frequencyObservedIn);
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
                if (MetadataPolicy.isProtected(property)) {
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

    private Map<String, List<AttestationMetadataValue>> readMetadata(
            Model model, Resource attestation) {
        Map<String, List<AttestationMetadataValue>> unsorted =
                new HashMap<String, List<AttestationMetadataValue>>();
        for (Statement statement : model.filter(attestation, null, null)) {
            String property = statement.getPredicate().stringValue();
            if (MetadataPolicy.isProtected(property)) {
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

    private Map<String, List<AttestationMetadataValue>> outputMetadata(
            Map<IRI, List<Value>> metadata) {
        Map<String, List<AttestationMetadataValue>> result =
                new LinkedHashMap<String, List<AttestationMetadataValue>>();
        for (RdfMetadataProperty property : metadataCodec.encode(metadata)) {
            List<AttestationMetadataValue> values =
                    new ArrayList<AttestationMetadataValue>();
            for (RdfMetadataValue value : property.values) {
                values.add(new AttestationMetadataValue(value.value, value.type,
                        value.language, value.datatype));
            }
            result.put(property.property, values);
        }
        return result;
    }

    private AttestationMetadataValue outputMetadataValue(Value value) {
        it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue common =
                metadataCodec.encode(value);
        if (common == null) {
            return null;
        }
        return new AttestationMetadataValue(common.value, common.type,
                common.language, common.datatype);
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
            Resource entryValue = firstSubject(connection,
                    vf.createIRI(ONTOLEX + "sense"), observable, lexicalGraph);
            String entryLabel = null;
            if (entryValue != null) {
                Resource entry = entryValue;
                entryLabel = firstNonBlank(
                        firstLiteralWithLanguage(connection, entry,
                                vf.createIRI(RDFS + "label"), lexicalGraph),
                        canonicalWrittenRep(connection, entry, lexicalGraph), null);
            }
            if (blank(entryLabel)) {
                return blank(definition) ? NO_LABEL : definition;
            }
            return blank(definition) ? entryLabel : entryLabel + " - " + definition;
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

    private List<String> rdfTypes(Model model, Resource subject) {
        List<String> types = new ArrayList<String>();
        for (Statement statement : model.filter(subject, RDF.TYPE, null)) {
            Value type = statement.getObject();
            if (type instanceof IRI && !types.contains(type.stringValue())) {
                types.add(type.stringValue());
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

    private String firstString(Model model, Resource subject, IRI predicate) {
        Value value = firstObject(model, subject, predicate);
        return value == null ? null : value.stringValue();
    }

    private Value firstObject(Model model, Resource subject, IRI predicate) {
        for (Statement statement : model.filter(subject, predicate, null)) {
            return statement.getObject();
        }
        return null;
    }

    private Value firstObject(RepositoryConnection connection, Resource subject,
                              IRI predicate, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            return statements.hasNext() ? statements.next().getObject() : null;
        }
    }

    private Resource firstSubject(RepositoryConnection connection, IRI predicate,
                                  Value object, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, predicate, object, false, graph)) {
            return statements.hasNext() ? statements.next().getSubject() : null;
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

    private Integer firstInteger(Model model, Resource subject, IRI predicate) {
        Value value = firstObject(model, subject, predicate);
        if (!(value instanceof Literal)) {
            return null;
        }
        try {
            return Integer.valueOf(((Literal) value).intValue());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Literal firstLiteral(Model model, Resource subject, IRI predicate) {
        Value value = firstObject(model, subject, predicate);
        return value instanceof Literal ? (Literal) value : null;
    }

    private Integer frequencyValue(Model model, Resource observable,
                                   IRI observedIn) {
        IRI frequencyRelation = vf.createIRI(FRAC + "frequency");
        IRI observedInRelation = vf.createIRI(FRAC + "observedIn");
        List<Resource> matches = new ArrayList<Resource>();
        for (Statement statement : model.filter(observable, frequencyRelation,
                null)) {
            Value candidate = statement.getObject();
            if (candidate instanceof Resource
                    && model.contains((Resource) candidate, observedInRelation,
                            observedIn)) {
                matches.add((Resource) candidate);
            }
        }
        if (matches.size() != 1) {
            return null;
        }
        Literal value = firstLiteral(model, matches.get(0), RDF.VALUE);
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

    private Resource resolveObservableGraph(RepositoryConnection connection,
                                            Resource observable)
            throws ManagerException {
        Resource result = findObservableGraph(connection, observable);
        if (result != null) {
            return result;
        }
        throw new ManagerException("INVALID_OBSERVABLE: observable must identify an "
                + "ontolex:LexicalEntry, ontolex:Form, ontolex:LexicalSense, "
                + "or ontolex:LexicalConcept in a supported named lexical graph");
    }

    private Resource findObservableGraph(RepositoryConnection connection,
                                         Resource observable)
            throws ManagerException {
        Resource result = null;
        for (Resource graph : supportedObservableGraphs(connection)) {
            String[] types = graph.stringValue().equals(
                    LexiconCrudSupport.lexicalConceptGraphUri())
                    ? new String[]{"LexicalConcept"}
                    : new String[]{"LexicalEntry", "Form", "LexicalSense"};
            for (String type : types) {
                if (hasObservableType(connection, observable,
                        vf.createIRI(ONTOLEX + type), graph)) {
                    if (result != null && !result.equals(graph)) {
                        throw new ManagerException("AMBIGUOUS_OBSERVABLE_GRAPH: observable "
                                + observable.stringValue()
                                + " is defined in more than one supported named graph");
                    }
                    result = graph;
                    break;
                }
            }
        }
        return result;
    }

    private Resource observableGraph(RepositoryConnection connection,
                                     Resource observable,
                                     Map<String, Resource> cache)
            throws ManagerException {
        if (observable == null) {
            return null;
        }
        String key = observable.stringValue();
        if (!cache.containsKey(key)) {
            cache.put(key, findObservableGraph(connection, observable));
        }
        return cache.get(key);
    }

    private List<Resource> supportedObservableGraphs(
            RepositoryConnection connection) {
        List<Resource> graphs = new ArrayList<Resource>();
        graphs.add(vf.createIRI(LexiconCrudSupport.lexicalConceptGraphUri()));
        try (RepositoryResult<Resource> contexts = connection.getContextIDs()) {
            while (contexts.hasNext()) {
                Resource graph = contexts.next();
                if (isObservableGraph(graph) && !graphs.contains(graph)) {
                    graphs.add(graph);
                }
            }
        }
        return graphs;
    }

    private boolean isObservableGraph(Resource graph) {
        String graphUri = graph.stringValue();
        if (graphUri.equals(LexiconCrudSupport.lexicalConceptGraphUri())) {
            return true;
        }
        if (!graphUri.startsWith(LexiconCrudSupport.LEXICAL_GRAPH_BASE_URI)) {
            return false;
        }
        String language = graphUri.substring(
                LexiconCrudSupport.LEXICAL_GRAPH_BASE_URI.length());
        try {
            return graphUri.equals(LexiconCrudSupport.lexicalGraphUri(language));
        } catch (IllegalArgumentException e) {
            return false;
        }
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
        return attestationModel(attestation, observable, locus, corpus, value,
                value, language, author, timestamp);
    }

    private Model attestationModel(IRI attestation, IRI observable, IRI locus,
                                   IRI corpus, String value, String gloss,
                                   String language, String author, String timestamp) {
        Model model = new LinkedHashModel();
        model.add(attestation, RDF.TYPE, vf.createIRI(FRAC + "Attestation"));
        model.add(attestation, DCTERMS.CREATOR,
                vf.createLiteral(author == null ? "anonymous" : author));
        model.add(attestation, DCTERMS.CREATED, vf.createLiteral(timestamp));
        model.add(attestation, DCTERMS.MODIFIED, vf.createLiteral(timestamp));
        Literal attestedValue = blank(language)
                ? vf.createLiteral(value) : vf.createLiteral(value, language);
        Literal glossValue = blank(language)
                ? vf.createLiteral(gloss) : vf.createLiteral(gloss, language);
        model.add(attestation, vf.createIRI(FRAC + "gloss"), glossValue);
        model.add(attestation, RDF.VALUE, attestedValue);
        model.add(attestation, vf.createIRI(FRAC + "locus"), locus);
        model.add(attestation, vf.createIRI(FRAC + "observedIn"), corpus);
        model.add(observable, vf.createIRI(FRAC + "attestation"), attestation);
        return model;
    }

    private void addMetadata(Model model, IRI attestation,
                             Map<IRI, List<Value>> metadata) {
        for (Map.Entry<IRI, List<Value>> property : metadata.entrySet()) {
            for (Value value : property.getValue()) {
                model.add(attestation, property.getKey(), value);
            }
        }
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

    private synchronized AttestationIdentity newAttestationIdentity(
            RepositoryConnection connection, Timestamp timestamp,
            Set<String> reserved)
            throws ManagerException {
        String namespace = configured("repository.lexicon.namespace",
                "https://lexo.ilc.cnr.it#");
        String prefix = configured("repository.instance.id", "LexO_");
        long candidateMillis = timestamp.getTime();
        if (lastAttestationEpochMillis >= candidateMillis) {
            candidateMillis = lastAttestationEpochMillis + 1L;
        }
        for (int attempt = 0; attempt < 10000; attempt++, candidateMillis++) {
            Timestamp candidateTimestamp = new Timestamp(candidateMillis);
            String local = (prefix + candidateTimestamp.toString())
                    .replaceAll("\\s+", "")
                    .replace(':', '_').replace('.', '_');
            IRI candidate = vf.createIRI(namespace(namespace) + local);
            if (!reserved.contains(candidate.stringValue())
                    && !hasNamedStatement(connection, candidate)) {
                reserved.add(candidate.stringValue());
                lastAttestationEpochMillis = candidateMillis;
                return new AttestationIdentity(candidate, candidateTimestamp);
            }
        }
        throw new ManagerException("ATTESTATION_ID_CONFLICT: unable to allocate "
                + "a unique timestamp-based attestation IRI");
    }

    private boolean hasNamedStatement(RepositoryConnection connection,
                                      Resource subject) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, null, null, false)) {
            while (statements.hasNext()) {
                if (statements.next().getContext() != null) {
                    return true;
                }
            }
        }
        return false;
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

    private boolean isGeneratedLocus(RepositoryConnection text, IRI locus,
                                     Resource textGraph) {
        return text.hasStatement(locus, vf.createIRI(PROV + "wasGeneratedBy"),
                attestationServiceIri(), false, textGraph);
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

    private void compensateLocusRelink(RepositoryConnection connection,
                                       Model removedOldStatements,
                                       Model addedNewStatements,
                                       Resource textGraph) {
        try {
            connection.begin();
            connection.remove(addedNewStatements, textGraph);
            connection.add(removedOldStatements, textGraph);
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

    private static final class ValidatedObservableUpdate {
        final IRI attestation;
        final List<IRI> previousObservables;

        ValidatedObservableUpdate(IRI attestation,
                                  List<IRI> previousObservables) {
            this.attestation = attestation;
            this.previousObservables = previousObservables;
        }
    }

    private static final class AttestationIdentity {
        final IRI iri;
        final Timestamp timestamp;

        AttestationIdentity(IRI iri, Timestamp timestamp) {
            this.iri = iri;
            this.timestamp = timestamp;
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
        final Resource observableGraph;

        AttestationMatch(Resource attestation, Resource observable,
                         Resource graph, String fileId, Resource observableGraph) {
            this.attestation = attestation;
            this.observable = observable;
            this.graph = graph;
            this.fileId = fileId;
            this.observableGraph = observableGraph;
        }
    }

    private static final class PageCandidate {
        final Resource attestation;
        final Resource observable;

        PageCandidate(Resource attestation, Resource observable) {
            this.attestation = attestation;
            this.observable = observable;
        }
    }

    private static final class ResourceGroup {
        final Resource graph;
        final List<Resource> resources = new ArrayList<Resource>();

        ResourceGroup(Resource graph) {
            this.graph = graph;
        }
    }

    private static final class PageModels {
        final Map<String, Model> lexical = new HashMap<String, Model>();
        final Map<String, Model> text = new HashMap<String, Model>();
        final Map<String, ResourceGroup> locusGroups =
                new LinkedHashMap<String, ResourceGroup>();

        Model lexical(Resource graph) {
            Model model = lexical.get(graph.stringValue());
            return model == null ? new LinkedHashModel() : model;
        }

        Model text(Resource graph) {
            Model model = text.get(graph.stringValue());
            return model == null ? new LinkedHashModel() : model;
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
