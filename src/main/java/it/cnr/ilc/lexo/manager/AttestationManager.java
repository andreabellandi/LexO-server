package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationOccurrence;
import it.cnr.ilc.lexo.service.data.attestation.output.Attestation;
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

/** Creates FRAC attestations and the corresponding NIF loci. */
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
    private static final String DEFAULT_STRUCTURE_NAMESPACE =
            "https://lexo.ilc.cnr.it/vocabulary/nif-structure#";

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

    /** Backward-compatible manager entry point for one attestation occurrence. */
    public Attestation create(String observableValue, String description,
                              String attestedValue, String startValue,
                              String endValue, String corpusValue,
                              boolean external, String author) throws ManagerException {
        int start = integer("start", startValue);
        int end = integer("end", endValue);
        List<AttestationOccurrence> occurrences = new ArrayList<AttestationOccurrence>();
        occurrences.add(new AttestationOccurrence(description, attestedValue,
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
                        observableIri, locus, corpusIri, occurrence.description, value,
                        location.language, author, timestamp);
                Attestation result = attestationResult(attestationIri, observable,
                        observableLabel, observableTypes, occurrence.description,
                        value, start, end, corpus, location, external, author,
                        timestamp);
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

    private Attestation attestationResult(IRI attestationIri, String observable,
                                          String observableLabel,
                                          List<String> observableTypes,
                                          String description, String value,
                                          int start, int end, String corpus,
                                          TextLocation location, boolean external,
                                          String author, String timestamp) {
        Attestation result = new Attestation();
        result.attestation = attestationIri.stringValue();
        result.observable = observable;
        result.observableLabel = observableLabel;
        result.observableTypes = new ArrayList<String>(observableTypes);
        result.description = blank(description) ? null : description;
        result.value = value;
        result.start = Integer.valueOf(start);
        result.end = Integer.valueOf(end);
        result.corpus = corpus;
        result.locus = location.locus;
        result.locusTypes.add(NIF + "Phrase");
        result.locusTypes.add(NIF + "RFC5147String");
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

    private String timestamp(Timestamp value) {
        return new SimpleDateFormat(configured("manager.operationTimestampFormat",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")).format(value);
    }

    /** Returns one filtered page of attestations and enriches it with NIF locus data. */
    public AttestationPage list(String fileIdValue, String observableTypeValue,
                                String authorValue, String limitValue,
                                String offsetValue) throws ManagerException {
        String fileId = required("fileId", fileIdValue);
        final IRI attestationGraph;
        try {
            attestationGraph = vf.createIRI(
                    LexicalNamedGraphs.attestationGraphUri(fileId));
        } catch (IllegalArgumentException e) {
            throw new ManagerException("INVALID_FILE_ID: fileId contains unsupported characters");
        }
        IRI observableType = blank(observableTypeValue) ? null
                : iri("observableType", observableTypeValue.trim());
        String author = blank(authorValue) ? null : authorValue;
        int limit = paginationInteger("limit", limitValue, 200, false);
        int offset = paginationInteger("offset", offsetValue, 0, true);

        RepositoryConnection lexical = null;
        RepositoryConnection text = null;
        try {
            lexical = connections.acquire(RepositoryTarget.LEXICON);
            text = connections.acquire(RepositoryTarget.TEXT);
            IRI lexicalGraph = vf.createIRI(LexicalNamedGraphs.lexiconGraphUri());
            IRI textGraph = vf.createIRI(textGraphBase + "documents/" + fileId);
            List<Attestation> matches = new ArrayList<Attestation>();
            Map<String, String> observableLabels = new HashMap<String, String>();
            try (RepositoryResult<Statement> statements = lexical.getStatements(null,
                    RDF.TYPE, vf.createIRI(FRAC + "Attestation"), false,
                    attestationGraph)) {
                while (statements.hasNext()) {
                    Resource resource = statements.next().getSubject();
                    Resource observable = matchingObservable(lexical, resource,
                            attestationGraph, lexicalGraph, observableType);
                    if (observableType != null && observable == null) {
                        continue;
                    }
                    if (author != null && !hasStringValue(lexical, resource,
                            DCTERMS.CREATOR, author, attestationGraph)) {
                        continue;
                    }
                    matches.add(readAttestation(lexical, text, resource, observable,
                            attestationGraph, lexicalGraph, textGraph, fileId,
                            observableLabels));
                }
            }
            Collections.sort(matches, new Comparator<Attestation>() {
                @Override
                public int compare(Attestation left, Attestation right) {
                    return left.attestation.compareTo(right.attestation);
                }
            });

            AttestationPage page = new AttestationPage();
            page.totalHits = matches.size();
            page.limit = limit;
            page.offset = offset;
            if (offset < matches.size()) {
                int end = (int) Math.min((long) matches.size(),
                        (long) offset + (long) limit);
                page.list.addAll(matches.subList(offset, end));
            }
            return page;
        } catch (RuntimeException e) {
            throw new ManagerException("ATTESTATION_LIST_FAILED: " + message(e), e);
        } finally {
            connections.release(RepositoryTarget.TEXT, text);
            connections.release(RepositoryTarget.LEXICON, lexical);
        }
    }

    private Resource matchingObservable(RepositoryConnection connection,
                                        Resource attestation, Resource attestationGraph,
                                        Resource lexicalGraph, IRI observableType) {
        Resource first = null;
        try (RepositoryResult<Statement> statements = connection.getStatements(null,
                vf.createIRI(FRAC + "attestation"), attestation, false,
                attestationGraph)) {
            while (statements.hasNext()) {
                Resource candidate = statements.next().getSubject();
                if (first == null) {
                    first = candidate;
                }
                if (observableType != null && connection.hasStatement(candidate,
                        RDF.TYPE, observableType, true, lexicalGraph)) {
                    return candidate;
                }
            }
        }
        return observableType == null ? first : null;
    }

    private Attestation readAttestation(RepositoryConnection lexical,
                                        RepositoryConnection text,
                                        Resource attestation, Resource observable,
                                        Resource attestationGraph, Resource lexicalGraph,
                                        Resource textGraph, String fileId,
                                        Map<String, String> observableLabels) {
        Attestation result = new Attestation();
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
        result.description = firstString(lexical, attestation, DCTERMS.DESCRIPTION,
                attestationGraph);
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
        return result;
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
            if (connection.hasStatement(observable, RDF.TYPE,
                    vf.createIRI(ONTOLEX + type), true, graph)) {
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
        model.add(locus, vf.createIRI(NIF + "anchorOf"), language == null
                ? vf.createLiteral(value) : vf.createLiteral(value, language));
        model.add(locus, vf.createIRI(NIF + "beginIndex"),
                vf.createLiteral(Integer.toString(start), XSD.NON_NEGATIVE_INTEGER));
        model.add(locus, vf.createIRI(NIF + "endIndex"),
                vf.createLiteral(Integer.toString(end), XSD.NON_NEGATIVE_INTEGER));
        model.add(locus, vf.createIRI(NIF + "referenceContext"), referenceContext);
        return model;
    }

    private Model attestationModel(IRI attestation, IRI observable, IRI locus,
                                   IRI corpus, String description, String value,
                                   String language, String author, String timestamp) {
        Model model = new LinkedHashModel();
        model.add(attestation, RDF.TYPE, vf.createIRI(FRAC + "Attestation"));
        model.add(attestation, DCTERMS.CREATOR,
                vf.createLiteral(author == null ? "anonymous" : author));
        model.add(attestation, DCTERMS.CREATED, vf.createLiteral(timestamp));
        model.add(attestation, DCTERMS.MODIFIED, vf.createLiteral(timestamp));
        if (!blank(description)) {
            model.add(attestation, DCTERMS.DESCRIPTION, vf.createLiteral(description));
        }
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
        if (locusExists && !missing.isEmpty()) {
            throw new ManagerException("LOCUS_CONFLICT: the NIF locus already exists with different data");
        }
        return missing;
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
