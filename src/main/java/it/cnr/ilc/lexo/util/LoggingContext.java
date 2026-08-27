package it.cnr.ilc.lexo.util;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/** Captures and restores MDC when work crosses an executor boundary. */
public final class LoggingContext {

    private LoggingContext() {
    }

    public static Runnable wrap(Runnable task, String key, String value) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        Map<String, String> captured = MDC.getCopyOfContextMap();
        final Map<String, String> context = captured == null
                ? new HashMap<String, String>()
                : new HashMap<String, String>(captured);
        if (key != null && value != null) {
            context.put(key, LogSanitizer.singleLine(value));
        }
        return () -> runWithContext(task, context);
    }

    private static void runWithContext(Runnable task,
                                       Map<String, String> context) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            if (!context.isEmpty()) {
                MDC.setContextMap(context);
            }
            task.run();
        } finally {
            MDC.clear();
            if (previous != null && !previous.isEmpty()) {
                MDC.setContextMap(previous);
            }
        }
    }
}
