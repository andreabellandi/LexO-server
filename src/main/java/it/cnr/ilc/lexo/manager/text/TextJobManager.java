package it.cnr.ilc.lexo.manager.text;

import com.fasterxml.jackson.annotation.JsonInclude;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.AttestationManager;
import it.cnr.ilc.lexo.manager.ManagerException;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.text.model.JsonTextImport;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.ValidationIssue;
import it.cnr.ilc.lexo.service.data.text.output.TextRecord;
import it.cnr.ilc.lexo.service.data.text.output.UnsavedAttestation;
import it.cnr.ilc.lexo.util.LoggingContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.eclipse.rdf4j.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchronous manager dedicated to TXT/CommonMark/JSON + optional CoNLL-U -> NIF jobs.
 * It deliberately mirrors LexO-server's existing JobManager without changing it.
 */
public final class TextJobManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextJobManager.class);

    private static final AttestationManager IMPORTED_ATTESTATION_MANAGER =
            ManagerFactory.getManager(AttestationManager.class);
    private static final TextJobManager INSTANCE = new TextJobManager();
    private static final String LANGUAGE_FILE = ".language";

    public static TextJobManager get() {
        return INSTANCE;
    }

    public enum UploadKind {
        TEXT, CONLLU
    }

    public enum TextJobType {
        CONVERT
    }

    public enum TextJobState {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TextJobInfo {
        public String fileId;
        public TextJobType type;
        public volatile TextJobState state;
        public volatile int progress;
        public volatile String message;
        public String resultId;
        public List<ValidationIssue> issues;
        public volatile String attestationState;
        public volatile Integer attestationTotal;
        public volatile Integer savedAttestations;
        public volatile List<UnsavedAttestation> unsavedAttestations;

        public TextJobInfo() {
        }

        public TextJobInfo(String fileId, TextJobType type) {
            this.fileId = fileId;
            this.type = type;
            this.state = TextJobState.PENDING;
            this.progress = 0;
        }
    }

    public static final long DEFAULT_MAX_TEXT_BYTES = 50L * 1024L * 1024L;
    public static final long DEFAULT_MAX_CONLLU_BYTES = 50L * 1024L * 1024L;

    private final ExecutorService ioPool = Executors.newFixedThreadPool(4);
    private final Map<String, UploadSet> uploads = new ConcurrentHashMap<String, UploadSet>();
    private final Map<String, TextJobInfo> jobs = new ConcurrentHashMap<String, TextJobInfo>();
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<String, Future<?>>();

    private final Path root;
    private final Path uploadRoot;
    private final Path workRoot;
    private final Path documentRoot;
    private final String publicBaseUri;
    private final String structureNamespace;

    private TextJobManager() {
        root = Paths.get(LexOProperties.getProperty(
                "lexo.text.storage.dir", "data/texts"));
        uploadRoot = root.resolve("uploads");
        workRoot = root.resolve("work");
        documentRoot = root.resolve("documents");
        publicBaseUri = LexOProperties.getProperty("lexo.text.publicBaseUri",
                "https://lexo.ilc.cnr.it/resources/texts/");
        structureNamespace = LexOProperties.getProperty("lexo.text.structureNamespace",
                "https://lexo.ilc.cnr.it/vocabulary/nif-structure#");
        try {
            Files.createDirectories(uploadRoot);
            Files.createDirectories(workRoot);
            Files.createDirectories(documentRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize text storage at " + root, e);
        }
    }

    public Path saveUpload(String fileId, InputStream input, String originalName,
                           UploadKind kind, long maxBytes) throws IOException {
        requireSafeFileId(fileId);
        if (input == null) {
            throw new IOException("Missing upload stream");
        }
        try {
            String safeName = sanitizeFileName(originalName);
            Path dir = uploadRoot.resolve(fileId);
            Files.createDirectories(dir);
            Path target = dir.resolve(safeName);
            Path temp = dir.resolve("." + safeName + "." + UUID.randomUUID().toString() + ".part");
            try {
                copyLimited(input, temp, maxBytes);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }

            UploadSet set = uploads.computeIfAbsent(fileId,
                    k -> new UploadSet(fileId, Instant.now().toString()));
            synchronized (set) {
                if (kind == UploadKind.TEXT) {
                    if (set.text != null && !set.text.equals(target)) {
                        throw new IOException("Only one text file is allowed for a text job");
                    }
                    set.text = target;
                    set.textFileName = safeName;
                } else {
                    if (set.conllu != null && !set.conllu.equals(target)) {
                        throw new IOException("Only one CoNLL-U file is allowed for a text job");
                    }
                    set.conllu = target;
                    set.conlluFileName = safeName;
                }
            }
            return target;
        } catch (IOException | RuntimeException e) {
            cleanupUpload(fileId);
            throw e;
        }
    }

    /** Associates a validated ISO 639 language with an upload and persists it across restarts. */
    public String saveUploadLanguage(String fileId, String language) throws IOException {
        requireSafeFileId(fileId);
        String canonical = Iso639LanguageValidator.get().requireValid(language);
        Path dir = uploadRoot.resolve(fileId);
        Files.createDirectories(dir);
        Files.write(dir.resolve(LANGUAGE_FILE), canonical.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        UploadSet set = uploads.computeIfAbsent(fileId,
                k -> new UploadSet(fileId, Instant.now().toString()));
        synchronized (set) {
            set.language = canonical;
        }
        return canonical;
    }

    public boolean hasTextUpload(String fileId) {
        UploadSet set = findUploadSet(fileId);
        return set != null && set.text != null && Files.exists(set.text);
    }

    public TextJobInfo startConversion(String fileId) {
        return startConversion(fileId, null);
    }

    public TextJobInfo startConversion(String fileId, String corpusId) {
        requireSafeFileId(fileId);
        String selectedCorpusId = corpusId == null || corpusId.trim().isEmpty()
                ? null : corpusId.trim();
        String selectedCorpusUri = selectedCorpusId == null
                ? null : CorpusManager.get().requireCorpusUri(selectedCorpusId);
        UploadSet upload = findUploadSet(fileId);
        if (upload == null || upload.text == null || !Files.exists(upload.text)) {
            throw new IllegalStateException("No uploaded text or JSON file for " + fileId);
        }
        if (upload.language == null) {
            throw new IllegalStateException("Missing language for uploaded text " + fileId);
        }
        TextJobInfo current = jobs.get(fileId);
        if (current != null && (current.state == TextJobState.PENDING
                || current.state == TextJobState.RUNNING)) {
            throw new IllegalStateException("A text conversion job is already running for " + fileId);
        }
        if (TextNifRepository.get().containsDocument(fileId)
                || Files.exists(documentRoot.resolve(fileId))) {
            throw new IllegalStateException("A completed text record already exists for " + fileId);
        }

        TextJobInfo job = new TextJobInfo(fileId, TextJobType.CONVERT);
        jobs.put(fileId, job);
        try {
            Future<?> future = ioPool.submit(LoggingContext.wrap(
                    () -> executeConversion(upload, job, selectedCorpusId,
                            selectedCorpusUri), "fileId", fileId));
            futures.put(fileId, future);
            return job;
        } catch (RuntimeException e) {
            jobs.remove(fileId);
            cleanupFailedConversion(fileId, null);
            throw e;
        }
    }

    private void executeConversion(UploadSet upload, TextJobInfo job,
                                   String corpusId, String corpusUri) {
        String fileId = upload.fileId;
        Path workDir = workRoot.resolve(fileId + "-" + UUID.randomUUID().toString());
        Path finalDir = documentRoot.resolve(fileId);
        boolean committed = false;
        boolean graphCommitted = false;
        TextJobState terminalState = null;
        LOGGER.info("Text NIF conversion job started corpusId={}", corpusId);
        try {
            job.state = TextJobState.RUNNING;
            job.progress = 2;
            checkCancelled();

            String rawInput = readUtf8Strict(upload.text);
            String rawConllu = upload.conllu == null ? null : readUtf8Strict(upload.conllu);
            job.progress = 15;
            job.message = "Input read and UTF-8 validated";
            checkCancelled();

            ControlledCommonMarkParser parser = new ControlledCommonMarkParser();
            JsonTextImport jsonImport = null;
            ParsedTextDocument doc;
            if (isJsonExtension(upload.textFileName.toLowerCase(Locale.ROOT))) {
                jsonImport = new TextJsonImportParser().parse(rawInput);
                if (!sameNullable(corpusId, jsonImport.corpusId)) {
                    throw new IllegalStateException(
                            "BULK_JSON_CORPUS_CHANGED: metadata.corpus changed after admission");
                }
                doc = parser.parseJsonTextStructure(jsonImport.content);
                applyJsonMetadata(doc, jsonImport);
            } else {
                boolean plainText = !parser.hasControlledCommonMarkHeading(rawInput);
                doc = plainText
                        ? parser.parsePlainTextStructure(rawInput)
                        : parser.parseStructure(rawInput);
            }
            applyUploadLanguage(doc, upload.language);
            if (rawConllu == null) {
                parser.segmentWithBreakIterator(doc);
            } else {
                new ConlluSegmenter().apply(doc, rawConllu, upload.conlluFileName);
            }
            job.progress = 55;
            job.message = "Document structure and linguistic segmentation validated";
            checkCancelled();

            Path originalDir = workDir.resolve("original");
            Files.createDirectories(originalDir);
            Files.copy(upload.text, originalDir.resolve(upload.textFileName),
                    StandardCopyOption.REPLACE_EXISTING);
            if (upload.conllu != null) {
                Path conlluDir = workDir.resolve("conllu");
                Files.createDirectories(conlluDir);
                Files.copy(upload.conllu, conlluDir.resolve(upload.conlluFileName),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            job.progress = 70;
            checkCancelled();

            NifModelWriter writer = new NifModelWriter(publicBaseUri, structureNamespace);
            Model nifModel = writer.build(fileId, upload.textFileName, doc, corpusUri);
            job.progress = 88;
            job.message = "RDF/NIF model built";
            checkCancelled();

            TextRecord record = buildRecord(fileId, upload, doc, writer.documentUri(fileId),
                    corpusId, corpusUri);
            moveDirectory(workDir, finalDir);
            TextNifRepository.get().saveDocument(fileId, nifModel,
                    record.documentUri + "#context", corpusId, corpusUri, record);
            graphCommitted = true;
            committed = true;
            if (jsonImport != null) {
                String evidence = corpusUri == null
                        ? record.documentUri + "#context" : corpusUri;
                importAttestations(fileId, evidence, upload.language,
                        jsonImport.attestations, job);
            }
            uploads.remove(fileId);
            deleteRecursively(uploadRoot.resolve(fileId));
            deleteRecursively(workDir);

            job.resultId = fileId;
            job.progress = 100;
            job.state = TextJobState.COMPLETED;
            job.message = "Converted to NIF: headings=" + doc.allHeadings.size()
                    + ", paragraphs=" + doc.paragraphs.size()
                    + ", sentences=" + doc.sentences.size()
                    + ", tokens=" + doc.tokens.size();
            LOGGER.info("Text NIF conversion job completed corpusId={} tokens={}",
                    corpusId, doc.tokens.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.message = "Text conversion cancelled";
            terminalState = TextJobState.CANCELLED;
            LOGGER.info("Text NIF conversion job cancelled corpusId={}", corpusId);
        } catch (TextValidationException e) {
            job.message = e.getMessage();
            job.issues = new ArrayList<ValidationIssue>(e.getIssues());
            terminalState = TextJobState.FAILED;
            LOGGER.warn("Text NIF conversion validation failed corpusId={} issues={}",
                    corpusId, e.getIssues().size());
        } catch (Throwable e) {
            job.message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            terminalState = TextJobState.FAILED;
            LOGGER.error("Text NIF conversion job failed corpusId={}", corpusId, e);
        } finally {
            if (!committed) {
                if (graphCommitted) {
                    try {
                        TextNifRepository.get().deleteDocument(fileId,
                                writerDocumentUri(fileId) + "#context", corpusId, corpusUri);
                    } catch (Throwable ignored) {
                        LOGGER.error("Unable to roll back text NIF graph corpusId={}",
                                corpusId, ignored);
                    }
                }
                cleanupFailedConversion(fileId, workDir);
            }
            if (terminalState != null) {
                job.state = terminalState;
            }
        }
    }

    private TextRecord buildRecord(String fileId, UploadSet upload, ParsedTextDocument doc,
                                   String documentUri, String corpusId, String corpusUri) {
        TextRecord record = new TextRecord();
        record.fileId = fileId;
        record.documentUri = documentUri;
        record.corpusId = corpusId;
        record.corpusUri = corpusUri;
        record.segmentationMethod = doc.segmentationMethod;
        record.frontMatterPresent = Boolean.valueOf(doc.frontMatterPresent);
        record.originalFileName = upload.textFileName;
        record.conlluFileName = upload.conlluFileName;
        record.originalPath = "documents/" + fileId + "/original/" + upload.textFileName;
        if (upload.conlluFileName != null) {
            record.conlluPath = "documents/" + fileId + "/conllu/"
                    + upload.conlluFileName;
        }
        record.nifGraph = TextNifRepository.get().documentGraphUri(fileId);
        record.createdAt = upload.createdAt;
        record.completedAt = Instant.now().toString();
        record.headingCount = Integer.valueOf(doc.allHeadings.size());
        record.paragraphCount = Integer.valueOf(doc.paragraphs.size());
        record.sentenceCount = Integer.valueOf(doc.sentences.size());
        record.tokenCount = Integer.valueOf(doc.tokens.size());
        record.metadata.putAll(doc.metadata);
        for (Map.Entry<String, List<String>> entry : doc.metadataValues.entrySet()) {
            record.metadataValues.put(entry.getKey(),
                    new ArrayList<String>(entry.getValue()));
        }
        record.warnings.addAll(doc.warnings);
        return record;
    }

    private static void applyUploadLanguage(ParsedTextDocument doc, String language) {
        doc.metadata.put("language", language);
        List<String> values = new ArrayList<String>(1);
        values.add(language);
        doc.metadataValues.put("language", values);
    }

    private static void applyJsonMetadata(ParsedTextDocument doc,
                                          JsonTextImport jsonImport) {
        doc.frontMatterPresent = jsonImport.metadataPresent;
        for (Map.Entry<String, List<String>> entry : jsonImport.metadata.entrySet()) {
            List<String> values = new ArrayList<String>(entry.getValue());
            doc.metadataValues.put(entry.getKey(), values);
            if (!values.isEmpty()) {
                doc.metadata.put(entry.getKey(), values.get(0));
            }
        }
    }

    private static void importAttestations(
            String fileId, String evidence, String language,
            List<JsonTextImport.AttestationInput> attestations,
            TextJobInfo job) {
        List<UnsavedAttestation> unsaved = new ArrayList<UnsavedAttestation>();
        int total = attestations == null ? 0 : attestations.size();
        int saved = 0;
        job.attestationState = "RUNNING";
        job.attestationTotal = Integer.valueOf(total);
        job.savedAttestations = Integer.valueOf(0);
        job.unsavedAttestations = Collections.emptyList();
        if (attestations != null) {
            for (int index = 0; index < attestations.size(); index++) {
                JsonTextImport.AttestationInput input = attestations.get(index);
                try {
                    IMPORTED_ATTESTATION_MANAGER.createImported(
                            fileId, evidence, language, input);
                    saved++;
                    job.savedAttestations = Integer.valueOf(saved);
                } catch (ManagerException | IllegalArgumentException e) {
                    unsaved.add(unsaved(input, e));
                } catch (RuntimeException e) {
                    unsaved.add(unsaved(input, e));
                }
                job.progress = 88 + (int) Math.floor(
                        11.0d * (index + 1) / Math.max(1, total));
            }
        }
        job.savedAttestations = Integer.valueOf(saved);
        job.unsavedAttestations = unsaved;
        if (unsaved.isEmpty()) {
            job.attestationState = "COMPLETED";
        } else if (saved == 0) {
            job.attestationState = "FAILED";
        } else {
            job.attestationState = "PARTIALLY_COMPLETED";
        }
    }

    private static UnsavedAttestation unsaved(
            JsonTextImport.AttestationInput input, Throwable error) {
        UnsavedAttestation result = new UnsavedAttestation();
        if (input != null) {
            result.id = input.id;
            result.observable = input.observable;
            result.type = input.type;
        }
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        int separator = message.indexOf(':');
        String candidate = separator <= 0 ? "ATTESTATION_IMPORT_FAILED"
                : message.substring(0, separator);
        result.code = candidate.matches("[A-Z][A-Z0-9_]*")
                ? candidate : "ATTESTATION_IMPORT_FAILED";
        result.cause = separator <= 0 ? message : message.substring(separator + 1).trim();
        return result;
    }

    private static boolean sameNullable(String left, String right) {
        String normalizedLeft = left == null || left.trim().isEmpty()
                ? null : left.trim();
        String normalizedRight = right == null || right.trim().isEmpty()
                ? null : right.trim();
        return normalizedLeft == null ? normalizedRight == null
                : normalizedLeft.equals(normalizedRight);
    }

    public Collection<TextJobInfo> getAllJobsFor(String fileId) {
        TextJobInfo job = jobs.get(fileId);
        if (job == null) {
            return Collections.emptyList();
        }
        List<TextJobInfo> out = new ArrayList<TextJobInfo>(1);
        out.add(job);
        return out;
    }

    public TextJobInfo getJob(String fileId) {
        return jobs.get(fileId);
    }

    public boolean cancel(String fileId) {
        Future<?> future = futures.get(fileId);
        if (future == null) {
            return false;
        }
        boolean cancelled = future.cancel(true);
        TextJobInfo job = jobs.get(fileId);
        if (cancelled && job != null) {
            job.state = TextJobState.CANCELLED;
            job.message = "Cancelled by user";
        }
        return cancelled;
    }

    public TextRecord getRecord(String fileId) {
        requireSafeFileId(fileId);
        return TextNifRepository.get().getDocumentRecord(fileId);
    }

    public String getCanonical(String fileId) {
        requireSafeFileId(fileId);
        return TextNifRepository.get().getCanonicalText(fileId);
    }

    public boolean hasNif(String fileId) {
        requireSafeFileId(fileId);
        return TextNifRepository.get().containsDocument(fileId);
    }

    public void writeNif(String fileId, OutputStream output) {
        requireSafeFileId(fileId);
        if (!TextNifRepository.get().containsDocument(fileId)) {
            throw new IllegalArgumentException("Text NIF not found: " + fileId);
        }
        TextNifRepository.get().writeDocument(fileId, output);
    }

    public boolean delete(String fileId) throws IOException {
        requireSafeFileId(fileId);
        TextRecord record = getRecord(fileId);
        cancel(fileId);
        boolean graphExisted = TextNifRepository.get().containsDocument(fileId);
        if (record != null) {
            TextNifRepository.get().deleteDocument(fileId,
                    record.documentUri + "#context", record.corpusId, record.corpusUri);
        } else if (graphExisted) {
            TextNifRepository.get().deleteDocument(fileId,
                    writerDocumentUri(fileId) + "#context", null, null);
        }
        boolean lexicalGraphsExisted = LexicalTextGraphManager.get()
                .deleteDocumentGraphs(fileId);
        jobs.remove(fileId);
        futures.remove(fileId);
        uploads.remove(fileId);
        boolean existed = Files.exists(documentRoot.resolve(fileId))
                || Files.exists(uploadRoot.resolve(fileId)) || graphExisted
                || lexicalGraphsExisted;
        deleteRecursively(documentRoot.resolve(fileId));
        deleteRecursively(uploadRoot.resolve(fileId));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workRoot, fileId + "-*")) {
            for (Path path : stream) {
                deleteRecursively(path);
            }
        }
        return existed;
    }

    public void cleanupUpload(String fileId) {
        uploads.remove(fileId);
        deleteRecursively(uploadRoot.resolve(fileId));
    }

    private void cleanupFailedConversion(String fileId, Path workDir) {
        uploads.remove(fileId);
        deleteRecursively(uploadRoot.resolve(fileId));
        deleteRecursively(workDir);
        deleteRecursively(documentRoot.resolve(fileId));
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workRoot, fileId + "-*")) {
            for (Path path : stream) {
                deleteRecursively(path);
            }
        } catch (IOException ignored) {
        }
    }


    public void shutdown() {
        ioPool.shutdownNow();
    }

    public Path getStorageRoot() {
        return root;
    }

    public Path getOriginal(String fileId) {
        TextRecord record = getRecord(fileId);
        return record == null || record.originalFileName == null ? null
                : documentRoot.resolve(fileId).resolve("original")
                        .resolve(sanitizeFileName(record.originalFileName));
    }

    public Path getConllu(String fileId) {
        TextRecord record = getRecord(fileId);
        return record == null || record.conlluFileName == null ? null
                : documentRoot.resolve(fileId).resolve("conllu")
                        .resolve(sanitizeFileName(record.conlluFileName));
    }

    private String writerDocumentUri(String fileId) {
        return new NifModelWriter(publicBaseUri, structureNamespace).documentUri(fileId);
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private UploadSet findUploadSet(String fileId) {
        UploadSet cached = uploads.get(fileId);
        if (cached != null) {
            return cached;
        }
        Path dir = uploadRoot.resolve(fileId);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        UploadSet discovered = new UploadSet(fileId, Instant.now().toString());
        Path languagePath = dir.resolve(LANGUAGE_FILE);
        if (Files.isRegularFile(languagePath)) {
            try {
                discovered.language = Iso639LanguageValidator.get()
                        .requireValid(readUtf8Strict(languagePath));
            } catch (IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Cannot read upload language for " + fileId, e);
            }
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path) || path.getFileName().toString().startsWith(".")) {
                    continue;
                }
                String name = path.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (isTextExtension(lower) || isJsonExtension(lower)) {
                    discovered.text = path;
                    discovered.textFileName = name;
                } else if (isConlluExtension(lower)) {
                    discovered.conllu = path;
                    discovered.conlluFileName = name;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot inspect uploads for " + fileId, e);
        }
        if (discovered.text == null && discovered.conllu == null) {
            return null;
        }
        UploadSet previous = uploads.putIfAbsent(fileId, discovered);
        return previous == null ? discovered : previous;
    }

    private static void copyLimited(InputStream input, Path target, long maxBytes) throws IOException {
        long limit = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        long written = 0L;
        byte[] buffer = new byte[8192];
        try (java.io.OutputStream out = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                written += read;
                if (written > limit) {
                    throw new IOException("File exceeds configured limit of " + limit + " bytes");
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
    }

    private static String readUtf8Strict(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw new IOException("File is not valid UTF-8: " + path.getFileName(), e);
        }
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing original filename");
        }
        String safe = Paths.get(name).getFileName().toString();
        safe = safe.replaceAll("[\\p{Cntrl}]", "_");
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Invalid filename");
        }
        return safe;
    }

    private static void requireSafeFileId(String fileId) {
        if (fileId == null || !fileId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid fileId");
        }
    }

    public static boolean isTextExtension(String lowerName) {
        return isPlainTextExtension(lowerName) || isMarkdownExtension(lowerName);
    }

    public static boolean isPlainTextExtension(String lowerName) {
        return lowerName != null && lowerName.endsWith(".txt");
    }

    public static boolean isMarkdownExtension(String lowerName) {
        return lowerName != null && (lowerName.endsWith(".md")
                || lowerName.endsWith(".markdown"));
    }

    public static boolean isJsonExtension(String lowerName) {
        return lowerName != null && lowerName.endsWith(".json");
    }

    public static boolean isConlluExtension(String lowerName) {
        return lowerName.endsWith(".conllu") || lowerName.endsWith(".conll-u")
                || lowerName.endsWith(".conll");
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    try {
                        path.toFile().deleteOnExit();
                    } catch (Throwable alsoIgnored) {
                    }
                }
            });
        } catch (IOException ignored) {
            try {
                root.toFile().deleteOnExit();
            } catch (Throwable alsoIgnored) {
            }
        }
    }

    private static final class UploadSet {
        final String fileId;
        final String createdAt;
        Path text;
        String textFileName;
        String language;
        Path conllu;
        String conlluFileName;

        UploadSet(String fileId, String createdAt) {
            this.fileId = fileId;
            this.createdAt = createdAt;
        }
    }
}
