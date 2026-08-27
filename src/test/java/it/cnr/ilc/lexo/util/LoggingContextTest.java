package it.cnr.ilc.lexo.util;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LoggingContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void capturesSubmissionContextAndRestoresWorkerContext() {
        MDC.put("requestId", "request-1");
        AtomicReference<String> observedRequest = new AtomicReference<String>();
        AtomicReference<String> observedFile = new AtomicReference<String>();
        Runnable wrapped = LoggingContext.wrap(() -> {
            observedRequest.set(MDC.get("requestId"));
            observedFile.set(MDC.get("fileId"));
        }, "fileId", "file\n1");

        MDC.clear();
        MDC.put("worker", "original");
        wrapped.run();

        assertThat(observedRequest.get()).isEqualTo("request-1");
        assertThat(observedFile.get()).isEqualTo("file_1");
        assertThat(MDC.get("worker")).isEqualTo("original");
        assertThat(MDC.get("requestId")).isNull();
    }
}
