package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.manager.text.TextJobManager.TextJobInfo;
import it.cnr.ilc.lexo.manager.text.TextJobManager.TextJobState;
import it.cnr.ilc.lexo.service.data.text.output.BulkTextJob;
import it.cnr.ilc.lexo.service.data.text.output.BulkTextJobItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates existing per-document conversion jobs without coupling their rollback. */
public final class TextBulkJobManager {

    public enum BulkTextJobState {
        PENDING, RUNNING, COMPLETED, PARTIALLY_COMPLETED, FAILED, CANCELLED
    }

    public static final class BulkUpload {
        public final String fileId;
        public final String originalFileName;

        public BulkUpload(String fileId, String originalFileName) {
            this.fileId = fileId;
            this.originalFileName = originalFileName;
        }
    }

    private static final TextBulkJobManager INSTANCE = new TextBulkJobManager();

    private final Map<String, BulkRecord> bulks =
            new ConcurrentHashMap<String, BulkRecord>();

    private TextBulkJobManager() {
    }

    public static TextBulkJobManager get() {
        return INSTANCE;
    }

    public BulkTextJob start(String bulkId, String language, String corpusId,
                             List<BulkUpload> uploads) {
        if (bulkId == null || bulkId.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing bulkId");
        }
        if (uploads == null || uploads.isEmpty()) {
            throw new IllegalArgumentException("Missing bulk uploads");
        }

        List<BulkItemRecord> itemRecords = new ArrayList<BulkItemRecord>(uploads.size());
        for (BulkUpload upload : uploads) {
            itemRecords.add(new BulkItemRecord(upload.fileId, upload.originalFileName));
        }
        BulkRecord record = new BulkRecord(bulkId, language, blankToNull(corpusId),
                Collections.unmodifiableList(itemRecords));
        if (bulks.putIfAbsent(bulkId, record) != null) {
            throw new IllegalStateException("A bulk text job already exists for " + bulkId);
        }

        for (BulkItemRecord item : itemRecords) {
            try {
                item.job = TextJobManager.get().startConversion(
                        item.fileId, record.corpusId);
            } catch (Throwable e) {
                item.startupError = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                TextJobManager.get().cleanupUpload(item.fileId);
            }
        }
        return snapshot(record);
    }

    public BulkTextJob get(String bulkId) {
        if (bulkId == null || bulkId.trim().isEmpty()) {
            return null;
        }
        BulkRecord record = bulks.get(bulkId.trim());
        return record == null ? null : snapshot(record);
    }

    private BulkTextJob snapshot(BulkRecord record) {
        BulkTextJob result = new BulkTextJob();
        result.bulkId = record.bulkId;
        result.language = record.language;
        result.corpusId = record.corpusId;
        result.total = record.items.size();

        List<TextJobState> states = new ArrayList<TextJobState>(record.items.size());
        for (BulkItemRecord itemRecord : record.items) {
            BulkTextJobItem item = new BulkTextJobItem();
            item.fileId = itemRecord.fileId;
            item.originalFileName = itemRecord.originalFileName;
            TextJobInfo job = itemRecord.job;
            TextJobState state;
            if (itemRecord.startupError != null) {
                state = TextJobState.FAILED;
                item.message = itemRecord.startupError;
            } else if (job == null) {
                state = TextJobState.FAILED;
                item.message = "Conversion job not found";
            } else {
                state = job.state;
                item.progress = job.progress;
                item.message = job.message;
                item.resultId = job.resultId;
                item.issues = job.issues;
            }
            item.state = state.name();
            states.add(state);
            if (state == TextJobState.COMPLETED) {
                result.completed++;
            } else if (state == TextJobState.FAILED) {
                result.failed++;
            } else if (state == TextJobState.CANCELLED) {
                result.cancelled++;
            }
            result.items.add(item);
        }
        result.state = aggregateState(states).name();
        return result;
    }

    static BulkTextJobState aggregateState(List<TextJobState> states) {
        if (states == null || states.isEmpty()) {
            return BulkTextJobState.PENDING;
        }
        int pending = 0;
        int running = 0;
        int completed = 0;
        int cancelled = 0;
        for (TextJobState state : states) {
            if (state == TextJobState.PENDING) {
                pending++;
            } else if (state == TextJobState.RUNNING) {
                running++;
            } else if (state == TextJobState.COMPLETED) {
                completed++;
            } else if (state == TextJobState.CANCELLED) {
                cancelled++;
            }
        }
        if (pending == states.size()) {
            return BulkTextJobState.PENDING;
        }
        if (pending > 0 || running > 0) {
            return BulkTextJobState.RUNNING;
        }
        if (completed == states.size()) {
            return BulkTextJobState.COMPLETED;
        }
        if (completed > 0) {
            return BulkTextJobState.PARTIALLY_COMPLETED;
        }
        if (cancelled == states.size()) {
            return BulkTextJobState.CANCELLED;
        }
        return BulkTextJobState.FAILED;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static final class BulkRecord {
        final String bulkId;
        final String language;
        final String corpusId;
        final List<BulkItemRecord> items;

        BulkRecord(String bulkId, String language, String corpusId,
                   List<BulkItemRecord> items) {
            this.bulkId = bulkId;
            this.language = language;
            this.corpusId = corpusId;
            this.items = items;
        }
    }

    private static final class BulkItemRecord {
        final String fileId;
        final String originalFileName;
        volatile TextJobInfo job;
        volatile String startupError;

        BulkItemRecord(String fileId, String originalFileName) {
            this.fileId = fileId;
            this.originalFileName = originalFileName;
        }
    }
}
