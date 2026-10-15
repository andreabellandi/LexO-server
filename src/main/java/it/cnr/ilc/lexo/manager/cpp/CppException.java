package it.cnr.ilc.lexo.manager.cpp;

public final class CppException extends RuntimeException {
    public final int status;
    public final String code;
    public CppException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static CppException invalid(String message) {
        return new CppException(400, "CPP_INVALID_PARAMETER", message);
    }
}
