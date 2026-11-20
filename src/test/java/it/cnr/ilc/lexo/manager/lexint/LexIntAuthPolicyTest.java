package it.cnr.ilc.lexo.manager.lexint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.service.data.AuthenticationData;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexIntAuthPolicyTest {
    private String keycloak;
    private String unauthenticated;
    private String role;

    @BeforeEach
    void remember() {
        keycloak = LexOProperties.getProperty("keycloack.url");
        unauthenticated = value(
                "LexInt.publication.allowUnauthenticatedPublish");
        role = value("LexInt.publication.requiredRole");
    }

    @AfterEach
    void restore() {
        if (keycloak == null) {
            LexOProperties.clearPropertyOverride("keycloack.url");
        } else {
            LexOProperties.setProperty("keycloack.url", keycloak);
        }
        LexOProperties.setProperty(
                "LexInt.publication.allowUnauthenticatedPublish",
                unauthenticated);
        LexOProperties.setProperty("LexInt.publication.requiredRole", role);
    }

    @Test
    void authenticatedUsernameOverridesDeclaredAuthorAndRequiresRole() {
        LexOProperties.setProperty("keycloack.url",
                "https://identity.example.org/realm");
        LexOProperties.setProperty("LexInt.publication.requiredRole",
                "lexint-publisher");
        AuthenticationData authentication = new AuthenticationData();
        authentication.setUsername("token-user");
        authentication.setIssuer("https://identity.example.org/realm");
        authentication.setSubject("subject-1");
        authentication.setRoles(Arrays.asList("lexint-publisher"));
        LexIntAuthPolicy policy = new LexIntAuthPolicy();

        LexIntActor actor = policy.resolve(authentication, "json-author");
        assertThat(actor.username).isEqualTo("token-user");
        assertThat(actor.authenticated).isTrue();
        assertThat(actor.identityAssurance).isEqualTo("AUTHENTICATED");
        policy.requirePublisher(actor);

        authentication.setRoles(Arrays.asList("viewer"));
        LexIntActor viewer = policy.resolve(authentication, "publisher-name");
        assertThatThrownBy(() -> policy.requirePublisher(viewer))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(403));
    }

    @Test
    void freeModeIdentityIsDeclaredNotAuthenticatedAndDefaultsAnonymous() {
        LexOProperties.setProperty("keycloack.url", "");
        LexOProperties.setProperty(
                "LexInt.publication.allowUnauthenticatedPublish", "false");
        LexIntAuthPolicy policy = new LexIntAuthPolicy();
        LexIntActor actor = policy.resolve(null, "   ");

        assertThat(actor.username).isEqualTo("anonymous");
        assertThat(actor.authenticated).isFalse();
        assertThat(actor.identityAssurance).isEqualTo("DECLARED");
        assertThatThrownBy(() -> policy.requirePublisher(actor))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> assertThat(error.status).isEqualTo(403));

        LexOProperties.setProperty(
                "LexInt.publication.allowUnauthenticatedPublish", "true");
        policy.requirePublisher(actor);
        assertThat(policy.resolve(null, "  editor  ").username)
                .isEqualTo("editor");
    }

    @Test
    void configuredAuthenticationRejectsMissingTokenContext() {
        LexOProperties.setProperty("keycloack.url",
                "https://identity.example.org/realm");
        assertThatThrownBy(() -> new LexIntAuthPolicy().resolve(null, "editor"))
                .isInstanceOfSatisfying(LexIntException.class,
                        error -> {
                            assertThat(error.status).isEqualTo(401);
                            assertThat(error.code)
                                    .isEqualTo("LEXINT_AUTH_REQUIRED");
                        });
    }

    private String value(String key) {
        String current = LexOProperties.getProperty(key);
        return current == null ? "" : current;
    }
}
