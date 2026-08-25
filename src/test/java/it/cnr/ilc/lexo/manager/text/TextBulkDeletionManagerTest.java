package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.text.input.TextBulkDeletionInput;
import it.cnr.ilc.lexo.service.data.text.output.TextBulkDeletionJob;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class TextBulkDeletionManagerTest {

    @Test
    void runsIndependentDeletionsAndReportsEveryOutcome() {
        ManualExecutor executor = new ManualExecutor();
        List<String> calls = new ArrayList<String>();
        TextBulkDeletionManager manager = new TextBulkDeletionManager(
                executor, fileId -> {
                    calls.add(fileId);
                    if ("failure".equals(fileId)) {
                        throw new IOException("filesystem unavailable");
                    }
                    return !"missing".equals(fileId);
                }, 100);

        TextBulkDeletionJob accepted = manager.start(new TextBulkDeletionInput(
                Arrays.asList("file-a", "failure", "missing", "file-b")));

        assertThat(accepted.state).isEqualTo("PENDING");
        assertThat(accepted.items).extracting(item -> item.state)
                .containsOnly("PENDING");

        executor.runNext();
        TextBulkDeletionJob completed = manager.get(accepted.bulkId);

        assertThat(completed.state).isEqualTo("PARTIALLY_COMPLETED");
        assertThat(completed.total).isEqualTo(4);
        assertThat(completed.deleted).isEqualTo(2);
        assertThat(completed.notFound).isEqualTo(1);
        assertThat(completed.failed).isEqualTo(1);
        assertThat(completed.items).extracting(item -> item.fileId)
                .containsExactly("file-a", "failure", "missing", "file-b");
        assertThat(completed.items).extracting(item -> item.state)
                .containsExactly("DELETED", "FAILED", "NOT_FOUND", "DELETED");
        assertThat(completed.items.get(1).message)
                .isEqualTo("filesystem unavailable");
        assertThat(calls).containsExactly("file-a", "failure", "missing", "file-b");
    }

    @Test
    void treatsNotFoundAsACompletedIdempotentOutcome() {
        ManualExecutor executor = new ManualExecutor();
        TextBulkDeletionManager manager = new TextBulkDeletionManager(
                executor, fileId -> false, 100);

        TextBulkDeletionJob accepted = manager.start(new TextBulkDeletionInput(
                Arrays.asList("missing-a", "missing-b")));
        executor.runNext();

        TextBulkDeletionJob completed = manager.get(accepted.bulkId);
        assertThat(completed.state).isEqualTo("COMPLETED");
        assertThat(completed.deleted).isZero();
        assertThat(completed.notFound).isEqualTo(2);
        assertThat(completed.failed).isZero();
    }

    @Test
    void reportsFailedWhenEveryDeletionFails() {
        ManualExecutor executor = new ManualExecutor();
        TextBulkDeletionManager manager = new TextBulkDeletionManager(
                executor, fileId -> {
                    throw new IOException("failure for " + fileId);
                }, 100);

        TextBulkDeletionJob accepted = manager.start(new TextBulkDeletionInput(
                Arrays.asList("file-a", "file-b")));
        executor.runNext();

        assertThat(manager.get(accepted.bulkId).state).isEqualTo("FAILED");
        assertThat(manager.get(accepted.bulkId).failed).isEqualTo(2);
    }

    @Test
    void validatesTheWholeRequestBeforeScheduling() {
        ManualExecutor executor = new ManualExecutor();
        TextBulkDeletionManager manager = new TextBulkDeletionManager(
                executor, fileId -> true, 2);

        assertThatThrownBy(() -> manager.start(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING_DELETE_REQUEST");
        assertThatThrownBy(() -> manager.start(new TextBulkDeletionInput(
                Collections.<String>emptyList())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING_FILE_IDS");
        assertThatThrownBy(() -> manager.start(new TextBulkDeletionInput(
                Arrays.asList("file-a", "file-a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DUPLICATE_FILE_ID");
        assertThatThrownBy(() -> manager.start(new TextBulkDeletionInput(
                Arrays.asList("file-a", "../file-b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_FILE_ID");
        assertThatThrownBy(() -> manager.start(new TextBulkDeletionInput(
                Arrays.asList("file-a", ".."))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INVALID_FILE_ID");
        assertThatThrownBy(() -> manager.start(new TextBulkDeletionInput(
                Arrays.asList("file-a", "file-b", "file-c"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BULK_DELETE_FILE_LIMIT_EXCEEDED");
        assertThat(executor.pending()).isZero();
    }

    private static final class ManualExecutor implements Executor {

        private final List<Runnable> tasks = new ArrayList<Runnable>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove(0).run();
        }
    }
}
