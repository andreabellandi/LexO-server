package it.cnr.ilc.lexo.manager.text;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.service.data.text.output.CorpusRecord;
import it.cnr.ilc.lexo.service.data.text.output.TextRecord;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RDF/NIF persistence in the repository selected as {@link RepositoryTarget#TEXT}. */
public final class TextNifRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextNifRepository.class);
    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static final String DOCO = "http://purl.org/spar/doco/";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    public static TextNifRepository get() {
        return Holder.INSTANCE;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final String graphBase;
    private final String structureNamespace;
    private final IRI recordsGraph;
    private final Repository memoryRepository;

    private TextNifRepository() {
        this(null);
    }

    TextNifRepository(Repository providedRepository) {
        graphBase = trailingSeparator(configured("TextGraphDb.namedGraphBase",
                "https://lexo.ilc.cnr.it/graphs/nif/"));
        recordsGraph = iri(graphBase + "records");
        structureNamespace = namespace(LexOProperties.getProperty(
                "lexo.text.structureNamespace",
                "https://lexo.ilc.cnr.it/vocabulary/nif-structure#"));
        if (providedRepository != null) {
            memoryRepository = providedRepository;
        } else if (Boolean.getBoolean("lexo.text.nifRepository.memory")) {
            memoryRepository = new SailRepository(new MemoryStore());
            memoryRepository.init();
        } else {
            memoryRepository = null;
            RepositoryConnection connection = acquire();
            try {
                connection.size();
            } finally {
                release(connection);
            }
            LOGGER.info("Text NIF repository initialized through GraphDbUtil: {}",
                    configured("TextGraphDb.repository", "LexOTexts"));
        }
    }

    public String documentGraphUri(String fileId) {
        return graphBase + "documents/" + fileId;
    }

    public String corpusGraphUri(String corpusId) {
        return graphBase + "corpora/" + corpusId;
    }

    public void saveDocument(String fileId, Model model, String documentContextUri,
                             String corpusId, String corpusUri) {
        saveDocument(fileId, model, documentContextUri, corpusId, corpusUri, null);
    }

    public void saveDocument(String fileId, Model model, String documentContextUri,
                             String corpusId, String corpusUri, TextRecord record) {
        IRI documentGraph = iri(documentGraphUri(fileId));
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            connection.clear(documentGraph);
            connection.add(model, documentGraph);
            if (record != null) {
                putRecord(connection, documentRecordResource(fileId), record);
            }
            if (corpusId != null) {
                IRI corpusGraph = iri(corpusGraphUri(corpusId));
                if (!connection.hasStatement(null, null, null, false, corpusGraph)) {
                    throw new IllegalArgumentException("Corpus NIF not found: " + corpusId);
                }
                connection.add(iri(documentContextUri), DCTERMS.IS_PART_OF,
                        iri(corpusUri), documentGraph);
                connection.add(iri(corpusUri), DCTERMS.HAS_PART,
                        iri(documentContextUri), corpusGraph);
            }
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    public void saveCorpus(String corpusId, Model model) {
        saveCorpus(corpusId, model, null);
    }

    public void saveCorpus(String corpusId, Model model, CorpusRecord record) {
        IRI graph = iri(corpusGraphUri(corpusId));
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            connection.clear(graph);
            connection.add(model, graph);
            if (record != null) {
                putRecord(connection, corpusRecordResource(corpusId), record);
            }
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    public TextRecord getDocumentRecord(String fileId) {
        Model model = readGraph(iri(documentGraphUri(fileId)));
        if (model.isEmpty()) {
            return null;
        }
        Resource context = subjectWith(model, iri(NIF + "isString"));
        TextRecord record = readRecord(documentRecordResource(fileId), TextRecord.class);
        if (record == null) {
            record = deriveDocumentRecord(fileId, model, context);
        }
        refreshDocumentMembership(record, model, context);
        return record;
    }

    public CorpusRecord getCorpusRecord(String corpusId) {
        Model model = readGraph(iri(corpusGraphUri(corpusId)));
        if (model.isEmpty()) {
            return null;
        }
        Resource corpus = subjectWith(model, iri(structureNamespace + "corpusId"));
        CorpusRecord record = readRecord(corpusRecordResource(corpusId), CorpusRecord.class);
        if (record == null) {
            record = deriveCorpusRecord(corpusId, model, corpus);
        }
        refreshCorpusMembership(record, model, corpus);
        return record;
    }

    public String getCanonicalText(String fileId) {
        Model model = readGraph(iri(documentGraphUri(fileId)));
        Resource context = subjectWith(model, iri(NIF + "isString"));
        return literal(model, context, iri(NIF + "isString"));
    }

    public boolean containsDocument(String fileId) {
        return containsGraph(iri(documentGraphUri(fileId)));
    }

    public boolean containsCorpus(String corpusId) {
        return containsGraph(iri(corpusGraphUri(corpusId)));
    }

    public String replaceDocumentTotal(String fileId, int value, IRI unit) {
        return replaceTotal(iri(documentGraphUri(fileId)),
                iri(structureNamespace + "fileId"), fileId, value, unit);
    }

    public String replaceCorpusTotal(String corpusId, int value, IRI unit) {
        return replaceTotal(iri(corpusGraphUri(corpusId)),
                iri(structureNamespace + "corpusId"), corpusId, value, unit);
    }

    public void writeDocument(String fileId, OutputStream output) {
        exportGraph(iri(documentGraphUri(fileId)), output);
    }

    public void writeCorpus(String corpusId, OutputStream output) {
        exportGraph(iri(corpusGraphUri(corpusId)), output);
    }

    public void deleteDocument(String fileId, String documentContextUri,
                               String corpusId, String corpusUri) {
        IRI documentGraph = iri(documentGraphUri(fileId));
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            connection.clear(documentGraph);
            connection.remove(documentRecordResource(fileId), null, null, recordsGraph);
            connection.remove((Resource) null, DCTERMS.HAS_PART,
                    iri(documentContextUri));
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    public void deleteCorpus(String corpusId, String corpusUri) {
        IRI corpusGraph = iri(corpusGraphUri(corpusId));
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            connection.remove((Resource) null, DCTERMS.IS_PART_OF, iri(corpusUri));
            connection.clear(corpusGraph);
            connection.remove(corpusRecordResource(corpusId), null, null, recordsGraph);
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    public void importLegacyDocument(String fileId, Path turtle) throws IOException {
        importLegacy(iri(documentGraphUri(fileId)), turtle);
    }

    public void importLegacyCorpus(String corpusId, Path turtle) throws IOException {
        importLegacy(iri(corpusGraphUri(corpusId)), turtle);
    }

    private void importLegacy(IRI graph, Path turtle) throws IOException {
        try (InputStream input = Files.newInputStream(turtle)) {
            RepositoryConnection connection = acquire();
            try {
                connection.begin();
                connection.clear(graph);
                connection.add(input, "", RDFFormat.TURTLE, graph);
                connection.commit();
            } catch (RuntimeException e) {
                rollback(connection);
                throw e;
            } finally {
                release(connection);
            }
        }
    }

    private TextRecord deriveDocumentRecord(String fileId, Model model, Resource context) {
        TextRecord record = new TextRecord();
        record.fileId = fileId;
        String contextUri = context == null ? null : context.stringValue();
        record.documentUri = contextUri != null && contextUri.endsWith("#context")
                ? contextUri.substring(0, contextUri.length() - "#context".length())
                : contextUri;
        record.nifGraph = documentGraphUri(fileId);
        record.segmentationMethod = literal(model, context,
                iri(structureNamespace + "segmentationMethod"));
        record.frontMatterPresent = booleanLiteral(model, context,
                iri(structureNamespace + "frontMatterPresent"));
        Resource source = record.documentUri == null ? null : iri(record.documentUri + "/source");
        record.originalFileName = literal(model, source, DCTERMS.IDENTIFIER);
        if (record.originalFileName != null) {
            record.originalPath = "documents/" + fileId + "/original/"
                    + record.originalFileName;
        }
        record.headingCount = Integer.valueOf(countType(model, DOCO + "Chapter")
                + countType(model, DOCO + "Section"));
        record.paragraphCount = Integer.valueOf(countType(model, DOCO + "Paragraph"));
        record.sentenceCount = Integer.valueOf(countType(model, NIF + "Sentence"));
        record.tokenCount = Integer.valueOf(countType(model, NIF + "Word"));
        readMetadata(model, context, record.metadata, record.metadataValues);
        return record;
    }

    private CorpusRecord deriveCorpusRecord(String corpusId, Model model, Resource corpus) {
        CorpusRecord record = new CorpusRecord();
        record.corpusId = corpusId;
        record.corpusUri = corpus == null ? null : corpus.stringValue();
        record.nifGraph = corpusGraphUri(corpusId);
        Resource source = record.corpusUri == null ? null : iri(record.corpusUri + "/source");
        record.originalFileName = literal(model, source, DCTERMS.IDENTIFIER);
        if (record.originalFileName != null) {
            record.originalPath = "corpora/" + corpusId + "/original/"
                    + record.originalFileName;
        }
        readMetadata(model, corpus, record.metadata, record.metadataValues);
        return record;
    }

    private void refreshDocumentMembership(TextRecord record, Model model, Resource context) {
        record.corpusId = null;
        record.corpusUri = null;
        if (context == null) {
            return;
        }
        Value corpus = first(model.filter(context, DCTERMS.IS_PART_OF, null).objects());
        if (corpus instanceof IRI) {
            record.corpusUri = corpus.stringValue();
            record.corpusId = corpusIdFor((IRI) corpus);
        }
    }

    private void refreshCorpusMembership(CorpusRecord record, Model model, Resource corpus) {
        record.documentIds.clear();
        record.documentUris.clear();
        if (corpus == null) {
            return;
        }
        for (Value member : model.filter(corpus, DCTERMS.HAS_PART, null).objects()) {
            if (!(member instanceof IRI)) {
                continue;
            }
            String contextUri = member.stringValue();
            record.documentUris.add(contextUri.endsWith("#context")
                    ? contextUri.substring(0, contextUri.length() - 8) : contextUri);
            String fileId = documentIdFor((IRI) member);
            if (fileId != null) {
                record.documentIds.add(fileId);
            }
        }
    }

    private String corpusIdFor(IRI corpus) {
        RepositoryConnection connection = acquire();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                corpus, iri(structureNamespace + "corpusId"), null, false)) {
            return statements.hasNext() ? statements.next().getObject().stringValue() : null;
        } finally {
            release(connection);
        }
    }

    private String documentIdFor(IRI context) {
        RepositoryConnection connection = acquire();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                context, iri(structureNamespace + "fileId"), null, false)) {
            return statements.hasNext() ? statements.next().getObject().stringValue() : null;
        } finally {
            release(connection);
        }
    }

    private void readMetadata(Model model, Resource subject, Map<String, String> metadata,
                              Map<String, List<String>> metadataValues) {
        if (subject == null) {
            return;
        }
        Map<IRI, String> predicates = new LinkedHashMap<IRI, String>();
        predicates.put(DCTERMS.IDENTIFIER, "id");
        predicates.put(DCTERMS.TITLE, "title");
        predicates.put(DCTERMS.CREATOR, "author");
        predicates.put(DCTERMS.CREATED, "date");
        predicates.put(DCTERMS.DESCRIPTION, "description");
        predicates.put(DCTERMS.LANGUAGE, "language");
        predicates.put(DCTERMS.FORMAT, "format");
        predicates.put(DCTERMS.IS_PART_OF, "corpus");
        for (Map.Entry<IRI, String> entry : predicates.entrySet()) {
            List<String> values = new ArrayList<String>();
            for (Value value : model.filter(subject, entry.getKey(), null).objects()) {
                values.add(value.stringValue());
            }
            if (!values.isEmpty()) {
                metadataValues.put(entry.getValue(), values);
                metadata.put(entry.getValue(), values.get(0));
            }
        }
    }

    private int countType(Model model, String type) {
        return model.filter(null, RDF.TYPE, iri(type)).subjects().size();
    }

    private <T> T readRecord(Resource subject, Class<T> type) {
        RepositoryConnection connection = acquire();
        String json;
        try (RepositoryResult<Statement> statements = connection.getStatements(subject,
                iri(structureNamespace + "recordJson"), null, false, recordsGraph)) {
            json = statements.hasNext()
                    ? statements.next().getObject().stringValue() : null;
        } finally {
            release(connection);
        }
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid persisted text record", e);
        }
    }

    private void putRecord(RepositoryConnection connection, Resource subject, Object record) {
        try {
            connection.remove(subject, iri(structureNamespace + "recordJson"), null,
                    recordsGraph);
            connection.add(subject, iri(structureNamespace + "recordJson"),
                    vf.createLiteral(mapper.writeValueAsString(record)), recordsGraph);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize text record", e);
        }
    }

    private Model readGraph(IRI graph) {
        Model model = new LinkedHashModel();
        RepositoryConnection connection = acquire();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, null, null, false, graph)) {
            while (statements.hasNext()) {
                model.add(statements.next());
            }
            return model;
        } finally {
            release(connection);
        }
    }

    private boolean containsGraph(IRI graph) {
        RepositoryConnection connection = acquire();
        try {
            return connection.hasStatement(null, null, null, false, graph);
        } finally {
            release(connection);
        }
    }

    private void exportGraph(IRI graph, OutputStream output) {
        RepositoryConnection connection = acquire();
        try {
            connection.export(Rio.createWriter(RDFFormat.TURTLE, output), graph);
        } finally {
            release(connection);
        }
    }

    private String replaceTotal(IRI graph, IRI identifierProperty,
                                String identifier, int value, IRI unit) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            Resource subject = null;
            try (RepositoryResult<Statement> identifiers = connection.getStatements(
                    null, identifierProperty, vf.createLiteral(identifier), false,
                    graph)) {
                if (identifiers.hasNext()) {
                    subject = identifiers.next().getSubject();
                }
            }
            if (subject == null) {
                connection.commit();
                return null;
            }

            IRI totalProperty = iri(FRAC + "total");
            IRI unitProperty = iri(FRAC + "unit");
            List<Resource> replaced = new ArrayList<Resource>();
            try (RepositoryResult<Statement> totals = connection.getStatements(
                    subject, totalProperty, null, false, graph)) {
                while (totals.hasNext()) {
                    Value candidate = totals.next().getObject();
                    if (candidate instanceof Resource
                            && connection.hasStatement((Resource) candidate,
                                    unitProperty, unit, false, graph)) {
                        replaced.add((Resource) candidate);
                    }
                }
            }
            for (Resource current : replaced) {
                connection.remove(subject, totalProperty, current, graph);
                connection.remove(current, null, null, graph);
            }
            Resource total = vf.createBNode();
            connection.add(subject, totalProperty, total, graph);
            connection.add(total, RDF.TYPE, iri(FRAC + "Frequency"), graph);
            connection.add(total, RDF.VALUE,
                    vf.createLiteral(Integer.toString(value),
                            org.eclipse.rdf4j.model.vocabulary.XSD.INT), graph);
            connection.add(total, unitProperty, unit, graph);
            connection.commit();
            return subject.stringValue();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    private RepositoryConnection acquire() {
        return memoryRepository == null
                ? GraphDbUtil.getConnection(RepositoryTarget.TEXT)
                : memoryRepository.getConnection();
    }

    private void release(RepositoryConnection connection) {
        if (memoryRepository == null) {
            GraphDbUtil.releaseConnection(RepositoryTarget.TEXT, connection);
        } else if (connection != null) {
            connection.close();
        }
    }

    private Resource subjectWith(Model model, IRI predicate) {
        return first(model.filter(null, predicate, null).subjects());
    }

    private String literal(Model model, Resource subject, IRI predicate) {
        if (subject == null) {
            return null;
        }
        Value value = first(model.filter(subject, predicate, null).objects());
        return value == null ? null : value.stringValue();
    }

    private Boolean booleanLiteral(Model model, Resource subject, IRI predicate) {
        Value value = subject == null ? null
                : first(model.filter(subject, predicate, null).objects());
        return value instanceof Literal ? Boolean.valueOf(((Literal) value).booleanValue()) : null;
    }

    private static <T> T first(Iterable<T> values) {
        java.util.Iterator<T> iterator = values.iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }

    private IRI documentRecordResource(String fileId) {
        return iri(documentGraphUri(fileId) + "#record");
    }

    private IRI corpusRecordResource(String corpusId) {
        return iri(corpusGraphUri(corpusId) + "#record");
    }

    private static void rollback(RepositoryConnection connection) {
        if (connection != null && connection.isActive()) {
            connection.rollback();
        }
    }

    private static String configured(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        return value == null || value.trim().isEmpty() || value.contains("${")
                ? fallback : value.trim();
    }

    private static String trailingSeparator(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "/";
    }

    private static String namespace(String value) {
        return value.endsWith("/") || value.endsWith("#") ? value : value + "#";
    }

    private static final class Holder {
        private static final TextNifRepository INSTANCE =
                new TextNifRepository();
    }
}
