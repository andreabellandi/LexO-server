package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.manager.metadata.RdfMetadataCodec;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptLabel;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptCreationResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/** Validates and atomically creates lexical concepts in their fixed named graph. */
public final class LexicalConceptManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final RdfMetadataCodec metadataCodec = new RdfMetadataCodec();
    private final Repository repository;

    /** Runtime constructor used by {@link ManagerFactory}. */
    public LexicalConceptManager() {
        this(null);
    }

    LexicalConceptManager(Repository repository) {
        this.repository = repository;
    }

    /** Creates one lexical concept after validating every requested relation. */
    public LexicalConceptCreationResult create(LexicalConceptCreationRequest request,
                                               String author) {
        ValidatedRequest input = validateRequest(request);
        String creator = LexiconCrudSupport.author(author);
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            Resource graph = vf.createIRI(
                    LexiconCrudSupport.lexicalConceptGraphUri());
            validateResources(connection, input, graph);

            IRI lexicalConcept = vf.createIRI(LexiconCrudSupport.newResourceUri());
            String timestamp = LexiconCrudSupport.operationTimestamp();
            Literal date = vf.createLiteral(timestamp, XSD.DATETIME);
            Model model = new LinkedHashModel();
            model.add(lexicalConcept, RDF.TYPE, iri(ONTOLEX + "LexicalConcept"));
            model.add(lexicalConcept, DCTERMS.CREATOR, vf.createLiteral(creator));
            model.add(lexicalConcept, DCTERMS.CREATED, date);
            model.add(lexicalConcept, DCTERMS.MODIFIED, date);
            addTexts(model, lexicalConcept, iri(SKOS + "prefLabel"), input.labels);
            addTexts(model, lexicalConcept, iri(SKOS + "alternativeLabel"),
                    input.alternativeLabels);
            addTexts(model, lexicalConcept, iri(SKOS + "hiddenLabel"),
                    input.hiddenLabels);
            addTexts(model, lexicalConcept, iri(SKOS + "definition"),
                    input.definitions);
            for (IRI sense : input.senses) {
                model.add(lexicalConcept, iri(ONTOLEX + "lexicalizedSense"),
                        sense);
            }
            if (input.parent != null) {
                model.add(lexicalConcept, iri(SKOS + "broader"), input.parent);
            }
            if (input.conceptSet != null) {
                model.add(lexicalConcept, iri(SKOS + "inScheme"), input.conceptSet);
            }
            for (Map.Entry<IRI, List<Value>> property
                    : input.metadata.entrySet()) {
                for (Value value : property.getValue()) {
                    model.add(lexicalConcept, property.getKey(), value);
                }
            }

            connection.add(model, graph);
            connection.commit();
            return result(lexicalConcept, creator, timestamp, input);
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        } finally {
            release(connection);
        }
    }

    private ValidatedRequest validateRequest(LexicalConceptCreationRequest request) {
        if (request == null) {
            throw invalid("MISSING_LEXICAL_CONCEPT", "request body is required");
        }
        if (request.label == null || request.label.isEmpty()) {
            throw invalid("MISSING_LABEL", "at least one preferred label is required");
        }
        List<LocalizedText> labels = validateTexts(request.label, "label");
        List<LocalizedText> alternatives = validateTexts(
                request.alternativeLabel, "alternativeLabel");
        List<LocalizedText> hidden = validateTexts(request.hiddenLabel, "hiddenLabel");
        List<LocalizedText> definitions = validateTexts(request.definition, "definition");
        List<IRI> senses = new ArrayList<IRI>();
        if (request.senseId != null) {
            for (int i = 0; i < request.senseId.size(); i++) {
                senses.add(requireIri("senseId[" + i + "]", request.senseId.get(i),
                        "INVALID_SENSE_IRI"));
            }
        }
        IRI parent = optionalIri("parent", request.parent, "INVALID_PARENT_IRI");
        IRI conceptSet = optionalIri("conceptSetId", request.conceptSetId,
                "INVALID_CONCEPT_SET_IRI");
        LinkedHashMap<IRI, List<Value>> metadata =
                new LinkedHashMap<IRI, List<Value>>();
        if (request.metadata != null && !request.metadata.isEmpty()) {
            metadata = metadataCodec.decodeProperties(request.metadata, false);
        }
        return new ValidatedRequest(labels, alternatives, hidden, definitions,
                senses, parent, conceptSet, metadata);
    }

    private List<LocalizedText> validateTexts(List<LexicalConceptLabel> values,
                                              String field) {
        if (values == null) {
            return Collections.emptyList();
        }
        List<LocalizedText> result = new ArrayList<LocalizedText>();
        for (int i = 0; i < values.size(); i++) {
            LexicalConceptLabel value = values.get(i);
            String path = field + "[" + i + "]";
            if (value == null) {
                throw invalid("INVALID_LABEL", path + " must be an object");
            }
            String text = requireText(value.label, "MISSING_LABEL_VALUE",
                    path + ".label is required");
            String language;
            try {
                language = Iso639LanguageValidator.get()
                        .requireValid(value.language);
            } catch (IllegalArgumentException e) {
                throw invalid("INVALID_LABEL_LANGUAGE",
                        path + ".language must be a supported ISO 639 code");
            }
            result.add(new LocalizedText(text, language));
        }
        return result;
    }

    private void validateResources(RepositoryConnection connection,
                                   ValidatedRequest input, Resource graph) {
        for (int i = 0; i < input.senses.size(); i++) {
            validateResource(connection, input.senses.get(i),
                    iri(ONTOLEX + "LexicalSense"), graph,
                    "SENSE_NOT_FOUND", "INVALID_SENSE_TYPE");
        }
        if (input.parent != null) {
            validateResource(connection, input.parent,
                    iri(ONTOLEX + "LexicalConcept"), graph,
                    "PARENT_NOT_FOUND", "INVALID_PARENT_TYPE");
        }
        if (input.conceptSet != null) {
            validateResource(connection, input.conceptSet,
                    iri(ONTOLEX + "ConceptSet"), graph,
                    "CONCEPT_SET_NOT_FOUND", "INVALID_CONCEPT_SET_TYPE");
        }
    }

    private void validateResource(RepositoryConnection connection, IRI resource,
                                  IRI expectedType, Resource graph,
                                  String missingCode, String typeCode) {
        if (!connection.hasStatement(resource, null, null, false, graph)) {
            throw new LexicalConceptCreationException(404, missingCode,
                    resource.stringValue() + " does not exist in the lexical concept graph");
        }
        if (!connection.hasStatement(resource, RDF.TYPE, expectedType, false, graph)) {
            throw new LexicalConceptCreationException(422, typeCode,
                    resource.stringValue() + " must be typed as "
                            + expectedType.stringValue());
        }
    }

    private void addTexts(Model model, IRI subject, IRI predicate,
                          List<LocalizedText> values) {
        for (LocalizedText value : values) {
            model.add(subject, predicate,
                    vf.createLiteral(value.text, value.language));
        }
    }

    private LexicalConceptCreationResult result(IRI concept, String author,
                                                 String timestamp,
                                                 ValidatedRequest input) {
        LexicalConceptCreationResult result = new LexicalConceptCreationResult();
        result.lexicalConcept = concept.stringValue();
        result.author = author;
        result.created = timestamp;
        result.senseId = new ArrayList<String>();
        for (IRI sense : input.senses) {
            result.senseId.add(sense.stringValue());
        }
        result.parent = input.parent == null ? null : input.parent.stringValue();
        result.conceptSetId = input.conceptSet == null
                ? null : input.conceptSet.stringValue();
        result.metadata = metadataCodec.encode(input.metadata);
        return result;
    }

    private IRI optionalIri(String field, String value, String code) {
        return value == null || value.trim().isEmpty()
                ? null : requireIri(field, value, code);
    }

    private IRI requireIri(String field, String value, String code) {
        String normalized = requireText(value, code,
                field + " must be an absolute IRI").trim();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw invalid(code, field + " must be an absolute IRI");
            }
            return iri(normalized);
        } catch (URISyntaxException e) {
            throw invalid(code, field + " must be an absolute IRI");
        }
    }

    private String requireText(String value, String code, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(code, message);
        }
        return value;
    }

    private IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
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

    /** Domain validation failure with its stable HTTP status. */
    public static final class LexicalConceptCreationException
            extends RuntimeException {
        public final int httpStatus;

        LexicalConceptCreationException(int httpStatus, String code, String message) {
            super(code + ": " + message);
            this.httpStatus = httpStatus;
        }
    }

    private static final class LocalizedText {
        final String text;
        final String language;

        LocalizedText(String text, String language) {
            this.text = text;
            this.language = language;
        }
    }

    private static final class ValidatedRequest {
        final List<LocalizedText> labels;
        final List<LocalizedText> alternativeLabels;
        final List<LocalizedText> hiddenLabels;
        final List<LocalizedText> definitions;
        final List<IRI> senses;
        final IRI parent;
        final IRI conceptSet;
        final LinkedHashMap<IRI, List<Value>> metadata;

        ValidatedRequest(List<LocalizedText> labels,
                         List<LocalizedText> alternativeLabels,
                         List<LocalizedText> hiddenLabels,
                         List<LocalizedText> definitions, List<IRI> senses,
                         IRI parent, IRI conceptSet,
                         LinkedHashMap<IRI, List<Value>> metadata) {
            this.labels = labels;
            this.alternativeLabels = alternativeLabels;
            this.hiddenLabels = hiddenLabels;
            this.definitions = definitions;
            this.senses = senses;
            this.parent = parent;
            this.conceptSet = conceptSet;
            this.metadata = metadata;
        }
    }
}
