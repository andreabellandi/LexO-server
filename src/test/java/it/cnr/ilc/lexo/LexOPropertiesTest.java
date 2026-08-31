package it.cnr.ilc.lexo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LexOPropertiesTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesStableEnvironmentVariableNames() {
        assertThat(LexOProperties.environmentVariableName("GraphDb.url"))
                .isEqualTo("LEXO_GRAPH_DB_URL");
        assertThat(LexOProperties.environmentVariableName("TextGraphDb.repository"))
                .isEqualTo("LEXO_TEXT_GRAPH_DB_REPOSITORY");
        assertThat(LexOProperties.environmentVariableName("lexo.text.storage.dir"))
                .isEqualTo("LEXO_TEXT_STORAGE_DIR");
        assertThat(LexOProperties.environmentVariableName(
                "Bootstrap.startup.maxAttempts"))
                .isEqualTo("LEXO_BOOTSTRAP_STARTUP_MAX_ATTEMPTS");
    }

    @Test
    void systemPropertiesOverrideEnvironmentValues() {
        Properties source = new Properties();
        source.setProperty("GraphDb.url", "http://default:7200");
        Properties system = new Properties();
        system.setProperty("GraphDb.url", "http://system:7200");
        Map<String, String> environment = new HashMap<String, String>();
        environment.put("LEXO_GRAPH_DB_URL", "http://environment:7200");

        Properties resolved = LexOProperties.applySystemAndEnvironmentOverrides(
                source, system, environment);

        assertThat(resolved.getProperty("GraphDb.url"))
                .isEqualTo("http://system:7200");
    }

    @Test
    void loadAppliesSystemPropertyOverridesToTheEffectiveConfiguration() {
        String previousGraphDbUrl = System.getProperty("GraphDb.url");
        try {
            System.setProperty("GraphDb.url", "http://system-load:7200");

            LexOProperties.load();

            assertThat(LexOProperties.getProperty("GraphDb.url"))
                    .isEqualTo("http://system-load:7200");
        } finally {
            restore("GraphDb.url", previousGraphDbUrl);
            LexOProperties.load();
        }
    }

    @Test
    void loadsAnExternalFileWithoutRepackagingTheWar() throws Exception {
        Path configuration = temporaryDirectory.resolve("lexo.properties");
        Files.write(configuration,
                "GraphDb.url=http://external:7200\n".getBytes(StandardCharsets.UTF_8));
        String previousConfiguration = System.getProperty("lexo.config.file");
        String previousGraphDbUrl = System.getProperty("GraphDb.url");
        try {
            System.setProperty("lexo.config.file", configuration.toString());
            System.clearProperty("GraphDb.url");

            LexOProperties.load();

            assertThat(LexOProperties.getProperty("GraphDb.url"))
                    .isEqualTo("http://external:7200");
        } finally {
            restore("lexo.config.file", previousConfiguration);
            restore("GraphDb.url", previousGraphDbUrl);
            LexOProperties.load();
        }
    }

    private static void restore(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
