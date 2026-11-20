package it.cnr.ilc.lexo.manager.lexint;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.service.data.AuthenticationData;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Feature, identity-assurance and publisher-role policy. */
public final class LexIntAuthPolicy {
    public void requireEnabled() {
        if (!Boolean.parseBoolean(LexOProperties.getProperty(
                "LexInt.enabled", "false"))) {
            throw new LexIntException(403, "LEXINT_FORBIDDEN",
                    "LexInt services are disabled");
        }
    }

    public LexIntActor resolve(AuthenticationData authentication,
            String declaredAuthor) {
        boolean configured = configuredAuthentication();
        if (configured && authentication == null) {
            throw new LexIntException(401, "LEXINT_AUTH_REQUIRED",
                    "A valid bearer token is required");
        }
        if (authentication != null) {
            Set<String> roles = new LinkedHashSet<String>();
            List<String> declaredRoles = authentication.getRoles();
            if (declaredRoles != null) {
                roles.addAll(declaredRoles);
            }
            if (roles.isEmpty() && authentication.getType() != null) {
                String raw = authentication.getType().replace('[', ' ')
                        .replace(']', ' ');
                for (String role : raw.split(",")) {
                    if (!role.trim().isEmpty()) {
                        roles.add(role.trim());
                    }
                }
            }
            return new LexIntActor(LexiconCrudSupport.author(
                    authentication.getUsername()).trim(), "AUTHENTICATED", true,
                    authentication.getIssuer(), authentication.getSubject(),
                    roles);
        }
        return new LexIntActor(LexiconCrudSupport.author(declaredAuthor).trim(),
                "DECLARED", false, null, null,
                new LinkedHashSet<String>());
    }

    public void requirePublisher(LexIntActor actor) {
        if (actor == null) {
            throw new LexIntException(401, "LEXINT_AUTH_REQUIRED",
                    "Actor context is required");
        }
        if (actor.authenticated) {
            String required = LexOProperties.getProperty(
                    "LexInt.publication.requiredRole", "lexint-publisher");
            if (required != null && !required.trim().isEmpty()
                    && !actor.roles.contains(required.trim())) {
                throw new LexIntException(403, "LEXINT_FORBIDDEN",
                        "The configured publisher role is required");
            }
            return;
        }
        if (!Boolean.parseBoolean(LexOProperties.getProperty(
                "LexInt.publication.allowUnauthenticatedPublish", "false"))) {
            throw new LexIntException(403, "LEXINT_FORBIDDEN",
                    "Publication by a declared free-mode identity is disabled");
        }
    }

    public boolean configuredAuthentication() {
        String value = LexOProperties.getProperty("keycloack.url");
        return value != null && !value.trim().isEmpty();
    }
}
