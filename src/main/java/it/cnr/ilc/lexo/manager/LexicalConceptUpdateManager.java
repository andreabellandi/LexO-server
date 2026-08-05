package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptLabel;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptUpdateRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptUpdateResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Atomically updates labels, definitions, and links of a lexical concept. */
public final class LexicalConceptUpdateManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final Repository repository;

    public LexicalConceptUpdateManager() {
        this(null);
    }

    LexicalConceptUpdateManager(Repository repository) {
        this.repository = repository;
    }

    public LexicalConceptUpdateResult update(LexicalConceptUpdateRequest request,
                                              String author) {
        ValidatedRequest input = validate(request);
        String updater = LexiconCrudSupport.author(author);
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            Resource graph = vf.createIRI(
                    LexiconCrudSupport.lexicalConceptGraphUri());
            validateTarget(connection, input.concept, graph);
            validateLinks(connection, input, graph);
            validateExpectedModified(connection, input.concept,
                    input.expectedModified, graph);

            replaceTexts(connection, input.concept,
                    vf.createIRI(SKOS + "prefLabel"), input.labels,
                    input.labelsPresent, graph);
            replaceTexts(connection, input.concept,
                    vf.createIRI(SKOS + "alternativeLabel"),
                    input.alternativeLabels, input.alternativeLabelsPresent,
                    graph);
            replaceTexts(connection, input.concept,
                    vf.createIRI(SKOS + "hiddenLabel"), input.hiddenLabels,
                    input.hiddenLabelsPresent, graph);
            replaceTexts(connection, input.concept,
                    vf.createIRI(SKOS + "definition"), input.definitions,
                    input.definitionsPresent, graph);
            if (input.sensesPresent) {
                connection.remove(input.concept,
                        vf.createIRI(ONTOLEX + "isLexicalizedSenseOf"),
                        null, graph);
            }
            replaceIris(connection, input.concept,
                    vf.createIRI(ONTOLEX + "lexicalizedSense"), input.senses,
                    input.sensesPresent, graph);
            replaceIri(connection, input.concept,
                    vf.createIRI(SKOS + "broader"), input.parent,
                    input.parentPresent, graph);
            replaceIri(connection, input.concept,
                    vf.createIRI(SKOS + "inScheme"), input.conceptSet,
                    input.conceptSetPresent, graph);

            String modified = LexiconCrudSupport.operationTimestamp();
            connection.remove(input.concept, DCTERMS.MODIFIED, null, graph);
            connection.add(input.concept, DCTERMS.MODIFIED,
                    vf.createLiteral(modified, XSD.DATETIME), graph);
            LexicalConceptUpdateResult result = result(connection, input.concept,
                    updater, modified, graph);
            connection.commit();
            return result;
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        } finally {
            release(connection);
        }
    }

    private ValidatedRequest validate(LexicalConceptUpdateRequest request) {
        if (request == null) {
            throw invalid("MISSING_LEXICAL_CONCEPT_UPDATE",
                    "request body is required");
        }
        IRI concept = requireIri("lexicalConcept", request.getLexicalConcept(),
                "INVALID_LEXICAL_CONCEPT_IRI");
        if (!request.hasLabel() && !request.hasAlternativeLabel()
                && !request.hasHiddenLabel() && !request.hasDefinition()
                && !request.hasSenseId() && !request.hasParent()
                && !request.hasConceptSetId()) {
            throw invalid("MISSING_LEXICAL_CONCEPT_CHANGES",
                    "at least one mutable field must be supplied");
        }
        List<LocalizedText> labels = validateTexts(request.getLabel(), "label",
                request.hasLabel(), true);
        List<LocalizedText> alternatives = validateTexts(
                request.getAlternativeLabel(), "alternativeLabel",
                request.hasAlternativeLabel(), false);
        List<LocalizedText> hidden = validateTexts(request.getHiddenLabel(),
                "hiddenLabel", request.hasHiddenLabel(), false);
        List<LocalizedText> definitions = validateTexts(request.getDefinition(),
                "definition", request.hasDefinition(), false);
        List<IRI> senses = validateIris(request.getSenseId(), "senseId",
                request.hasSenseId(), "INVALID_SENSE_IRI");
        IRI parent = optionalIri(request.getParent(), "parent",
                request.hasParent(), "INVALID_PARENT_IRI");
        IRI conceptSet = optionalIri(request.getConceptSetId(), "conceptSetId",
                request.hasConceptSetId(), "INVALID_CONCEPT_SET_IRI");
        if (parent != null && parent.equals(concept)) {
            throw invalid("INVALID_PARENT", "a lexical concept cannot be its own parent");
        }
        String expected = null;
        if (request.hasExpectedModified()) {
            expected = requireText(request.getExpectedModified(),
                    "INVALID_EXPECTED_MODIFIED",
                    "expectedModified must not be blank");
        }
        return new ValidatedRequest(concept, labels, request.hasLabel(),
                alternatives, request.hasAlternativeLabel(), hidden,
                request.hasHiddenLabel(), definitions, request.hasDefinition(),
                senses, request.hasSenseId(), parent, request.hasParent(),
                conceptSet, request.hasConceptSetId(), expected);
    }

    private List<LocalizedText> validateTexts(List<LexicalConceptLabel> values,
                                              String field, boolean present,
                                              boolean requiredNonEmpty) {
        if (!present) {
            return Collections.emptyList();
        }
        if (values == null) {
            throw invalid("INVALID_LABEL", field + " must be an array");
        }
        if (requiredNonEmpty && values.isEmpty()) {
            throw invalid("MISSING_LABEL", field + " must not be empty");
        }
        List<LocalizedText> result = new ArrayList<LocalizedText>();
        for (int i = 0; i < values.size(); i++) {
            LexicalConceptLabel value = values.get(i);
            String path = field + "[" + i + "]";
            if (value == null) {
                throw invalid("INVALID_LABEL", path + " must be an object");
            }
            requireText(value.label, "MISSING_LABEL_VALUE",
                    path + ".label is required");
            String text = value.label;
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

    private List<IRI> validateIris(List<String> values, String field,
                                   boolean present, String code) {
        if (!present) {
            return Collections.emptyList();
        }
        if (values == null) {
            throw invalid("INVALID_SENSE_LIST", field + " must be an array");
        }
        List<IRI> result = new ArrayList<IRI>();
        for (int i = 0; i < values.size(); i++) {
            result.add(requireIri(field + "[" + i + "]", values.get(i), code));
        }
        return result;
    }

    private IRI optionalIri(String value, String field, boolean present,
                            String code) {
        if (!present || value == null) {
            return null;
        }
        return requireIri(field, requireText(value, code,
                field + " must be an absolute IRI or null"), code);
    }

    private void validateTarget(RepositoryConnection connection, IRI concept,
                                Resource graph) {
        if (!connection.hasStatement(concept, null, null, false, graph)) {
            throw failure(404, "LEXICAL_CONCEPT_NOT_FOUND",
                    concept.stringValue() + " does not exist in the lexical concept graph");
        }
        if (!connection.hasStatement(concept, RDF.TYPE,
                vf.createIRI(ONTOLEX + "LexicalConcept"), false, graph)) {
            throw failure(422, "INVALID_LEXICAL_CONCEPT_TYPE",
                    concept.stringValue() + " is not an ontolex:LexicalConcept");
        }
    }

    private void validateLinks(RepositoryConnection connection,
                               ValidatedRequest input, Resource graph) {
        if (input.sensesPresent) {
            for (IRI sense : input.senses) {
                validateResource(connection, sense,
                        vf.createIRI(ONTOLEX + "LexicalSense"), graph,
                        "SENSE_NOT_FOUND", "INVALID_SENSE_TYPE");
            }
        }
        if (input.parentPresent && input.parent != null) {
            validateResource(connection, input.parent,
                    vf.createIRI(ONTOLEX + "LexicalConcept"), graph,
                    "PARENT_NOT_FOUND", "INVALID_PARENT_TYPE");
        }
        if (input.conceptSetPresent && input.conceptSet != null) {
            validateResource(connection, input.conceptSet,
                    vf.createIRI(ONTOLEX + "ConceptSet"), graph,
                    "CONCEPT_SET_NOT_FOUND", "INVALID_CONCEPT_SET_TYPE");
        }
    }

    private void validateResource(RepositoryConnection connection, IRI resource,
                                  IRI expectedType, Resource graph,
                                  String missingCode, String typeCode) {
        if (!connection.hasStatement(resource, null, null, false, graph)) {
            throw failure(404, missingCode,
                    resource.stringValue() + " does not exist in the lexical concept graph");
        }
        if (!connection.hasStatement(resource, RDF.TYPE, expectedType,
                false, graph)) {
            throw failure(422, typeCode,
                    resource.stringValue() + " must be typed as "
                            + expectedType.stringValue());
        }
    }

    private void validateExpectedModified(RepositoryConnection connection,
                                          IRI concept, String expected,
                                          Resource graph) {
        if (expected != null && !connection.hasStatement(concept,
                DCTERMS.MODIFIED, vf.createLiteral(expected, XSD.DATETIME),
                false, graph)) {
            throw failure(409, "MODIFIED_MISMATCH",
                    "expectedModified does not match the stored value");
        }
    }

    private void replaceTexts(RepositoryConnection connection, IRI subject,
                              IRI predicate, List<LocalizedText> values,
                              boolean present, Resource graph) {
        if (!present) {
            return;
        }
        connection.remove(subject, predicate, null, graph);
        for (LocalizedText value : values) {
            connection.add(subject, predicate,
                    vf.createLiteral(value.text, value.language), graph);
        }
    }

    private void replaceIris(RepositoryConnection connection, IRI subject,
                             IRI predicate, List<IRI> values, boolean present,
                             Resource graph) {
        if (!present) {
            return;
        }
        connection.remove(subject, predicate, null, graph);
        for (IRI value : values) {
            connection.add(subject, predicate, value, graph);
        }
    }

    private void replaceIri(RepositoryConnection connection, IRI subject,
                            IRI predicate, IRI value, boolean present,
                            Resource graph) {
        if (!present) {
            return;
        }
        connection.remove(subject, predicate, null, graph);
        if (value != null) {
            connection.add(subject, predicate, value, graph);
        }
    }

    private LexicalConceptUpdateResult result(RepositoryConnection connection,
                                               IRI concept, String author,
                                               String modified,
                                               Resource graph) {
        LexicalConceptUpdateResult result = new LexicalConceptUpdateResult();
        result.lexicalConcept = concept.stringValue();
        result.author = author;
        result.modified = modified;
        result.label = texts(connection, concept,
                vf.createIRI(SKOS + "prefLabel"), graph);
        result.alternativeLabel = texts(connection, concept,
                vf.createIRI(SKOS + "alternativeLabel"), graph);
        result.hiddenLabel = texts(connection, concept,
                vf.createIRI(SKOS + "hiddenLabel"), graph);
        result.definition = texts(connection, concept,
                vf.createIRI(SKOS + "definition"), graph);
        result.senseId = iris(connection, concept,
                vf.createIRI(ONTOLEX + "lexicalizedSense"), graph);
        result.parent = first(resultIris(connection, concept,
                vf.createIRI(SKOS + "broader"), graph));
        result.conceptSetId = first(resultIris(connection, concept,
                vf.createIRI(SKOS + "inScheme"), graph));
        return result;
    }

    private List<LexicalConceptLabel> texts(RepositoryConnection connection,
                                            IRI subject, IRI predicate,
                                            Resource graph) {
        List<LexicalConceptLabel> result = new ArrayList<LexicalConceptLabel>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof Literal) {
                    Literal literal = (Literal) value;
                    result.add(new LexicalConceptLabel(literal.getLabel(),
                            literal.getLanguage().orElse(null)));
                }
            }
        }
        Collections.sort(result, new Comparator<LexicalConceptLabel>() {
            @Override
            public int compare(LexicalConceptLabel left,
                               LexicalConceptLabel right) {
                String leftLanguage = left.language == null ? "" : left.language;
                String rightLanguage = right.language == null ? "" : right.language;
                int language = leftLanguage.compareTo(rightLanguage);
                return language != 0 ? language : left.label.compareTo(right.label);
            }
        });
        return result;
    }

    private List<String> iris(RepositoryConnection connection, IRI subject,
                              IRI predicate, Resource graph) {
        return new ArrayList<String>(resultIris(connection, subject, predicate, graph));
    }

    private SortedSet<String> resultIris(RepositoryConnection connection,
                                         IRI subject, IRI predicate,
                                         Resource graph) {
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
        return result;
    }

    private String first(SortedSet<String> values) {
        return values.isEmpty() ? null : values.first();
    }

    private IRI requireIri(String field, String value, String code) {
        String normalized = requireText(value, code,
                field + " must be an absolute IRI").trim();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw invalid(code, field + " must be an absolute IRI");
            }
            return vf.createIRI(normalized);
        } catch (URISyntaxException e) {
            throw invalid(code, field + " must be an absolute IRI");
        }
    }

    private String requireText(String value, String code, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(code, message);
        }
        return value.trim();
    }

    private IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private ConceptUpdateException failure(int status, String code,
                                           String message) {
        return new ConceptUpdateException(status, code + ": " + message);
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
    public static final class ConceptUpdateException extends RuntimeException {
        public final int httpStatus;

        ConceptUpdateException(int httpStatus, String message) {
            super(message);
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
        final IRI concept;
        final List<LocalizedText> labels;
        final boolean labelsPresent;
        final List<LocalizedText> alternativeLabels;
        final boolean alternativeLabelsPresent;
        final List<LocalizedText> hiddenLabels;
        final boolean hiddenLabelsPresent;
        final List<LocalizedText> definitions;
        final boolean definitionsPresent;
        final List<IRI> senses;
        final boolean sensesPresent;
        final IRI parent;
        final boolean parentPresent;
        final IRI conceptSet;
        final boolean conceptSetPresent;
        final String expectedModified;

        ValidatedRequest(IRI concept, List<LocalizedText> labels,
                         boolean labelsPresent,
                         List<LocalizedText> alternativeLabels,
                         boolean alternativeLabelsPresent,
                         List<LocalizedText> hiddenLabels,
                         boolean hiddenLabelsPresent,
                         List<LocalizedText> definitions,
                         boolean definitionsPresent, List<IRI> senses,
                         boolean sensesPresent, IRI parent,
                         boolean parentPresent, IRI conceptSet,
                         boolean conceptSetPresent, String expectedModified) {
            this.concept = concept;
            this.labels = labels;
            this.labelsPresent = labelsPresent;
            this.alternativeLabels = alternativeLabels;
            this.alternativeLabelsPresent = alternativeLabelsPresent;
            this.hiddenLabels = hiddenLabels;
            this.hiddenLabelsPresent = hiddenLabelsPresent;
            this.definitions = definitions;
            this.definitionsPresent = definitionsPresent;
            this.senses = senses;
            this.sensesPresent = sensesPresent;
            this.parent = parent;
            this.parentPresent = parentPresent;
            this.conceptSet = conceptSet;
            this.conceptSetPresent = conceptSetPresent;
            this.expectedModified = expectedModified;
        }
    }
}
