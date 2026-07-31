package it.cnr.ilc.lexo.manager;

/** Workflow states and legal transitions for new lexical entries. */
public enum LexicalWorkflowStatus {
    WORKING("working"),
    COMPLETED("completed"),
    REVISED("revised");

    private final String value;

    LexicalWorkflowStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean canTransitionTo(LexicalWorkflowStatus next) {
        return (this == WORKING && next == COMPLETED)
                || (this == COMPLETED
                    && (next == WORKING || next == REVISED))
                || (this == REVISED && next == COMPLETED);
    }

    public static LexicalWorkflowStatus require(String value, String field) {
        if (value != null) {
            String normalized = value.trim();
            for (LexicalWorkflowStatus status : values()) {
                if (status.value.equals(normalized)) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("INVALID_STATUS: " + field
                + " must be working, completed, or revised");
    }
}
