package it.cnr.ilc.lexo.manager.text.organization;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.TextNifRepository;
import it.cnr.ilc.lexo.service.data.text.input.FolderBulkTextMoveRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderCreateRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderMoveRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderUpdateRequest;
import it.cnr.ilc.lexo.service.data.text.output.FolderBulkMoveResult;
import it.cnr.ilc.lexo.service.data.text.output.FolderMembershipResult;
import it.cnr.ilc.lexo.service.data.text.output.FolderNode;
import it.cnr.ilc.lexo.service.data.text.output.FolderRecord;
import it.cnr.ilc.lexo.service.data.text.output.FolderTextItem;
import it.cnr.ilc.lexo.service.data.text.output.FolderTree;
import it.cnr.ilc.lexo.service.data.text.output.TextRecord;
import it.cnr.ilc.lexo.service.data.text.output.UnfiledTexts;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Validates folder invariants and maps organization RDF to API DTOs. */
public final class TextFolderManager {

    interface TextResolver {
        TextRecord get(String fileId);
        List<String> listFileIds();
    }

    private static final class Holder {
        private static final TextFolderManager INSTANCE =
                new TextFolderManager(TextOrganizationRepository.get(),
                        new TextResolver() {
                            @Override
                            public TextRecord get(String fileId) {
                                return TextNifRepository.get()
                                        .getDocumentRecord(fileId);
                            }

                            @Override
                            public List<String> listFileIds() {
                                return TextNifRepository.get()
                                        .listDocumentFileIds();
                            }
                        }, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    public static TextFolderManager get() {
        return Holder.INSTANCE;
    }

    private static final String SAFE_ID = "[A-Za-z0-9._-]+";
    private static final int MAX_NAME_CODE_POINTS = 255;
    private static final Comparator<FolderRecord> FOLDER_ORDER =
            new Comparator<FolderRecord>() {
                @Override
                public int compare(FolderRecord left, FolderRecord right) {
                    String leftName = left.name == null ? "" : left.name;
                    String rightName = right.name == null ? "" : right.name;
                    int name = leftName.toLowerCase(Locale.ROOT).compareTo(
                            rightName.toLowerCase(Locale.ROOT));
                    return name != 0 ? name : left.folderId.compareTo(right.folderId);
                }
            };

    private final TextOrganizationRepository repository;
    private final TextResolver texts;
    private final Clock clock;
    private final Supplier<String> ids;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    TextFolderManager(TextOrganizationRepository repository, TextResolver texts,
                      Clock clock, Supplier<String> ids) {
        this.repository = repository;
        this.texts = texts;
        this.clock = clock;
        this.ids = ids;
    }

    public FolderRecord create(FolderCreateRequest request) {
        lock.writeLock().lock();
        try {
            if (request == null) {
                throw badRequest("INVALID_REQUEST", "A JSON request body is required.");
            }
            String name = requireName(request.name);
            String parentId = optionalFolderId(request.parentFolderId);
            if (parentId != null) {
                requireFolder(parentId);
            }
            String folderId = ids.get();
            requireFolderId(folderId);
            String timestamp = now();
            FolderRecord record = new FolderRecord();
            record.folderId = folderId;
            record.uri = repository.vocabulary().folderIri(folderId).stringValue();
            record.name = name;
            record.description = request.description;
            record.parentFolderId = parentId;
            record.createdAt = timestamp;
            record.modifiedAt = timestamp;
            repository.createFolder(record);
            return record;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public FolderTree tree(boolean includeTexts) {
        lock.readLock().lock();
        try {
            List<FolderRecord> folders = repository.listFolders();
            FolderTree result = new FolderTree();
            result.folders.addAll(buildForest(folders, includeTexts,
                    includeTexts ? repository.membershipsByContext()
                            : Collections.<String, String>emptyMap(), includeTexts
                            ? textsByContext()
                            : Collections.<String, TextRecord>emptyMap()));
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public FolderNode get(String folderId, boolean recursive,
                          boolean includeTexts) {
        lock.readLock().lock();
        try {
            String id = requireFolderId(folderId);
            List<FolderRecord> folders = repository.listFolders();
            Map<String, FolderRecord> byId = indexFolders(folders);
            FolderRecord selected = byId.get(id);
            if (selected == null) {
                throw notFound("FOLDER_NOT_FOUND", "Folder not found: " + id);
            }
            Map<String, List<FolderRecord>> children = childrenByParent(folders);
            Map<String, String> memberships = includeTexts
                    ? repository.membershipsByContext()
                    : Collections.<String, String>emptyMap();
            Map<String, TextRecord> textByContext = includeTexts
                    ? textsByContext()
                    : Collections.<String, TextRecord>emptyMap();
            if (recursive) {
                return buildNode(selected, children, memberships, textByContext,
                        includeTexts, new LinkedHashSet<String>());
            }
            FolderNode node = new FolderNode(selected);
            if (includeTexts) {
                node.texts.addAll(textItems(id, memberships, textByContext));
            }
            List<FolderRecord> direct = children.get(id);
            if (direct != null) {
                for (FolderRecord child : direct) {
                    node.children.add(new FolderNode(child));
                }
            }
            return node;
        } finally {
            lock.readLock().unlock();
        }
    }

    public FolderRecord update(String folderId, FolderUpdateRequest request) {
        lock.writeLock().lock();
        try {
            String id = requireFolderId(folderId);
            requireFolder(id);
            if (request == null) {
                throw badRequest("INVALID_REQUEST", "A JSON request body is required.");
            }
            repository.updateFolder(id, requireName(request.name),
                    request.description, now());
            return repository.getFolder(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public FolderRecord move(String folderId, FolderMoveRequest request) {
        lock.writeLock().lock();
        try {
            String id = requireFolderId(folderId);
            requireFolder(id);
            if (request == null) {
                throw badRequest("INVALID_REQUEST", "A JSON request body is required.");
            }
            String parentId = optionalFolderId(request.parentFolderId);
            if (parentId != null) {
                requireFolder(parentId);
                if (id.equals(parentId) || isDescendant(parentId, id,
                        repository.listFolders())) {
                    throw conflict("FOLDER_CYCLE",
                            "The target parent is the folder itself or one of its descendants.");
                }
            }
            repository.moveFolder(id, parentId, now());
            return repository.getFolder(id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean delete(String folderId) {
        lock.writeLock().lock();
        try {
            String id = requireFolderId(folderId);
            requireFolder(id);
            if (!repository.deleteEmptyFolder(id)) {
                throw conflict("FOLDER_NOT_EMPTY",
                        "A folder with direct children or texts cannot be deleted.");
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public FolderMembershipResult assign(String folderId, String fileId) {
        lock.writeLock().lock();
        try {
            String target = requireFolderId(folderId);
            requireFolder(target);
            TextRecord text = requireText(fileId);
            String context = contextUri(text);
            TextOrganizationRepository.MembershipChange change =
                    repository.moveText(context, target, now());
            return membershipResult(text.fileId, context, target,
                    change.previousFolderId, change.changed);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public FolderMembershipResult remove(String folderId, String fileId) {
        lock.writeLock().lock();
        try {
            String target = requireFolderId(folderId);
            requireFolder(target);
            TextRecord text = requireText(fileId);
            String context = contextUri(text);
            if (!repository.removeTextFromFolder(target, context, now())) {
                throw notFound("FOLDER_MEMBERSHIP_NOT_FOUND",
                        "The text is not a direct member of the requested folder.");
            }
            FolderMembershipResult result = membershipResult(text.fileId,
                    context, target, target, true);
            result.removed = Boolean.TRUE;
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public FolderBulkMoveResult bulkMove(FolderBulkTextMoveRequest request) {
        lock.writeLock().lock();
        try {
            if (request == null || request.fileIds == null
                    || request.fileIds.isEmpty()) {
                throw badRequest("INVALID_REQUEST",
                        "A non-empty fileIds list is required.");
            }
            int max = positiveInteger("lexo.text.maxBulkFiles", 100);
            if (request.fileIds.size() > max) {
                throw badRequest("INVALID_REQUEST",
                        "The request exceeds the maximum of " + max + " texts.");
            }
            String target = optionalFolderId(request.targetFolderId);
            if (target != null) {
                requireFolder(target);
            }
            Set<String> unique = new HashSet<String>();
            List<TextRecord> records = new ArrayList<TextRecord>();
            List<String> contexts = new ArrayList<String>();
            for (String fileId : request.fileIds) {
                String normalized = requireFileId(fileId);
                if (!unique.add(normalized)) {
                    throw badRequest("INVALID_REQUEST",
                            "Duplicate fileId: " + normalized);
                }
                TextRecord record = requireText(normalized);
                records.add(record);
                contexts.add(contextUri(record));
            }
            Map<String, TextOrganizationRepository.MembershipChange> changes =
                    repository.moveTexts(contexts, target, now());
            FolderBulkMoveResult result = new FolderBulkMoveResult();
            result.targetFolderId = target;
            result.total = records.size();
            for (int index = 0; index < records.size(); index++) {
                TextRecord record = records.get(index);
                String context = contexts.get(index);
                TextOrganizationRepository.MembershipChange change =
                        changes.get(context);
                FolderMembershipResult item = membershipResult(record.fileId,
                        context, target, change.previousFolderId, change.changed);
                result.texts.add(item);
                if (change.changed) {
                    result.changed++;
                }
            }
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public UnfiledTexts unfiled() {
        lock.readLock().lock();
        try {
            Set<String> filedContexts = repository.membershipsByContext().keySet();
            UnfiledTexts result = new UnfiledTexts();
            for (String fileId : texts.listFileIds()) {
                TextRecord record = texts.get(fileId);
                if (record == null) {
                    continue;
                }
                String context = contextUri(record);
                if (!filedContexts.contains(context)) {
                    result.texts.add(textItem(record, context));
                }
            }
            Collections.sort(result.texts, (left, right) -> left.fileId
                    .compareTo(right.fileId));
            result.total = result.texts.size();
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    public TextOrganizationRepository.MembershipSnapshot detachForTextDeletion(
            String contextUri) {
        lock.writeLock().lock();
        try {
            return repository.detachText(contextUri, now());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void restoreAfterFailedTextDeletion(
            TextOrganizationRepository.MembershipSnapshot snapshot) {
        lock.writeLock().lock();
        try {
            repository.restoreText(snapshot, now());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private List<FolderNode> buildForest(List<FolderRecord> folders,
                                         boolean includeTexts,
                                         Map<String, String> memberships,
                                         Map<String, TextRecord> textByContext) {
        Map<String, List<FolderRecord>> children = childrenByParent(folders);
        List<FolderRecord> roots = children.get(null);
        List<FolderNode> result = new ArrayList<FolderNode>();
        Set<String> visited = new LinkedHashSet<String>();
        if (roots != null) {
            for (FolderRecord root : roots) {
                result.add(buildNode(root, children, memberships, textByContext,
                        includeTexts, visited));
            }
        }
        if (visited.size() != folders.size()) {
            throw internal("Folder hierarchy contains a cycle or an unknown parent.", null);
        }
        return result;
    }

    private FolderNode buildNode(FolderRecord folder,
                                 Map<String, List<FolderRecord>> children,
                                 Map<String, String> memberships,
                                 Map<String, TextRecord> textByContext,
                                 boolean includeTexts, Set<String> visited) {
        if (!visited.add(folder.folderId)) {
            throw internal("Folder hierarchy contains a cycle.", null);
        }
        FolderNode node = new FolderNode(folder);
        if (includeTexts) {
            node.texts.addAll(textItems(folder.folderId, memberships,
                    textByContext));
        }
        List<FolderRecord> direct = children.get(folder.folderId);
        if (direct != null) {
            for (FolderRecord child : direct) {
                node.children.add(buildNode(child, children, memberships,
                        textByContext, includeTexts, visited));
            }
        }
        return node;
    }

    private List<FolderTextItem> textItems(String folderId,
                                           Map<String, String> memberships,
                                           Map<String, TextRecord> textByContext) {
        List<FolderTextItem> result = new ArrayList<FolderTextItem>();
        for (Map.Entry<String, String> entry : memberships.entrySet()) {
            if (!folderId.equals(entry.getValue())) {
                continue;
            }
            TextRecord record = textByContext.get(entry.getKey());
            if (record != null) {
                result.add(textItem(record, entry.getKey()));
            }
        }
        Collections.sort(result, (left, right) -> left.fileId
                .compareTo(right.fileId));
        return result;
    }

    private Map<String, TextRecord> textsByContext() {
        Map<String, TextRecord> result =
                new LinkedHashMap<String, TextRecord>();
        for (String fileId : texts.listFileIds()) {
            TextRecord record = texts.get(fileId);
            if (record != null) {
                result.put(contextUri(record), record);
            }
        }
        return result;
    }

    private Map<String, FolderRecord> indexFolders(List<FolderRecord> folders) {
        Map<String, FolderRecord> result = new LinkedHashMap<String, FolderRecord>();
        for (FolderRecord folder : folders) {
            result.put(folder.folderId, folder);
        }
        return result;
    }

    private Map<String, List<FolderRecord>> childrenByParent(
            List<FolderRecord> folders) {
        Map<String, List<FolderRecord>> result =
                new LinkedHashMap<String, List<FolderRecord>>();
        for (FolderRecord folder : folders) {
            List<FolderRecord> children = result.get(folder.parentFolderId);
            if (children == null) {
                children = new ArrayList<FolderRecord>();
                result.put(folder.parentFolderId, children);
            }
            children.add(folder);
        }
        for (List<FolderRecord> children : result.values()) {
            Collections.sort(children, FOLDER_ORDER);
        }
        return result;
    }

    private boolean isDescendant(String possibleDescendant, String folderId,
                                 List<FolderRecord> folders) {
        Map<String, FolderRecord> byId = indexFolders(folders);
        Set<String> visited = new HashSet<String>();
        String current = possibleDescendant;
        while (current != null && visited.add(current)) {
            if (folderId.equals(current)) {
                return true;
            }
            FolderRecord record = byId.get(current);
            current = record == null ? null : record.parentFolderId;
        }
        return false;
    }

    private FolderRecord requireFolder(String folderId) {
        FolderRecord folder = repository.getFolder(folderId);
        if (folder == null) {
            throw notFound("FOLDER_NOT_FOUND", "Folder not found: " + folderId);
        }
        return folder;
    }

    private TextRecord requireText(String fileId) {
        String id = requireFileId(fileId);
        TextRecord record = texts.get(id);
        if (record == null) {
            throw notFound("TEXT_NOT_FOUND", "Persisted text not found: " + id);
        }
        if (record.fileId == null) {
            record.fileId = id;
        }
        contextUri(record);
        return record;
    }

    private String contextUri(TextRecord record) {
        if (record.documentUri == null || record.documentUri.trim().isEmpty()) {
            throw internal("Persisted text has no document URI: " + record.fileId,
                    null);
        }
        return record.documentUri + "#context";
    }

    private FolderTextItem textItem(TextRecord record, String context) {
        FolderTextItem item = new FolderTextItem();
        item.fileId = record.fileId;
        item.contextUri = context;
        item.name = record.originalFileName;
        item.lemmaSearchAvailable = record.lemmaSearchAvailable;
        item.lemmaTokenCount = record.lemmaTokenCount;
        item.lemmaCoverage = record.lemmaCoverage;
        return item;
    }

    private FolderMembershipResult membershipResult(String fileId,
                                                     String contextUri,
                                                     String folderId,
                                                     String previousFolderId,
                                                     boolean changed) {
        FolderMembershipResult result = new FolderMembershipResult();
        result.fileId = fileId;
        result.contextUri = contextUri;
        result.folderId = folderId;
        result.previousFolderId = previousFolderId;
        result.changed = Boolean.valueOf(changed);
        return result;
    }

    private String requireFolderId(String folderId) {
        if (folderId == null || !folderId.trim().matches(SAFE_ID)) {
            throw badRequest("INVALID_FOLDER_ID", "Invalid folder identifier.");
        }
        return folderId.trim();
    }

    private String optionalFolderId(String folderId) {
        return folderId == null ? null : requireFolderId(folderId);
    }

    private String requireFileId(String fileId) {
        if (fileId == null || !fileId.trim().matches(SAFE_ID)) {
            throw badRequest("INVALID_FILE_ID", "Invalid text identifier.");
        }
        return fileId.trim();
    }

    private String requireName(String name) {
        if (name == null) {
            throw badRequest("INVALID_FOLDER_NAME", "Folder name is required.");
        }
        String normalized = name.trim();
        if (normalized.isEmpty()
                || normalized.codePointCount(0, normalized.length())
                        > MAX_NAME_CODE_POINTS) {
            throw badRequest("INVALID_FOLDER_NAME",
                    "Folder name must contain between 1 and 255 Unicode characters.");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw badRequest("INVALID_FOLDER_NAME",
                        "Folder name must not contain control characters.");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private static int positiveInteger(String name, int fallback) {
        try {
            int parsed = Integer.parseInt(LexOProperties.getProperty(name,
                    Integer.toString(fallback)));
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static TextOrganizationException badRequest(String code,
                                                        String message) {
        return new TextOrganizationException(
                TextOrganizationException.Status.BAD_REQUEST, code, message);
    }

    private static TextOrganizationException notFound(String code,
                                                      String message) {
        return new TextOrganizationException(
                TextOrganizationException.Status.NOT_FOUND, code, message);
    }

    private static TextOrganizationException conflict(String code,
                                                      String message) {
        return new TextOrganizationException(
                TextOrganizationException.Status.CONFLICT, code, message);
    }

    private static TextOrganizationException internal(String message,
                                                       Throwable cause) {
        return new TextOrganizationException(
                TextOrganizationException.Status.INTERNAL_ERROR,
                "TEXT_ORGANIZATION_ERROR", message, cause);
    }
}
