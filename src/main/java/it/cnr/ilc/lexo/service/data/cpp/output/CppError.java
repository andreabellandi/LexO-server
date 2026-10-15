package it.cnr.ilc.lexo.service.data.cpp.output;

public class CppError {
    public final String code, message;
    public final java.util.Map<String, String> details;
    public CppError(String code, String message) {
        this.code = code; this.message = message;
        this.details = java.util.Collections.emptyMap();
    }
}
