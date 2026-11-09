package it.cnr.ilc.lexo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class GraphDbWordnetRoutingTest {

    @Test
    void everyRepositoryTargetUsesItsOwnConfigurationWithoutChangingDefaults()
            throws Exception {
        assertSettings(RepositoryTarget.LEXICON, "http://localhost:7200",
                "LexOLexica", "5");
        assertSettings(RepositoryTarget.TEXT, "http://localhost:7200",
                "LexOTexts", "5");
        assertSettings(RepositoryTarget.WORDNET, "http://localhost:7200",
                "LexOWordnets", "5");
    }

    @Test
    void missingWordnetPoolSizeDoesNotInheritTheLexicalPoolSize()
            throws Exception {
        Field effectiveField = LexOProperties.class.getDeclaredField("PROPERTIES");
        Field overridesField = LexOProperties.class.getDeclaredField(
                "RUNTIME_OVERRIDES");
        effectiveField.setAccessible(true);
        overridesField.setAccessible(true);
        Properties effective = (Properties) effectiveField.get(null);
        Properties overrides = (Properties) overridesField.get(null);
        String wordnetSize = effective.getProperty("WordnetGraphDb.size");
        String lexicalSize = effective.getProperty("GraphDb.size");
        String wordnetOverride = overrides.getProperty("WordnetGraphDb.size");
        try {
            effective.remove("WordnetGraphDb.size");
            overrides.remove("WordnetGraphDb.size");
            effective.setProperty("GraphDb.size", "17");
            assertSettings(RepositoryTarget.WORDNET, "http://localhost:7200",
                    "LexOWordnets", "5");
        } finally {
            if (wordnetSize != null) {
                effective.setProperty("WordnetGraphDb.size", wordnetSize);
            }
            if (wordnetOverride != null) {
                overrides.setProperty("WordnetGraphDb.size", wordnetOverride);
            }
            if (lexicalSize != null) {
                effective.setProperty("GraphDb.size", lexicalSize);
            }
        }
    }

    private static void assertSettings(RepositoryTarget target, String url,
            String repository, String size) throws Exception {
        Method method = GraphDbUtil.class.getDeclaredMethod("settings",
                RepositoryTarget.class);
        method.setAccessible(true);
        Object settings = method.invoke(null, target);
        assertThat(field(settings, "url")).isEqualTo(url);
        assertThat(field(settings, "repositoryId")).isEqualTo(repository);
        assertThat(field(settings, "poolSize")).isEqualTo(size);
    }

    private static String field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(target);
    }
}
