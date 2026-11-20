package it.cnr.ilc.lexo.manager.lexint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable HTTP-facing LexInt failure. */
public final class LexIntException extends RuntimeException {
    public final int status;
    public final String code;
    public final Map<String, Object> details;

    public LexIntException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public LexIntException(int status, String code, String message,
            Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<String, Object>(details));
    }

    public static LexIntException invalid(String message) {
        return new LexIntException(400, "LEXINT_INVALID_REQUEST", message);
    }

    public static LexIntException notFound(String message) {
        return new LexIntException(404, "LEXINT_NOT_FOUND", message);
    }

    public static LexIntException immutable(String message) {
        return new LexIntException(409, "LEXINT_IMMUTABLE_RESOURCE", message);
    }
}
