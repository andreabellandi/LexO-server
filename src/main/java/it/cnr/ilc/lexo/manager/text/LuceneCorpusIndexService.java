package it.cnr.ilc.lexo.manager.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.LexOProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.CheckIndex;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Filesystem Lucene index with canonical positions, offsets and term vectors. */
public final class LuceneCorpusIndexService implements CorpusIndexService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LuceneCorpusIndexService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String FILE_ID = "fileId";
    public static final String CONTEXT_IRI = "contextIRI";
    public static final String CORPUS_IRI = "corpusIRI";
    public static final String GRAPH_IRI = "graphIRI";
    public static final String CONTENT = "content";
    public static final String CONTENT_SURFACE = "content_surface";
    public static final String CONTENT_NORMALIZED = "content_normalized";
    public static final String CONTENT_HASH = "contentHash";
    public static final String SEGMENTATION_HASH = "segmentationHash";
    public static final String SEGMENTATION_SOURCE = "segmentationSource";
    public static final String LANGUAGE = "language";
    public static final String ANALYZER_PROFILE = "analyzerProfile";
    public static final String TOKENIZER_PROFILE = "tokenizerProfile";
    public static final String SENTENCE_PROFILE = "sentenceSplitterProfile";
    public static final String SEGMENTATION_SCHEMA = "segmentationSchemaVersion";
    public static final String TOKEN_COUNT = "tokenCount";
    public static final String SENTENCE_COUNT = "sentenceCount";
    public static final String SNAPSHOT = "canonicalSegmentation";

    private static final FieldType POSITIONAL_FIELD;
    static {
        FieldType type = new FieldType();
        type.setTokenized(true);
        type.setStored(false);
        type.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        type.setStoreTermVectors(true);
        type.setStoreTermVectorPositions(true);
        type.setStoreTermVectorOffsets(true);
        type.freeze();
        POSITIONAL_FIELD = type;
    }

    private final Path indexPath;
    private volatile Directory directory;
    private volatile IndexWriter writer;
    private volatile SearcherManager searchers;
    private volatile Throwable initializationFailure;
    private volatile Instant lastCommit;
    private final ReentrantReadWriteLock lifecycleLock =
            new ReentrantReadWriteLock(true);

    public static LuceneCorpusIndexService get() {
        return Holder.INSTANCE;
    }

    private LuceneCorpusIndexService() {
        this(Paths.get(configured("lexo.lucene.index.path", "data/texts/lucene-index")));
    }

    LuceneCorpusIndexService(Path indexPath) {
        this.indexPath = indexPath.toAbsolutePath().normalize();
        try {
            open();
        } catch (Throwable error) {
            initializationFailure = error;
            LOGGER.error("Standalone Lucene corpus index is unavailable", error);
        }
    }

    private synchronized void open() throws IOException {
        Files.createDirectories(indexPath);
        directory = FSDirectory.open(indexPath);
        IndexWriterConfig config = new IndexWriterConfig(new KeywordAnalyzer());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        writer = new IndexWriter(directory, config);
        searchers = new SearcherManager(writer, true, true, null);
        initializationFailure = null;
        LOGGER.info("Standalone Lucene corpus index initialized schema={} version={}",
                schemaVersion(), Version.LATEST);
    }

    @Override
    public synchronized void index(CorpusIndexDocument source) throws IOException {
        requireAvailable();
        validate(source);
        Document document = new Document();
        document.add(new StringField(FILE_ID, source.fileId, Field.Store.YES));
        document.add(new StringField(CONTEXT_IRI, source.contextIRI, Field.Store.YES));
        optionalKeyword(document, CORPUS_IRI, source.corpusIRI);
        optionalKeyword(document, GRAPH_IRI, source.graphIRI);
        document.add(new StoredField(CONTENT, source.content));
        document.add(new StringField(CONTENT_HASH, source.contentHash, Field.Store.YES));
        document.add(new StringField(SEGMENTATION_HASH, source.segmentationHash,
                Field.Store.YES));
        optionalKeyword(document, SEGMENTATION_SOURCE, source.segmentationSource);
        optionalKeyword(document, LANGUAGE, source.language);
        optionalKeyword(document, ANALYZER_PROFILE, source.analyzerProfile);
        optionalKeyword(document, TOKENIZER_PROFILE, source.tokenizerProfile);
        optionalKeyword(document, SENTENCE_PROFILE, source.sentenceSplitterProfile);
        optionalKeyword(document, SEGMENTATION_SCHEMA, source.segmentationSchemaVersion);
        document.add(new IntPoint(TOKEN_COUNT, source.tokens.size()));
        document.add(new StoredField(TOKEN_COUNT, source.tokens.size()));
        document.add(new IntPoint(SENTENCE_COUNT, source.sentences.size()));
        document.add(new StoredField(SENTENCE_COUNT, source.sentences.size()));
        document.add(new StoredField(SNAPSHOT, JSON.writeValueAsString(source)));
        document.add(new Field(CONTENT_SURFACE,
                new CanonicalTokenStream(source.tokens, false), POSITIONAL_FIELD));
        document.add(new Field(CONTENT_NORMALIZED,
                new CanonicalTokenStream(source.tokens, true), POSITIONAL_FIELD));
        writer.updateDocument(new Term(FILE_ID, source.fileId), document);
        commitAndRefresh();
    }

    @Override
    public synchronized boolean delete(String fileId) throws IOException {
        requireAvailable();
        boolean existed = get(fileId) != null;
        writer.deleteDocuments(new Term(FILE_ID, fileId));
        commitAndRefresh();
        return existed;
    }

    @Override
    public CorpusIndexDocument get(String fileId) throws IOException {
        requireAvailable();
        IndexSearcher searcher = acquire();
        try {
            org.apache.lucene.search.TopDocs hits = searcher.search(
                    new TermQuery(new Term(FILE_ID, fileId)), 1);
            if (hits.scoreDocs.length == 0) {
                return null;
            }
            return JSON.readValue(searcher.doc(hits.scoreDocs[0].doc)
                    .get(SNAPSHOT), CorpusIndexDocument.class);
        } finally {
            release(searcher);
        }
    }

    @Override
    public List<String> fileIds() throws IOException {
        requireAvailable();
        IndexSearcher searcher = acquire();
        try {
            List<String> result = new ArrayList<String>();
            org.apache.lucene.util.Bits live = org.apache.lucene.index.MultiBits
                    .getLiveDocs(searcher.getIndexReader());
            for (int index = 0; index < searcher.getIndexReader().maxDoc(); index++) {
                if (live == null || live.get(index)) {
                    Document document = searcher.doc(index,
                            Collections.singleton(FILE_ID));
                    if (document.get(FILE_ID) != null) {
                        result.add(document.get(FILE_ID));
                    }
                }
            }
            Collections.sort(result);
            return result;
        } finally {
            release(searcher);
        }
    }

    @Override
    public long documentCount() throws IOException {
        requireAvailable();
        IndexSearcher searcher = acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            release(searcher);
        }
    }

    public IndexSearcher acquire() throws IOException {
        lifecycleLock.readLock().lock();
        try {
            requireAvailable();
            return searchers.acquire();
        } catch (IOException | RuntimeException | Error error) {
            lifecycleLock.readLock().unlock();
            throw error;
        }
    }

    public void release(IndexSearcher searcher) throws IOException {
        try {
            if (searcher != null && searchers != null) {
                searchers.release(searcher);
            }
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public boolean available() {
        return initializationFailure == null && writer != null && writer.isOpen();
    }

    public Throwable initializationFailure() {
        return initializationFailure;
    }

    public Instant lastCommit() {
        return lastCommit;
    }

    public Path indexPath() {
        return indexPath;
    }

    public String schemaVersion() {
        return configured("lexo.lucene.indexSchemaVersion", "1");
    }

    /** Builds and validates a temporary index before swapping it into service. */
    public synchronized void safeRebuild(List<CorpusIndexDocument> documents)
            throws IOException {
        lifecycleLock.writeLock().lock();
        try {
            safeRebuildLocked(documents);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void safeRebuildLocked(List<CorpusIndexDocument> documents)
            throws IOException {
        Path rebuildRoot = Paths.get(configured("lexo.lucene.rebuild.temp.dir",
                "data/texts/lucene-rebuild")).toAbsolutePath().normalize();
        Files.createDirectories(rebuildRoot);
        Path temporaryPath = Files.createTempDirectory(rebuildRoot, "index-");
        LuceneCorpusIndexService temporary = new LuceneCorpusIndexService(temporaryPath);
        Path backup = indexPath.resolveSibling(indexPath.getFileName().toString()
                + ".backup-" + System.currentTimeMillis());
        boolean oldMoved = false;
        try {
            for (CorpusIndexDocument document : documents) {
                temporary.index(document);
            }
            temporary.close();
            try (Directory checkDirectory = FSDirectory.open(temporaryPath);
                 CheckIndex checker = new CheckIndex(checkDirectory);
                 PrintStream checkOutput = new PrintStream(
                         new ByteArrayOutputStream())) {
                checker.setInfoStream(checkOutput);
                CheckIndex.Status status = checker.checkIndex();
                if (status == null || !status.clean) {
                    throw new IOException("Temporary Lucene index failed CheckIndex");
                }
            }
            close();
            if (Files.exists(indexPath)) {
                try {
                    Files.move(indexPath, backup, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                    Files.move(indexPath, backup);
                }
                oldMoved = true;
            }
            try {
                Files.move(temporaryPath, indexPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporaryPath, indexPath);
            }
            open();
            deleteRecursively(backup);
        } catch (Throwable failure) {
            temporary.close();
            if (!available()) {
                deleteRecursively(indexPath);
                if (oldMoved && Files.exists(backup)) {
                    Files.move(backup, indexPath);
                }
                try {
                    open();
                } catch (Throwable reopen) {
                    failure.addSuppressed(reopen);
                }
            }
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw new IOException("Lucene safe rebuild failed", failure);
        } finally {
            deleteRecursively(temporaryPath);
        }
    }

    public synchronized void close() {
        lifecycleLock.writeLock().lock();
        try {
            closeLocked();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void closeLocked() {
        try {
            if (searchers != null) {
                searchers.close();
            }
        } catch (IOException error) {
            LOGGER.warn("Unable to close Lucene SearcherManager", error);
        }
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException error) {
            LOGGER.warn("Unable to close Lucene IndexWriter", error);
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException error) {
            LOGGER.warn("Unable to close Lucene directory", error);
        }
    }

    private void commitAndRefresh() throws IOException {
        writer.commit();
        searchers.maybeRefreshBlocking();
        lastCommit = Instant.now();
    }

    private void requireAvailable() throws IOException {
        if (!available()) {
            throw new IOException("Lucene corpus index is unavailable",
                    initializationFailure);
        }
    }

    private static void validate(CorpusIndexDocument document) {
        if (document == null || blank(document.fileId) || blank(document.contextIRI)
                || document.content == null || blank(document.contentHash)
                || blank(document.segmentationHash)) {
            throw new IllegalArgumentException("Incomplete Lucene corpus document");
        }
        int previousEnd = -1;
        for (int index = 0; index < document.tokens.size(); index++) {
            CorpusIndexDocument.TokenData token = document.tokens.get(index);
            if (token.position != index || token.startUtf16 < previousEnd
                    || token.endUtf16 <= token.startUtf16
                    || token.endUtf16 > document.content.length()
                    || !token.surface.equals(document.content.substring(
                            token.startUtf16, token.endUtf16))) {
                throw new IllegalArgumentException("Invalid canonical token map");
            }
            previousEnd = token.endUtf16;
        }
    }

    private static void optionalKeyword(Document document, String name, String value) {
        if (!blank(value)) {
            document.add(new StringField(name, value, Field.Store.YES));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String configured(String name, String fallback) {
        String value = LexOProperties.getProperty(name);
        return value == null || value.trim().isEmpty() || value.contains("${")
                ? fallback : value.trim();
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static final class CanonicalTokenStream extends TokenStream {
        private final List<CorpusIndexDocument.TokenData> tokens;
        private final boolean normalized;
        private final CharTermAttribute term = addAttribute(CharTermAttribute.class);
        private final OffsetAttribute offset = addAttribute(OffsetAttribute.class);
        private final PositionIncrementAttribute position =
                addAttribute(PositionIncrementAttribute.class);
        private int cursor;

        CanonicalTokenStream(List<CorpusIndexDocument.TokenData> tokens,
                             boolean normalized) {
            this.tokens = tokens;
            this.normalized = normalized;
        }

        @Override
        public boolean incrementToken() {
            if (cursor >= tokens.size()) {
                return false;
            }
            clearAttributes();
            CorpusIndexDocument.TokenData token = tokens.get(cursor++);
            String value = token.indexedTerm == null ? token.surface : token.indexedTerm;
            term.append(normalized ? value.toLowerCase(Locale.ROOT) : value);
            offset.setOffset(token.startUtf16, token.endUtf16);
            position.setPositionIncrement(1);
            return true;
        }

        @Override
        public void reset() {
            cursor = 0;
        }
    }

    private static final class Holder {
        private static final LuceneCorpusIndexService INSTANCE =
                new LuceneCorpusIndexService();
    }
}
