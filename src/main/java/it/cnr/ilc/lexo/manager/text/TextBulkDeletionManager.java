package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.service.data.text.input.TextBulkDeletionInput;
import it.cnr.ilc.lexo.service.data.text.output.TextBulkDeletionItem;
import it.cnr.ilc.lexo.service.data.text.output.TextBulkDeletionJob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Runs independent text deletions as one asynchronous aggregate job. */
public final class TextBulkDeletionManager {

    public static final int DEFAULT_MAX_FILES = 100;

    enum JobState {
        PENDING, RUNNING, COMPLETED, PARTIALLY_COMPLETED, FAILED
    }

    enum ItemState {
        PENDING, RUNNING, DELETED, NOT_FOUND, FAILED
    }

    interface TextDeleter {
        boolean delete(String fileId) throws IOException;
    }

    private static final TextBulkDeletionManager INSTANCE =
            new TextBulkDeletionManager(
                    Executors.newSingleThreadExecutor(),
                    fileId -> TextJobManager.get().delete(fileId),
                    positiveIntProperty("lexo.text.maxBulkDeleteFiles",
                            DEFAULT_MAX_FILES));

    private final Executor executor;
    private final TextDeleter deleter;
    private final int maxFiles;
    private final Map<String, JobRecord> jobs =
            new ConcurrentHashMap<String, JobRecord>();

    TextBulkDeletionManager(Executor executor, TextDeleter deleter,
                            int maxFiles) {
        this.executor = executor;
        this.deleter = deleter;
        this.maxFiles = maxFiles;
    }

    public static TextBulkDeletionManager get() {
        return INSTANCE;
    }

    public TextBulkDeletionJob start(TextBulkDeletionInput input) {
        List<String> fileIds = validate(input);
        String bulkId = UUID.randomUUID().toString();
        List<ItemRecord> items = new ArrayList<ItemRecord>(fileIds.size());
        for (String fileId : fileIds) {
            items.add(new ItemRecord(fileId));
        }
        JobRecord record = new JobRecord(bulkId,
                Collections.unmodifiableList(items));
        jobs.put(bulkId, record);
        try {
            executor.execute(() -> execute(record));
        } catch (RuntimeException e) {
            jobs.remove(bulkId);
            throw e;
        }
        return snapshot(record);
    }

    public TextBulkDeletionJob get(String bulkId) {
        if (bulkId == null || bulkId.trim().isEmpty()) {
            return null;
        }
        JobRecord record = jobs.get(bulkId.trim());
        return record == null ? null : snapshot(record);
    }

    private List<String> validate(TextBulkDeletionInput input) {
        if (input == null) {
            throw new IllegalArgumentException(
                    "MISSING_DELETE_REQUEST: request body is required");
        }
        if (input.fileIds == null || input.fileIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "MISSING_FILE_IDS: fileIds must not be empty");
        }
        if (input.fileIds.size() > maxFiles) {
            throw new IllegalArgumentException(
                    "BULK_DELETE_FILE_LIMIT_EXCEEDED: at most " + maxFiles
                            + " fileIds are allowed");
        }
        List<String> result = new ArrayList<String>(input.fileIds.size());
        Set<String> unique = new HashSet<String>();
        for (int index = 0; index < input.fileIds.size(); index++) {
            String value = input.fileIds.get(index);
            if (value == null || ".".equals(value) || "..".equals(value)
                    || !value.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("INVALID_FILE_ID: fileIds["
                        + index + "] is invalid");
            }
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                        "DUPLICATE_FILE_ID: " + value);
            }
            result.add(value);
        }
        return result;
    }

    private void execute(JobRecord record) {
        record.state = JobState.RUNNING;
        for (ItemRecord item : record.items) {
            item.state = ItemState.RUNNING;
            try {
                item.state = deleter.delete(item.fileId)
                        ? ItemState.DELETED : ItemState.NOT_FOUND;
            } catch (Exception e) {
                item.message = message(e);
                item.state = ItemState.FAILED;
            }
        }
        int failures = 0;
        for (ItemRecord item : record.items) {
            if (item.state == ItemState.FAILED) {
                failures++;
            }
        }
        if (failures == 0) {
            record.state = JobState.COMPLETED;
        } else if (failures == record.items.size()) {
            record.state = JobState.FAILED;
        } else {
            record.state = JobState.PARTIALLY_COMPLETED;
        }
    }

    private TextBulkDeletionJob snapshot(JobRecord record) {
        TextBulkDeletionJob result = new TextBulkDeletionJob();
        result.bulkId = record.bulkId;
        result.state = record.state.name();
        result.total = record.items.size();
        for (ItemRecord itemRecord : record.items) {
            TextBulkDeletionItem item = new TextBulkDeletionItem();
            item.fileId = itemRecord.fileId;
            item.state = itemRecord.state.name();
            item.message = itemRecord.message;
            if (itemRecord.state == ItemState.DELETED) {
                result.deleted++;
            } else if (itemRecord.state == ItemState.NOT_FOUND) {
                result.notFound++;
            } else if (itemRecord.state == ItemState.FAILED) {
                result.failed++;
            }
            result.items.add(item);
        }
        return result;
    }

    private static String message(Exception error) {
        return error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static int positiveIntProperty(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class JobRecord {
        final String bulkId;
        final List<ItemRecord> items;
        volatile JobState state = JobState.PENDING;

        JobRecord(String bulkId, List<ItemRecord> items) {
            this.bulkId = bulkId;
            this.items = items;
        }
    }

    private static final class ItemRecord {
        final String fileId;
        volatile ItemState state = ItemState.PENDING;
        volatile String message;

        ItemRecord(String fileId) {
            this.fileId = fileId;
        }
    }
}
