package it.cnr.ilc.lexo.manager.lexint;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Server-resolved actor; DECLARED is deliberately not authentication. */
public final class LexIntActor {
    public final String username;
    public final String identityAssurance;
    public final boolean authenticated;
    public final String issuer;
    public final String subject;
    public final Set<String> roles;

    public LexIntActor(String username, String identityAssurance,
            boolean authenticated, String issuer, String subject,
            Set<String> roles) {
        this.username = username;
        this.identityAssurance = identityAssurance;
        this.authenticated = authenticated;
        this.issuer = issuer;
        this.subject = subject;
        this.roles = roles == null ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<String>(roles));
    }
}
