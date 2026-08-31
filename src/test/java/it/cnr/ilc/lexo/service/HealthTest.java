package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.RepositoryTarget;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class HealthTest {

    @Test
    void livenessDoesNotRequireGraphDb() {
        Health health = new Health(target -> false, () -> false);

        Response response = health.live();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity().toString()).contains("\"status\":\"UP\"");
    }

    @Test
    void readinessRequiresBootstrapAndBothRepositories() {
        Health health = new Health(target -> target == RepositoryTarget.LEXICON,
                () -> true);

        Response response = health.ready();

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getEntity().toString())
                .contains("\"status\":\"DOWN\"")
                .contains("\"lexiconRepository\":true")
                .contains("\"textRepository\":false");
    }

    @Test
    void readinessSucceedsWhenBootstrapAndRepositoriesAreReady() {
        Health health = new Health(target -> true, () -> true);

        Response response = health.ready();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getEntity().toString()).contains("\"status\":\"UP\"");
    }
}
