package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.service.data.text.output.CorpusRecord;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.rdf4j.model.Model;

/** Corpus metadata in LexOTexts with original descriptors retained on disk. */
public final class CorpusManager {

    private static final CorpusManager INSTANCE = new CorpusManager();

    public static CorpusManager get() {
        return INSTANCE;
    }

    private final Map<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final Path workRoot;
    private final Path corpusRoot;
    private final NifModelWriter writer;

    private CorpusManager() {
        Path root = Paths.get(LexOProperties.getProperty(
                "lexo.text.storage.dir", "data/texts"));
        workRoot = root.resolve("work");
        corpusRoot = root.resolve("corpora");
        writer = new NifModelWriter(
                LexOProperties.getProperty("lexo.text.publicBaseUri",
                        "https://lexo.ilc.cnr.it/resources/texts/"),
                LexOProperties.getProperty("lexo.text.structureNamespace",
                        "https://lexo.ilc.cnr.it/vocabulary/nif-structure#"));
        try {
            Files.createDirectories(workRoot);
            Files.createDirectories(corpusRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize temporary corpus storage", e);
        }
    }

    public CorpusRecord create(String corpusId, InputStream input, String originalName,
                               long maxBytes) throws IOException, TextValidationException {
        requireSafeId(corpusId);
        String safeName = sanitizeFileName(originalName);
        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            throw new IllegalArgumentException("A .txt metadata file is required");
        }
        synchronized (lockFor(corpusId)) {
            if (TextNifRepository.get().containsCorpus(corpusId)) {
                throw new IllegalStateException("Corpus already exists: " + corpusId);
            }
            Path workDir = workRoot.resolve("corpus-" + corpusId + "-" + UUID.randomUUID());
            Path temporary = workDir.resolve("original").resolve(safeName);
            Path finalDir = corpusRoot.resolve(corpusId);
            boolean graphCommitted = false;
            try {
                Files.createDirectories(temporary.getParent());
                copyLimited(input, temporary, maxBytes);
                ParsedTextDocument metadata = new ControlledCommonMarkParser()
                        .parseMetadataOnly(readUtf8Strict(temporary));

                CorpusRecord record = new CorpusRecord();
                record.corpusId = corpusId;
                record.corpusUri = writer.corpusUri(corpusId);
                record.originalFileName = safeName;
                record.originalPath = "corpora/" + corpusId + "/original/" + safeName;
                record.nifGraph = TextNifRepository.get().corpusGraphUri(corpusId);
                record.createdAt = Instant.now().toString();
                record.updatedAt = record.createdAt;
                record.metadata.putAll(metadata.metadata);
                copyMetadataValues(metadata, record);

                Model model = writer.buildCorpus(corpusId, safeName,
                        metadata, record.documentUris);
                moveDirectory(workDir, finalDir);
                TextNifRepository.get().saveCorpus(corpusId, model, record);
                graphCommitted = true;
                return record;
            } catch (IOException | RuntimeException | TextValidationException e) {
                if (graphCommitted) {
                    try {
                        TextNifRepository.get().deleteCorpus(corpusId,
                                writer.corpusUri(corpusId));
                    } catch (RuntimeException ignored) {
                    }
                }
                deleteRecursively(finalDir);
                throw e;
            } finally {
                deleteRecursively(workDir);
            }
        }
    }

    public CorpusRecord getRecord(String corpusId) {
        requireSafeId(corpusId);
        return TextNifRepository.get().getCorpusRecord(corpusId);
    }

    public String requireCorpusUri(String corpusId) {
        CorpusRecord record = getRecord(corpusId);
        if (record == null) {
            throw new IllegalArgumentException("Corpus not found: " + corpusId);
        }
        return record.corpusUri;
    }

    public boolean hasNif(String corpusId) {
        requireSafeId(corpusId);
        return TextNifRepository.get().containsCorpus(corpusId);
    }

    public Path getOriginal(String corpusId) {
        CorpusRecord record = getRecord(corpusId);
        return record == null || record.originalFileName == null ? null
                : corpusRoot.resolve(corpusId).resolve("original")
                        .resolve(sanitizeFileName(record.originalFileName));
    }

    public void writeNif(String corpusId, OutputStream output) {
        requireSafeId(corpusId);
        if (!TextNifRepository.get().containsCorpus(corpusId)) {
            throw new IllegalArgumentException("Corpus NIF not found: " + corpusId);
        }
        TextNifRepository.get().writeCorpus(corpusId, output);
    }

    public boolean delete(String corpusId) throws IOException {
        requireSafeId(corpusId);
        synchronized (lockFor(corpusId)) {
            CorpusRecord record = getRecord(corpusId);
            if (record == null) {
                return false;
            }
            TextNifRepository.get().deleteCorpus(corpusId, record.corpusUri);
            deleteRecursively(corpusRoot.resolve(corpusId));
            return true;
        }
    }

    private static void copyMetadataValues(ParsedTextDocument source, CorpusRecord target) {
        for (Map.Entry<String, List<String>> entry : source.metadataValues.entrySet()) {
            target.metadataValues.put(entry.getKey(),
                    new ArrayList<String>(entry.getValue()));
        }
    }

    private Object lockFor(String corpusId) {
        Object created = new Object();
        Object existing = locks.putIfAbsent(corpusId, created);
        return existing == null ? created : existing;
    }

    private static void copyLimited(InputStream input, Path target, long maxBytes)
            throws IOException {
        if (input == null) {
            throw new IOException("Missing upload stream");
        }
        long limit = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        long written = 0L;
        byte[] buffer = new byte[8192];
        try (OutputStream out = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                written += read;
                if (written > limit) {
                    throw new IOException("File exceeds configured limit of " + limit + " bytes");
                }
                out.write(buffer, 0, read);
            }
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

    private static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing original filename");
        }
        String safe = Paths.get(name).getFileName().toString().replaceAll("[\\p{Cntrl}]", "_");
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) {
            throw new IllegalArgumentException("Invalid filename");
        }
        return safe;
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void requireSafeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid corpusId");
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.deleteIfExists(current);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
