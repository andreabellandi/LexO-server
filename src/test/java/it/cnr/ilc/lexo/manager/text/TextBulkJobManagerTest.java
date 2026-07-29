package it.cnr.ilc.lexo.manager.text;

import static it.cnr.ilc.lexo.manager.text.TextBulkJobManager.BulkTextJobState.CANCELLED;
import static it.cnr.ilc.lexo.manager.text.TextBulkJobManager.BulkTextJobState.COMPLETED;
import static it.cnr.ilc.lexo.manager.text.TextBulkJobManager.BulkTextJobState.FAILED;
import static it.cnr.ilc.lexo.manager.text.TextBulkJobManager.BulkTextJobState.PARTIALLY_COMPLETED;
import static it.cnr.ilc.lexo.manager.text.TextBulkJobManager.BulkTextJobState.PENDING;
import static it.cnr.ilc.lexo.manager.text.TextBulkJobManager.BulkTextJobState.RUNNING;
import static it.cnr.ilc.lexo.manager.text.TextJobManager.TextJobState;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextBulkJobManagerTest {

    @Test
    @DisplayName("Aggregate bulk state follows pending and running document jobs")
    void aggregatesNonTerminalStates() {
        assertThat(TextBulkJobManager.aggregateState(Collections.<TextJobState>emptyList()))
                .isEqualTo(PENDING);
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.PENDING, TextJobState.PENDING))).isEqualTo(PENDING);
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.COMPLETED, TextJobState.PENDING))).isEqualTo(RUNNING);
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.FAILED, TextJobState.RUNNING))).isEqualTo(RUNNING);
    }

    @Test
    @DisplayName("Mixed terminal outcomes produce a partial bulk result")
    void aggregatesPartialResult() {
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.COMPLETED, TextJobState.FAILED)))
                .isEqualTo(PARTIALLY_COMPLETED);
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.COMPLETED, TextJobState.CANCELLED)))
                .isEqualTo(PARTIALLY_COMPLETED);
    }

    @Test
    @DisplayName("Uniform or unsuccessful terminal jobs produce final aggregate states")
    void aggregatesTerminalStates() {
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.COMPLETED, TextJobState.COMPLETED))).isEqualTo(COMPLETED);
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.FAILED, TextJobState.CANCELLED))).isEqualTo(FAILED);
        assertThat(TextBulkJobManager.aggregateState(Arrays.asList(
                TextJobState.CANCELLED, TextJobState.CANCELLED))).isEqualTo(CANCELLED);
    }
}
