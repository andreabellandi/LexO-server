package it.cnr.ilc.lexo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the single GraphDB topology required by LexO-server.
 *
 * <p>The repository identifiers are runtime contracts, not build-profile
 * choices: every normal build must target the same local GraphDB instance and
 * let the startup bootstrap create the two repositories when necessary.</p>
 */
class GraphDbRuntimeConfigurationTest {

    @Test
    @DisplayName("Runtime always uses local GraphDB with LexOLexica and LexOTexts")
    void usesFixedLocalRepositories() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("lexo-server.properties")) {
            assertThat(input).as("lexo-server.properties must be packaged").isNotNull();
            properties.load(input);
        }

        assertThat(properties.getProperty("GraphDb.url"))
                .isEqualTo("http://localhost:7200");
        assertThat(properties.getProperty("GraphDb.repository"))
                .isEqualTo("LexOLexica");
        assertThat(properties.getProperty("TextGraphDb.url"))
                .isEqualTo("http://localhost:7200");
        assertThat(properties.getProperty("TextGraphDb.repository"))
                .isEqualTo("LexOTexts");
        assertThat(properties.getProperty("Bootstrap.enabled")).isEqualTo("true");
        assertThat(properties.getProperty("Bootstrap.required")).isEqualTo("true");
        assertThat(URI.create(properties.getProperty("Bootstrap.schema.baseIri")).isAbsolute())
                .as("RDF4J requires an absolute base IRI for schema imports")
                .isTrue();

        assertThat(properties.stringPropertyNames())
                .allSatisfy(key -> assertThat(properties.getProperty(key))
                        .as("property %s must not contain an unresolved Maven placeholder", key)
                        .doesNotContain("${"));
    }
}
