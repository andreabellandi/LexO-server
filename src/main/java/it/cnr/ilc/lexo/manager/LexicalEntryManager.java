package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalRdfValue;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalSenseCreation;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalSenseProperty;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryCreationResult;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Validates and atomically creates lexical entries in language-specific graphs. */
public final class LexicalEntryManager implements Manager {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LIME = "http://www.w3.org/ns/lemon/lime#";
    private static final String LEXINFO =
            "http://www.lexinfo.net/ontology/3.0/lexinfo#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String LEXO = "https://lexo.ilc.cnr.it#";
    private static final String WORKING = "working";
    private static final Set<String> RESERVED_METADATA_PROPERTIES =
            reservedMetadataProperties();
    private static final Set<String> MANAGED_SENSE_PROPERTIES =
            managedSenseProperties();

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final Repository repository;

    /** Runtime constructor used by {@link ManagerFactory}. */
    public LexicalEntryManager() {
        this(null);
    }

    LexicalEntryManager(Repository repository) {
        this.repository = repository;
    }

    /** Creates one lexical entry and all requested child resources atomically. */
    public LexicalEntryCreationResult create(LexicalEntryCreationRequest request,
                                             String author) {
        ValidatedRequest input = validateRequest(request);
        String creator = LexiconCrudSupport.author(author);
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            Resource graph = vf.createIRI(
                    LexiconCrudSupport.lexicalGraphUri(input.language));
            Resource schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
            validateEntryType(connection, input.type, graph, schemaGraph);
            validatePartOfSpeech(connection, input.pos, graph, schemaGraph);

            IRI lexicon = findLexicon(connection, graph, input.language);
            boolean lexiconCreated = lexicon == null;
            Set<String> generated = new HashSet<String>();
            if (lexiconCreated) {
                lexicon = newIri(generated);
            }
            IRI entry = newIri(generated);
            IRI form = Boolean.TRUE.equals(request.lemma) ? newIri(generated) : null;
            List<IRI> senses = new ArrayList<IRI>();
            for (int i = 0; i < input.senses.size(); i++) {
                senses.add(newIri(generated));
            }

            String timestamp = LexiconCrudSupport.operationTimestamp();
            Literal date = vf.createLiteral(timestamp, XSD.DATETIME);
            Literal creatorValue = vf.createLiteral(creator);
            Model model = new LinkedHashModel();

            if (lexiconCreated) {
                model.add(lexicon, RDF.TYPE, iri(LIME + "Lexicon"));
                model.add(lexicon, iri(LIME + "language"),
                        vf.createLiteral(input.language));
                addAudit(model, lexicon, creatorValue, date);
                model.add(lexicon, iri(LEXO + "status"), vf.createLiteral(WORKING));
            }
            model.add(lexicon, iri(LIME + "entry"), entry);

            model.add(entry, RDF.TYPE, input.type);
            addAudit(model, entry, creatorValue, date);
            model.add(entry, RDFS.LABEL,
                    vf.createLiteral(request.label, input.language));
            model.add(entry, iri(LEXO + "status"), vf.createLiteral(WORKING));
            if (input.pos != null) {
                model.add(entry, iri(LEXINFO + "partOfSpeech"), input.pos);
            }

            if (form != null) {
                model.add(entry, iri(ONTOLEX + "canonicalForm"), form);
                model.add(form, RDF.TYPE, iri(ONTOLEX + "Form"));
                addAudit(model, form, creatorValue, date);
                model.add(form, iri(ONTOLEX + "writtenRep"),
                        vf.createLiteral(request.label, input.language));
            }

            for (int i = 0; i < senses.size(); i++) {
                IRI sense = senses.get(i);
                model.add(entry, iri(ONTOLEX + "sense"), sense);
                model.add(sense, RDF.TYPE, iri(ONTOLEX + "LexicalSense"));
                addAudit(model, sense, creatorValue, date);
                for (PropertyValue value : input.senses.get(i).values) {
                    model.add(sense, value.property, value.value);
                }
            }

            connection.add(model, graph);
            connection.commit();
            return result(lexicon, lexiconCreated, entry, form, senses,
                    input.language, timestamp);
        } catch (RuntimeException e) {
            if (connection.isActive()) {
                connection.rollback();
            }
            throw e;
        } finally {
            release(connection);
        }
    }

    private ValidatedRequest validateRequest(LexicalEntryCreationRequest request) {
        if (request == null) {
            throw invalid("MISSING_ENTRY", "request body is required");
        }
        requireText(request.label, "MISSING_LABEL", "label is required");
        String typeValue = requireText(request.type, "MISSING_TYPE", "type is required");
        String language = Iso639LanguageValidator.get()
                .requireValid(request.language);
        IRI type = requireIri("type",
                LexiconCrudSupport.expandLexicalIri(typeValue), "INVALID_ENTRY_TYPE_IRI");
        IRI pos = null;
        if (request.pos != null && !request.pos.trim().isEmpty()) {
            pos = requireIri("pos", LexiconCrudSupport.expandLexicalIri(
                    request.pos.trim()), "INVALID_PART_OF_SPEECH_IRI");
        }
        List<ValidatedSense> senses = new ArrayList<ValidatedSense>();
        if (request.senses != null) {
            for (int i = 0; i < request.senses.size(); i++) {
                LexicalSenseCreation sense = request.senses.get(i);
                if (sense == null) {
                    throw invalid("INVALID_SENSE", "sense at index " + i
                            + " must be an object");
                }
                senses.add(validateSense(sense, i));
            }
        }
        return new ValidatedRequest(language, type, pos, senses);
    }

    private ValidatedSense validateSense(LexicalSenseCreation sense, int index) {
        List<PropertyValue> values = new ArrayList<PropertyValue>();
        if (sense.properties != null) {
            for (int i = 0; i < sense.properties.size(); i++) {
                LexicalSenseProperty property = sense.properties.get(i);
                if (property == null) {
                    throw invalid("INVALID_SENSE_PROPERTY", "property at sense "
                            + index + ", index " + i + " must be an object");
                }
                addPropertyValues(values, property.property, property.values,
                        false, "senses[" + index + "].properties[" + i + "]");
            }
        }
        if (sense.metadata != null) {
            for (Map.Entry<String, List<LexicalRdfValue>> metadata
                    : sense.metadata.entrySet()) {
                addPropertyValues(values, metadata.getKey(), metadata.getValue(),
                        true, "senses[" + index + "].metadata");
            }
        }
        return new ValidatedSense(values);
    }

    private void addPropertyValues(List<PropertyValue> target, String propertyValue,
                                   List<LexicalRdfValue> values, boolean metadata,
                                   String path) {
        IRI property = requireIri(path + ".property", propertyValue,
                "INVALID_SENSE_PROPERTY_IRI");
        if (metadata && RESERVED_METADATA_PROPERTIES.contains(property.stringValue())) {
            throw invalid("RESERVED_SENSE_METADATA_PROPERTY",
                    property.stringValue() + " is structural and cannot be metadata");
        }
        if (!metadata && MANAGED_SENSE_PROPERTIES.contains(property.stringValue())) {
            throw invalid("MANAGED_SENSE_PROPERTY",
                    property.stringValue() + " is managed by the service");
        }
        if (values == null || values.isEmpty()) {
            throw invalid("MISSING_SENSE_PROPERTY_VALUES",
                    path + ".values must not be empty");
        }
        for (int i = 0; i < values.size(); i++) {
            target.add(new PropertyValue(property,
                    rdfValue(values.get(i), path + ".values[" + i + "]")));
        }
    }

    private Value rdfValue(LexicalRdfValue value, String path) {
        if (value == null) {
            throw invalid("INVALID_RDF_VALUE", path + " must be an object");
        }
        if (value.value == null) {
            throw invalid("MISSING_RDF_VALUE", path + ".value is required");
        }
        String kind = requireText(value.type, "MISSING_RDF_VALUE_TYPE",
                path + ".type is required").toLowerCase(Locale.ROOT);
        if ("iri".equals(kind)) {
            if (notBlank(value.language) || notBlank(value.datatype)) {
                throw invalid("INVALID_IRI_VALUE",
                        path + " cannot define language or datatype for an IRI");
            }
            return requireIri(path + ".value", value.value, "INVALID_IRI_VALUE");
        }
        if (!"literal".equals(kind)) {
            throw invalid("INVALID_RDF_VALUE_TYPE",
                    path + ".type must be literal or iri");
        }
        if (notBlank(value.language) && notBlank(value.datatype)) {
            throw invalid("INVALID_LITERAL_VALUE",
                    path + " cannot define both language and datatype");
        }
        if (notBlank(value.language)) {
            String language = value.language.trim();
            if (!language.matches("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*")) {
                throw invalid("INVALID_LITERAL_LANGUAGE",
                        path + ".language is not a valid language tag");
            }
            return vf.createLiteral(value.value, language);
        }
        if (notBlank(value.datatype)) {
            return vf.createLiteral(value.value, requireIri(path + ".datatype",
                    value.datatype.trim(), "INVALID_LITERAL_DATATYPE"));
        }
        return vf.createLiteral(value.value);
    }

    private void validateEntryType(RepositoryConnection connection, IRI type,
                                   Resource graph, Resource schemaGraph) {
        if (!isSubclassOf(connection, type, iri(ONTOLEX + "LexicalEntry"),
                new HashSet<String>(), graph, schemaGraph)) {
            throw invalid("INVALID_ENTRY_TYPE",
                    "type must be ontolex:LexicalEntry or one of its subclasses");
        }
    }

    private void validatePartOfSpeech(RepositoryConnection connection, IRI pos,
                                      Resource graph, Resource schemaGraph) {
        if (pos == null) {
            return;
        }
        IRI partOfSpeech = iri(LEXINFO + "PartOfSpeech");
        try (RepositoryResult<Statement> types = connection.getStatements(
                pos, RDF.TYPE, null, false, graph, schemaGraph)) {
            while (types.hasNext()) {
                Value type = types.next().getObject();
                if (type instanceof IRI && isSubclassOf(connection, (IRI) type,
                        partOfSpeech, new HashSet<String>(), graph, schemaGraph)) {
                    return;
                }
            }
        }
        throw invalid("INVALID_PART_OF_SPEECH",
                "pos must identify a lexinfo:PartOfSpeech individual");
    }

    private boolean isSubclassOf(RepositoryConnection connection, IRI candidate,
                                 IRI expected, Set<String> visited,
                                 Resource graph, Resource schemaGraph) {
        if (candidate.equals(expected)) {
            return true;
        }
        if (!visited.add(candidate.stringValue())) {
            return false;
        }
        try (RepositoryResult<Statement> parents = connection.getStatements(
                candidate, RDFS.SUBCLASSOF, null, false, graph, schemaGraph)) {
            while (parents.hasNext()) {
                Value parent = parents.next().getObject();
                if (parent instanceof IRI && isSubclassOf(connection, (IRI) parent,
                        expected, visited, graph, schemaGraph)) {
                    return true;
                }
            }
        }
        return false;
    }

    private IRI findLexicon(RepositoryConnection connection, Resource graph,
                            String language) {
        Set<String> candidates = new TreeSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, RDF.TYPE, iri(LIME + "Lexicon"), false, graph)) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (subject instanceof IRI) {
                    candidates.add(subject.stringValue());
                }
            }
        }
        for (String candidate : candidates) {
            IRI lexicon = iri(candidate);
            if (hasLanguage(connection, lexicon, iri(LIME + "language"),
                    language, graph)
                    || hasLanguage(connection, lexicon, DCTERMS.LANGUAGE,
                            language, graph)) {
                return lexicon;
            }
        }
        return null;
    }

    private boolean hasLanguage(RepositoryConnection connection, IRI lexicon,
                                IRI predicate, String language, Resource graph) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                lexicon, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (value instanceof Literal
                        && language.equalsIgnoreCase(value.stringValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addAudit(Model model, Resource resource, Literal creator,
                          Literal timestamp) {
        model.add(resource, DCTERMS.CREATOR, creator);
        model.add(resource, DCTERMS.CREATED, timestamp);
        model.add(resource, DCTERMS.MODIFIED, timestamp);
    }

    private LexicalEntryCreationResult result(IRI lexicon, boolean lexiconCreated,
                                               IRI entry, IRI form,
                                               List<IRI> senses, String language,
                                               String timestamp) {
        LexicalEntryCreationResult result = new LexicalEntryCreationResult();
        result.lexicon = lexicon.stringValue();
        result.lexiconCreated = lexiconCreated;
        result.entry = entry.stringValue();
        result.canonicalForm = form == null ? null : form.stringValue();
        result.senses = new ArrayList<String>();
        for (IRI sense : senses) {
            result.senses.add(sense.stringValue());
        }
        result.language = language;
        result.status = WORKING;
        result.created = timestamp;
        return result;
    }

    private IRI newIri(Set<String> generated) {
        String value;
        do {
            value = LexiconCrudSupport.newResourceUri();
            if (generated.contains(value)) {
                Thread.yield();
            }
        } while (generated.contains(value));
        generated.add(value);
        return iri(value);
    }

    private IRI requireIri(String field, String value, String code) {
        String normalized = requireText(value, code, field + " must be an absolute IRI")
                .trim();
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

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
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

    private static Set<String> reservedMetadataProperties() {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                RDF.TYPE.stringValue(), RDF.VALUE.stringValue(),
                DCTERMS.CREATOR.stringValue(), DCTERMS.CREATED.stringValue(),
                DCTERMS.MODIFIED.stringValue(), SKOS + "definition",
                ONTOLEX + "reference", ONTOLEX + "isLexicalizedSenseOf")));
    }

    private static Set<String> managedSenseProperties() {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                RDF.TYPE.stringValue(), DCTERMS.CREATOR.stringValue(),
                DCTERMS.CREATED.stringValue(), DCTERMS.MODIFIED.stringValue(),
                LEXO + "status")));
    }

    private static final class ValidatedRequest {
        final String language;
        final IRI type;
        final IRI pos;
        final List<ValidatedSense> senses;

        ValidatedRequest(String language, IRI type, IRI pos,
                         List<ValidatedSense> senses) {
            this.language = language;
            this.type = type;
            this.pos = pos;
            this.senses = senses;
        }
    }

    private static final class ValidatedSense {
        final List<PropertyValue> values;

        ValidatedSense(List<PropertyValue> values) {
            this.values = values;
        }
    }

    private static final class PropertyValue {
        final IRI property;
        final Value value;

        PropertyValue(IRI property, Value value) {
            this.property = property;
            this.value = value;
        }
    }
}
