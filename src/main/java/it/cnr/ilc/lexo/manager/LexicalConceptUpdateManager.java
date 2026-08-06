package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptLabel;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptSenseLink;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptUpdateRequest;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptUpdateResult;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
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

            IRI preferredLabel = vf.createIRI(SKOS + "prefLabel");
            applyTextChanges(connection, input.concept, preferredLabel,
                    input.labels, graph);
            applyTextChanges(connection, input.concept,
                    vf.createIRI(SKOS + "alternativeLabel"),
                    input.alternativeLabels, graph);
            applyTextChanges(connection, input.concept,
                    vf.createIRI(SKOS + "hiddenLabel"), input.hiddenLabels,
                    graph);
            applyTextChanges(connection, input.concept,
                    vf.createIRI(SKOS + "definition"), input.definitions,
                    graph);
            if (input.labels.hasChanges()
                    && !connection.hasStatement(input.concept, preferredLabel,
                            null, false, graph)) {
                throw invalid("MISSING_LABEL",
                        "a lexical concept must retain at least one preferred label");
            }
            if (input.hasSenseChanges()) {
                connection.remove(input.concept,
                        vf.createIRI(ONTOLEX + "isLexicalizedSenseOf"),
                        null, graph);
            }
            IRI lexicalizedSense = vf.createIRI(
                    ONTOLEX + "lexicalizedSense");
            if (input.sensesPresent) {
                replaceIris(connection, input.concept, lexicalizedSense,
                        senseIris(input.senses), true, graph);
            } else {
                removeIris(connection, input.concept, lexicalizedSense,
                        input.removeSenseIds, graph);
                addSenseLinks(connection, input.concept, lexicalizedSense,
                        input.addSenses, graph);
            }
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
        if (!hasRequestedTextChange(request)
                && !request.hasSenses() && !request.hasAddSenses()
                && !request.hasRemoveSenseIds()
                && !request.hasLegacySenseId()
                && !request.hasParent()
                && !request.hasConceptSetId()) {
            throw invalid("MISSING_LEXICAL_CONCEPT_CHANGES",
                    "at least one mutable field must be supplied");
        }
        TextChanges labels = validateTextChanges(request.getLabel(),
                request.hasLabel(), request.getAddLabels(),
                request.hasAddLabels(), request.getRemoveLabels(),
                request.hasRemoveLabels(), "label", "addLabels",
                "removeLabels", true);
        TextChanges alternatives = validateTextChanges(
                request.getAlternativeLabel(), request.hasAlternativeLabel(),
                request.getAddAlternativeLabels(),
                request.hasAddAlternativeLabels(),
                request.getRemoveAlternativeLabels(),
                request.hasRemoveAlternativeLabels(), "alternativeLabel",
                "addAlternativeLabels", "removeAlternativeLabels", false);
        TextChanges hidden = validateTextChanges(request.getHiddenLabel(),
                request.hasHiddenLabel(), request.getAddHiddenLabels(),
                request.hasAddHiddenLabels(), request.getRemoveHiddenLabels(),
                request.hasRemoveHiddenLabels(), "hiddenLabel",
                "addHiddenLabels", "removeHiddenLabels", false);
        TextChanges definitions = validateTextChanges(request.getDefinition(),
                request.hasDefinition(), request.getAddDefinitions(),
                request.hasAddDefinitions(), request.getRemoveDefinitions(),
                request.hasRemoveDefinitions(), "definition",
                "addDefinitions", "removeDefinitions", false);
        if (request.hasLegacySenseId()) {
            throw invalid("SENSE_LANGUAGE_REQUIRED",
                    "senseId is unsupported; use senses with senseId and language");
        }
        if (request.hasSenses()
                && (request.hasAddSenses() || request.hasRemoveSenseIds())) {
            throw invalid("CONFLICTING_SENSE_OPERATIONS",
                    "senses cannot be combined with addSenses or removeSenseIds");
        }
        List<ValidatedSenseLink> senses = validateSenses(request.getSenses(),
                request.hasSenses(), "senses");
        List<ValidatedSenseLink> addSenses = validateSenses(
                request.getAddSenses(), request.hasAddSenses(), "addSenses");
        List<IRI> removeSenseIds = validateSenseIris(
                request.getRemoveSenseIds(), request.hasRemoveSenseIds());
        rejectConflictingSenseChanges(addSenses, removeSenseIds);
        if (!labels.hasChanges() && !alternatives.hasChanges()
                && !hidden.hasChanges() && !definitions.hasChanges()
                && !request.hasSenses() && addSenses.isEmpty()
                && removeSenseIds.isEmpty() && !request.hasParent()
                && !request.hasConceptSetId()) {
            throw invalid("MISSING_LEXICAL_CONCEPT_CHANGES",
                    "empty incremental lists do not modify the concept");
        }
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
        return new ValidatedRequest(concept, labels, alternatives, hidden,
                definitions,
                senses, request.hasSenses(), addSenses, removeSenseIds,
                parent, request.hasParent(),
                conceptSet, request.hasConceptSetId(), expected);
    }

    private boolean hasRequestedTextChange(
            LexicalConceptUpdateRequest request) {
        return request.hasLabel() || request.hasAddLabels()
                || request.hasRemoveLabels()
                || request.hasAlternativeLabel()
                || request.hasAddAlternativeLabels()
                || request.hasRemoveAlternativeLabels()
                || request.hasHiddenLabel() || request.hasAddHiddenLabels()
                || request.hasRemoveHiddenLabels()
                || request.hasDefinition() || request.hasAddDefinitions()
                || request.hasRemoveDefinitions();
    }

    private TextChanges validateTextChanges(
            List<LexicalConceptLabel> replacement, boolean replacementPresent,
            List<LexicalConceptLabel> additions, boolean additionsPresent,
            List<LexicalConceptLabel> removals, boolean removalsPresent,
            String replacementField, String additionField,
            String removalField, boolean replacementRequiredNonEmpty) {
        if (replacementPresent && (additionsPresent || removalsPresent)) {
            throw invalid("CONFLICTING_TEXT_OPERATIONS", replacementField
                    + " cannot be combined with " + additionField + " or "
                    + removalField);
        }
        List<LocalizedText> validatedReplacement = validateTexts(replacement,
                replacementField, replacementPresent,
                replacementRequiredNonEmpty);
        List<LocalizedText> validatedAdditions = validateTexts(additions,
                additionField, additionsPresent, false);
        List<LocalizedText> validatedRemovals = validateTexts(removals,
                removalField, removalsPresent, false);
        rejectConflictingTextChanges(validatedAdditions, validatedRemovals,
                replacementField);
        return new TextChanges(validatedReplacement, replacementPresent,
                validatedAdditions, validatedRemovals);
    }

    private void rejectConflictingTextChanges(List<LocalizedText> additions,
                                              List<LocalizedText> removals,
                                              String field) {
        Set<String> added = new HashSet<String>();
        for (LocalizedText addition : additions) {
            added.add(textKey(addition));
        }
        for (LocalizedText removal : removals) {
            if (added.contains(textKey(removal))) {
                throw invalid("CONFLICTING_TEXT_CHANGE", field
                        + " contains the same value in add and remove operations");
            }
        }
    }

    private String textKey(LocalizedText value) {
        return value.language + "\u0000" + value.text;
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

    private List<ValidatedSenseLink> validateSenses(
            List<LexicalConceptSenseLink> values, boolean present,
            String field) {
        if (!present) {
            return Collections.emptyList();
        }
        if (values == null) {
            throw invalid("INVALID_SENSE_LIST", field + " must be an array");
        }
        List<ValidatedSenseLink> result =
                new ArrayList<ValidatedSenseLink>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < values.size(); i++) {
            LexicalConceptSenseLink value = values.get(i);
            String path = field + "[" + i + "]";
            if (value == null) {
                throw invalid("INVALID_SENSE_LINK", path + " must be an object");
            }
            IRI sense = requireIri(path + ".senseId", value.senseId,
                    "INVALID_SENSE_IRI");
            String language;
            try {
                language = Iso639LanguageValidator.get()
                        .requireValid(value.language);
            } catch (IllegalArgumentException e) {
                throw invalid("INVALID_SENSE_LANGUAGE",
                        path + ".language must be a supported ISO 639 code");
            }
            if (!seen.add(sense.stringValue())) {
                throw invalid("DUPLICATE_SENSE",
                        sense.stringValue() + " occurs more than once");
            }
            result.add(new ValidatedSenseLink(sense, language));
        }
        return result;
    }

    private List<IRI> validateSenseIris(List<String> values,
                                        boolean present) {
        if (!present) {
            return Collections.emptyList();
        }
        if (values == null) {
            throw invalid("INVALID_REMOVE_SENSE_LIST",
                    "removeSenseIds must be an array");
        }
        List<IRI> result = new ArrayList<IRI>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < values.size(); i++) {
            IRI sense = requireIri("removeSenseIds[" + i + "]",
                    values.get(i), "INVALID_REMOVE_SENSE_IRI");
            if (!seen.add(sense.stringValue())) {
                throw invalid("DUPLICATE_REMOVE_SENSE",
                        sense.stringValue() + " occurs more than once");
            }
            result.add(sense);
        }
        return result;
    }

    private void rejectConflictingSenseChanges(
            List<ValidatedSenseLink> additions, List<IRI> removals) {
        Set<String> added = new HashSet<String>();
        for (ValidatedSenseLink addition : additions) {
            added.add(addition.sense.stringValue());
        }
        for (IRI removal : removals) {
            if (added.contains(removal.stringValue())) {
                throw invalid("CONFLICTING_SENSE_CHANGE",
                        removal.stringValue()
                                + " cannot be added and removed together");
            }
        }
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
            for (ValidatedSenseLink sense : input.senses) {
                validateSense(connection, sense);
            }
        }
        if (!input.addSenses.isEmpty()) {
            for (ValidatedSenseLink sense : input.addSenses) {
                validateSense(connection, sense);
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

    private void validateSense(RepositoryConnection connection,
                               ValidatedSenseLink link) {
        Resource languageGraph = vf.createIRI(
                LexiconCrudSupport.lexicalGraphUri(link.language));
        Resource schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
        if (!connection.hasStatement(link.sense, null, null, false,
                languageGraph)) {
            throw failure(404, "SENSE_NOT_FOUND",
                    link.sense.stringValue() + " does not exist in the "
                            + link.language + " lexical graph");
        }
        try (RepositoryResult<Statement> types = connection.getStatements(
                link.sense, RDF.TYPE, null, false, languageGraph)) {
            while (types.hasNext()) {
                Value type = types.next().getObject();
                if (type instanceof IRI && LexiconCrudSupport.isSubclassOf(
                        connection, (IRI) type,
                        vf.createIRI(ONTOLEX + "LexicalSense"), languageGraph,
                        schemaGraph)) {
                    return;
                }
            }
        }
        throw failure(422, "INVALID_SENSE_TYPE",
                link.sense.stringValue()
                        + " must be an ontolex:LexicalSense in the "
                        + link.language + " lexical graph");
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

    private void applyTextChanges(RepositoryConnection connection, IRI subject,
                                  IRI predicate, TextChanges changes,
                                  Resource graph) {
        if (changes.replacementPresent) {
            connection.remove(subject, predicate, null, graph);
            addTexts(connection, subject, predicate, changes.replacement,
                    graph);
            return;
        }
        for (LocalizedText value : changes.removals) {
            connection.remove(subject, predicate,
                    vf.createLiteral(value.text, value.language), graph);
        }
        addTexts(connection, subject, predicate, changes.additions, graph);
    }

    private void addTexts(RepositoryConnection connection, IRI subject,
                          IRI predicate, List<LocalizedText> values,
                          Resource graph) {
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

    private List<IRI> senseIris(List<ValidatedSenseLink> senses) {
        List<IRI> result = new ArrayList<IRI>();
        for (ValidatedSenseLink sense : senses) {
            result.add(sense.sense);
        }
        return result;
    }

    private void removeIris(RepositoryConnection connection, IRI subject,
                            IRI predicate, List<IRI> values, Resource graph) {
        for (IRI value : values) {
            connection.remove(subject, predicate, value, graph);
        }
    }

    private void addSenseLinks(RepositoryConnection connection, IRI subject,
                               IRI predicate,
                               List<ValidatedSenseLink> values,
                               Resource graph) {
        for (ValidatedSenseLink value : values) {
            connection.add(subject, predicate, value.sense, graph);
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
        final TextChanges labels;
        final TextChanges alternativeLabels;
        final TextChanges hiddenLabels;
        final TextChanges definitions;
        final List<ValidatedSenseLink> senses;
        final boolean sensesPresent;
        final List<ValidatedSenseLink> addSenses;
        final List<IRI> removeSenseIds;
        final IRI parent;
        final boolean parentPresent;
        final IRI conceptSet;
        final boolean conceptSetPresent;
        final String expectedModified;

        ValidatedRequest(IRI concept, TextChanges labels,
                         TextChanges alternativeLabels,
                         TextChanges hiddenLabels,
                         TextChanges definitions,
                         List<ValidatedSenseLink> senses,
                         boolean sensesPresent,
                         List<ValidatedSenseLink> addSenses,
                         List<IRI> removeSenseIds,
                         IRI parent,
                         boolean parentPresent, IRI conceptSet,
                         boolean conceptSetPresent, String expectedModified) {
            this.concept = concept;
            this.labels = labels;
            this.alternativeLabels = alternativeLabels;
            this.hiddenLabels = hiddenLabels;
            this.definitions = definitions;
            this.senses = senses;
            this.sensesPresent = sensesPresent;
            this.addSenses = addSenses;
            this.removeSenseIds = removeSenseIds;
            this.parent = parent;
            this.parentPresent = parentPresent;
            this.conceptSet = conceptSet;
            this.conceptSetPresent = conceptSetPresent;
            this.expectedModified = expectedModified;
        }

        boolean hasSenseChanges() {
            return sensesPresent || !addSenses.isEmpty()
                    || !removeSenseIds.isEmpty();
        }
    }

    private static final class TextChanges {
        final List<LocalizedText> replacement;
        final boolean replacementPresent;
        final List<LocalizedText> additions;
        final List<LocalizedText> removals;

        TextChanges(List<LocalizedText> replacement,
                    boolean replacementPresent,
                    List<LocalizedText> additions,
                    List<LocalizedText> removals) {
            this.replacement = replacement;
            this.replacementPresent = replacementPresent;
            this.additions = additions;
            this.removals = removals;
        }

        boolean hasChanges() {
            return replacementPresent || !additions.isEmpty()
                    || !removals.isEmpty();
        }
    }

    private static final class ValidatedSenseLink {
        final IRI sense;
        final String language;

        ValidatedSenseLink(IRI sense, String language) {
            this.sense = sense;
            this.language = language;
        }
    }
}
