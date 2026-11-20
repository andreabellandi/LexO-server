package it.cnr.ilc.lexo.service.data.lexint.output;

import java.util.Collections;
import java.util.Map;

public final class LexIntError {
    public String code;
    public String message;
    public String requestId;
    public Map<String, Object> details = Collections.emptyMap();

    public LexIntError() { }

    public LexIntError(String code, String message, String requestId,
            Map<String, Object> details) {
        this.code = code;
        this.message = message;
        this.requestId = requestId;
        this.details = details == null ? Collections.<String, Object>emptyMap()
                : details;
    }
}
