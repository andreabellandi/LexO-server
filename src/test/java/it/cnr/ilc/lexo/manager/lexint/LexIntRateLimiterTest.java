package it.cnr.ilc.lexo.manager.lexint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.LexOProperties;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexIntRateLimiterTest {
    private String requests;
    private String identities;

    @BeforeEach
    void remember() {
        requests = value("LexInt.rateLimit.requestsPerMinute");
        identities = value("LexInt.rateLimit.maxIdentities");
    }

    @AfterEach
    void restore() {
        LexOProperties.setProperty("LexInt.rateLimit.requestsPerMinute",
                requests);
        LexOProperties.setProperty("LexInt.rateLimit.maxIdentities",
                identities);
    }

    @Test
    void limitsEachStableIdentityAndKeepsOtherIdentitiesIndependent() {
        LexOProperties.setProperty("LexInt.rateLimit.requestsPerMinute", "1");
        LexOProperties.setProperty("LexInt.rateLimit.maxIdentities", "10");
        String suffix = UUID.randomUUID().toString();
        LexIntActor first = actor("editor-" + suffix);
        LexIntActor second = actor("reviewer-" + suffix);
        LexIntRateLimiter limiter = new LexIntRateLimiter();

        limiter.check(first);
        limiter.check(second);
        assertThatThrownBy(() -> limiter.check(first))
                .isInstanceOfSatisfying(LexIntException.class, error -> {
                    assertThat(error.status).isEqualTo(429);
                    assertThat(error.code).isEqualTo("LEXINT_RATE_LIMITED");
                    assertThat(error.details).containsKey("retryAfterSeconds");
                });
    }

    private LexIntActor actor(String username) {
        return new LexIntActor(username, "DECLARED", false, null, null,
                Collections.<String>emptySet());
    }

    private String value(String key) {
        String current = LexOProperties.getProperty(key);
        return current == null ? "" : current;
    }
}
