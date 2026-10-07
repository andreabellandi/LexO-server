package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.NifCanonicalSegmentationReader.ReadResult;
import it.cnr.ilc.lexo.service.data.text.output.CorpusIndexConsistency;
import it.cnr.ilc.lexo.service.data.text.output.CorpusIndexStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.util.Version;
import org.eclipse.rdf4j.model.Model;

/** Administrative verification, exact reindex and safe full rebuild. */
public final class CorpusIndexManager {

    private static final CorpusIndexManager INSTANCE = new CorpusIndexManager();
    private final LuceneCorpusIndexService index = LuceneCorpusIndexService.get();
    private final TextNifRepository repository = TextNifRepository.get();
    private final NifCanonicalSegmentationReader reader =
            new NifCanonicalSegmentationReader(LexOProperties.getProperty(
                    "lexo.text.structureNamespace",
                    "https://lexo.ilc.cnr.it/vocabulary/nif-structure#"));

    public static CorpusIndexManager get() {
        return INSTANCE;
    }

    private CorpusIndexManager() {
    }

    public CorpusIndexStatus status() {
        CorpusIndexStatus result = new CorpusIndexStatus();
        result.available = index.available();
        result.luceneVersion = Version.LATEST.toString();
        result.tokenizerProfile = CanonicalSegmentationService.TOKENIZER_PROFILE;
        result.indexSchemaVersion = index.schemaVersion();
        result.lastCommit = index.lastCommit() == null
                ? null : index.lastCommit().toString();
        if (result.available) {
            try {
                result.documents = Long.valueOf(index.documentCount());
            } catch (IOException error) {
                result.available = false;
                result.error = message(error);
            }
        } else if (index.initializationFailure() != null) {
            result.error = message(index.initializationFailure());
        }
        return result;
    }

    public CorpusIndexConsistency verify(boolean deep) throws IOException {
        Map<String, CorpusIndexDocument> lucene = new HashMap<String, CorpusIndexDocument>();
        for (String fileId : index.fileIds()) {
            lucene.put(fileId, index.get(fileId));
        }
        List<String> rdfIds = repository.listDocumentFileIds();
        Set<String> rdf = new HashSet<String>(rdfIds);
        CorpusIndexConsistency result = new CorpusIndexConsistency();
        for (String fileId : rdfIds) {
            CorpusIndexDocument indexed = lucene.get(fileId);
            if (indexed == null) {
                result.missingInLucene.add(fileId);
                continue;
            }
            ReadResult persisted = read(fileId);
            if (!persisted.document.contentHash.equals(indexed.contentHash)) {
                result.contentHashMismatch.add(fileId);
            }
            if (!persisted.document.segmentationHash.equals(indexed.segmentationHash)) {
                result.segmentationHashMismatch.add(fileId);
            }
            if (deep && persisted.document.tokens.size() != indexed.tokens.size()) {
                result.tokenCountMismatch.add(fileId);
            }
            if (deep && persisted.document.sentences.size() != indexed.sentences.size()) {
                result.sentenceCountMismatch.add(fileId);
            }
        }
        for (String fileId : lucene.keySet()) {
            if (!rdf.contains(fileId)) {
                result.missingInRdf.add(fileId);
            }
        }
        result.consistent = result.missingInLucene.isEmpty()
                && result.missingInRdf.isEmpty()
                && result.contentHashMismatch.isEmpty()
                && result.segmentationHashMismatch.isEmpty()
                && result.tokenCountMismatch.isEmpty()
                && result.sentenceCountMismatch.isEmpty();
        return result;
    }

    public CorpusIndexDocument reindex(String fileId) throws IOException {
        ReadResult persisted = read(fileId);
        CorpusIndexDocument document = CorpusIndexDocument.from(fileId,
                persisted.contextIRI, persisted.corpusIRI, persisted.graphIRI,
                persisted.document);
        index.index(document);
        return document;
    }

    public CorpusIndexStatus rebuild() throws IOException {
        List<CorpusIndexDocument> documents = new ArrayList<CorpusIndexDocument>();
        for (String fileId : repository.listDocumentFileIds()) {
            ReadResult persisted = read(fileId);
            documents.add(CorpusIndexDocument.from(fileId, persisted.contextIRI,
                    persisted.corpusIRI, persisted.graphIRI, persisted.document));
        }
        index.safeRebuild(documents);
        return status();
    }

    private ReadResult read(String fileId) {
        Model model = repository.getDocumentModel(fileId);
        if (model.isEmpty()) {
            throw new IllegalArgumentException("CONTEXT_NOT_FOUND: " + fileId);
        }
        return reader.read(fileId, repository.documentGraphUri(fileId), model);
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }
}
