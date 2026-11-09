package it.cnr.ilc.lexo.manager.wordnet;

/** Stable HTTP-facing WordNet domain failure. */
public final class WordnetException extends RuntimeException {

    public final int httpStatus;
    public final String code;

    public WordnetException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
