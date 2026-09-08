package it.cnr.ilc.lexo.manager;

/** Stable HTTP classification for the new export, independent of legacy errors. */
public class WebAnnotationExportException extends ManagerException {
    public final int httpStatus;

    public WebAnnotationExportException(int httpStatus, String code, String detail) {
        super(code + ": " + detail);
        this.httpStatus = httpStatus;
    }
}
