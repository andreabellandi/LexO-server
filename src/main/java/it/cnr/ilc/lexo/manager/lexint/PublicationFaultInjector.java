package it.cnr.ilc.lexo.manager.lexint;

/**
 * Internal publication hook used to prove transactional rollback in tests.
 *
 * <p>The production manager always uses {@link #NONE}. The hook is not
 * configurable through properties, REST, or request data.</p>
 */
public interface PublicationFaultInjector {
    PublicationFaultInjector NONE = new PublicationFaultInjector() {
        @Override public void afterProvisionalWrites() { }
    };

    /** Called after provisional graph/audit writes and before validation/commit. */
    void afterProvisionalWrites();
}
