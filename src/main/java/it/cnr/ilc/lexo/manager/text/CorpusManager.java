package it.cnr.ilc.lexo.manager.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.service.data.text.output.CorpusRecord;
import java.io.IOException;
import java.io.InputStream;
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

/** Persistent corpus descriptors and their document membership. */
public final class CorpusManager {

    private static final CorpusManager INSTANCE = new CorpusManager();

    public static CorpusManager get() {
        return INSTANCE;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CorpusRecord> records = new ConcurrentHashMap<String, CorpusRecord>();
    private final Map<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final Path root;
    private final Path corpusRoot;
    private final Path workRoot;
    private final NifModelWriter writer;

    private CorpusManager() {
        root = Paths.get(System.getProperty("lexo.text.storage.dir", "data/texts"));
        corpusRoot = root.resolve("corpora");
        workRoot = root.resolve("work");
        writer = new NifModelWriter(
                System.getProperty("lexo.text.publicBaseUri",
                        "https://lexo.ilc.cnr.it/resources/texts/"),
                System.getProperty("lexo.text.structureNamespace",
                        "https://lexo.ilc.cnr.it/vocabulary/nif-structure#"));
        try {
            Files.createDirectories(corpusRoot);
            Files.createDirectories(workRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize corpus storage at " + root, e);
        }
    }

    public CorpusRecord create(String corpusId, InputStream input, String originalName,
                               long maxBytes) throws IOException, TextValidationException {
        requireSafeId(corpusId);
        String safeName = sanitizeFileName(originalName);
        if (!safeName.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            throw new IllegalArgumentException("A .txt metadata file is required");
        }
        Object lock = lockFor(corpusId);
        synchronized (lock) {
            Path finalDir = corpusRoot.resolve(corpusId);
            if (Files.exists(finalDir)) {
                throw new IllegalStateException("Corpus already exists: " + corpusId);
            }
            Path workDir = workRoot.resolve("corpus-" + corpusId + "-" + UUID.randomUUID());
            try {
                Path originalDir = workDir.resolve("original");
                Files.createDirectories(originalDir);
                Path original = originalDir.resolve(safeName);
                copyLimited(input, original, maxBytes);
                ParsedTextDocument metadata = new ControlledCommonMarkParser()
                        .parseMetadataOnly(readUtf8Strict(original));

                CorpusRecord record = new CorpusRecord();
                record.corpusId = corpusId;
                record.corpusUri = writer.corpusUri(corpusId);
                record.originalFileName = safeName;
                record.originalPath = relative(finalDir.resolve("original").resolve(safeName));
                record.nifPath = relative(finalDir.resolve("corpus.ttl"));
                record.metadataPath = relative(finalDir.resolve("metadata.json"));
                record.createdAt = Instant.now().toString();
                record.updatedAt = record.createdAt;
                record.metadata.putAll(metadata.metadata);
                copyMetadataValues(metadata, record);

                writer.writeCorpus(workDir.resolve("corpus.ttl"), corpusId, safeName,
                        metadata, record.documentUris);
                mapper.writerWithDefaultPrettyPrinter().writeValue(
                        workDir.resolve("metadata.json").toFile(), record);
                moveDirectory(workDir, finalDir);
                records.put(corpusId, record);
                return record;
            } catch (IOException | RuntimeException | TextValidationException e) {
                deleteRecursively(workDir);
                throw e;
            }
        }
    }

    public CorpusRecord getRecord(String corpusId) {
        requireSafeId(corpusId);
        CorpusRecord cached = records.get(corpusId);
        if (cached != null) {
            return cached;
        }
        Path metadata = corpusRoot.resolve(corpusId).resolve("metadata.json");
        if (!Files.exists(metadata)) {
            return null;
        }
        try {
            CorpusRecord loaded = mapper.readValue(metadata.toFile(), CorpusRecord.class);
            CorpusRecord previous = records.putIfAbsent(corpusId, loaded);
            return previous == null ? loaded : previous;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read corpus metadata for " + corpusId, e);
        }
    }

    public String requireCorpusUri(String corpusId) {
        CorpusRecord record = getRecord(corpusId);
        if (record == null) {
            throw new IllegalArgumentException("Corpus not found: " + corpusId);
        }
        return record.corpusUri;
    }

    public Path getNif(String corpusId) {
        CorpusRecord record = getRecord(corpusId);
        return record == null ? null : resolveRelative(record.nifPath);
    }

    public Path getOriginal(String corpusId) {
        CorpusRecord record = getRecord(corpusId);
        return record == null ? null : resolveRelative(record.originalPath);
    }

    public void addDocument(String corpusId, String fileId, String documentUri) throws IOException {
        updateMembership(corpusId, fileId, documentUri, true);
    }

    public void removeDocument(String corpusId, String fileId, String documentUri) throws IOException {
        updateMembership(corpusId, fileId, documentUri, false);
    }

    private void updateMembership(String corpusId, String fileId, String documentUri,
                                  boolean add) throws IOException {
        requireSafeId(corpusId);
        synchronized (lockFor(corpusId)) {
            CorpusRecord current = getRecord(corpusId);
            if (current == null) {
                throw new IllegalArgumentException("Corpus not found: " + corpusId);
            }
            CorpusRecord updated = copyRecord(current);
            String contextUri = documentUri + "#context";
            if (add) {
                if (!updated.documentIds.contains(fileId)) {
                    updated.documentIds.add(fileId);
                    updated.documentUris.add(contextUri);
                }
            } else {
                int index = updated.documentIds.indexOf(fileId);
                if (index >= 0) {
                    updated.documentIds.remove(index);
                    if (index < updated.documentUris.size()) {
                        updated.documentUris.remove(index);
                    } else {
                        updated.documentUris.remove(contextUri);
                    }
                }
            }
            updated.updatedAt = Instant.now().toString();
            rewriteAtomically(updated);
            records.put(corpusId, updated);
        }
    }

    private void rewriteAtomically(CorpusRecord record) throws IOException {
        Path dir = corpusRoot.resolve(record.corpusId);
        Path nif = dir.resolve("corpus.ttl");
        Path metadataPath = dir.resolve("metadata.json");
        String suffix = "." + UUID.randomUUID();
        Path newNif = dir.resolve(".corpus" + suffix + ".ttl");
        Path newMetadata = dir.resolve(".metadata" + suffix + ".json");
        Path oldNif = dir.resolve(".corpus" + suffix + ".bak");
        Path oldMetadata = dir.resolve(".metadata" + suffix + ".bak");
        try {
            ParsedTextDocument parsed = new ControlledCommonMarkParser().parseMetadataOnly(
                    readUtf8Strict(resolveRelative(record.originalPath)));
            writer.writeCorpus(newNif, record.corpusId, record.originalFileName,
                    parsed, record.documentUris);
            mapper.writerWithDefaultPrettyPrinter().writeValue(newMetadata.toFile(), record);
            Files.copy(nif, oldNif, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(metadataPath, oldMetadata, StandardCopyOption.REPLACE_EXISTING);
            replaceFile(newNif, nif);
            replaceFile(newMetadata, metadataPath);
        } catch (TextValidationException e) {
            throw new IOException("Stored corpus descriptor is no longer valid", e);
        } catch (IOException e) {
            restore(oldNif, nif);
            restore(oldMetadata, metadataPath);
            throw e;
        } finally {
            Files.deleteIfExists(newNif);
            Files.deleteIfExists(newMetadata);
            Files.deleteIfExists(oldNif);
            Files.deleteIfExists(oldMetadata);
        }
    }

    private static CorpusRecord copyRecord(CorpusRecord source) {
        CorpusRecord copy = new CorpusRecord();
        copy.corpusId = source.corpusId;
        copy.corpusUri = source.corpusUri;
        copy.originalFileName = source.originalFileName;
        copy.originalPath = source.originalPath;
        copy.nifPath = source.nifPath;
        copy.metadataPath = source.metadataPath;
        copy.createdAt = source.createdAt;
        copy.updatedAt = source.updatedAt;
        copy.metadata.putAll(source.metadata);
        for (Map.Entry<String, List<String>> entry : source.metadataValues.entrySet()) {
            copy.metadataValues.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
        copy.documentIds.addAll(source.documentIds);
        copy.documentUris.addAll(source.documentUris);
        return copy;
    }

    private static void copyMetadataValues(ParsedTextDocument source, CorpusRecord target) {
        for (Map.Entry<String, List<String>> entry : source.metadataValues.entrySet()) {
            target.metadataValues.put(entry.getKey(), new ArrayList<String>(entry.getValue()));
        }
    }

    private Object lockFor(String corpusId) {
        Object created = new Object();
        Object existing = locks.putIfAbsent(corpusId, created);
        return existing == null ? created : existing;
    }

    private String relative(Path path) {
        return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private Path resolveRelative(String path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(path).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalStateException("Artifact path escapes the configured text storage");
        }
        return resolved;
    }

    private static void copyLimited(InputStream input, Path target, long maxBytes) throws IOException {
        if (input == null) {
            throw new IOException("Missing upload stream");
        }
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

    private static void replaceFile(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restore(Path backup, Path target) {
        if (Files.exists(backup)) {
            try {
                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
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
