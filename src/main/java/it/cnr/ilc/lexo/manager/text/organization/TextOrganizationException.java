package it.cnr.ilc.lexo.manager.text.organization;

/** Domain failure with a stable API error code. */
public final class TextOrganizationException extends RuntimeException {

    public enum Status {
        BAD_REQUEST, NOT_FOUND, CONFLICT, INTERNAL_ERROR
    }

    public final String code;
    public final Status status;

    public TextOrganizationException(Status status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public TextOrganizationException(Status status, String code, String message,
                                     Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }
}
