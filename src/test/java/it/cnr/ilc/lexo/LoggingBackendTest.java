package it.cnr.ilc.lexo;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.apache.logging.log4j.core.LoggerContext;
import org.slf4j.LoggerFactory;

class LoggingBackendTest {

    private static final List<String> FORBIDDEN = Arrays.asList(
            "org.apache.log4j", "java.util.logging", "System.out",
            "System.err", "printStackTrace");

    @Test
    void usesTheSingleLog4j2Slf4jBackend() {
        assertThat(LoggerFactory.getILoggerFactory().getClass().getName())
                .isEqualTo("org.apache.logging.slf4j.Log4jLoggerFactory");
    }

    @Test
    void productionConfigurationBuildsTheRollingJsonAppender() {
        String previousDirectory = System.getProperty("lexo.log.dir");
        System.setProperty("lexo.log.dir", "target/test-production-logs");
        LoggerContext context = new LoggerContext("production-config-test");
        try {
            context.setConfigLocation(Paths.get(System.getProperty("user.dir"),
                    "src/main/resources/log4j2.xml").toUri());
            assertThat(context.getConfiguration().getAppenders())
                    .containsKeys("APPLICATION_JSON", "CONSOLE");
        } finally {
            context.stop();
            if (previousDirectory == null) {
                System.clearProperty("lexo.log.dir");
            } else {
                System.setProperty("lexo.log.dir", previousDirectory);
            }
        }
    }

    @Test
    void retainedServicesDoNotUseLegacyLoggingApis() throws IOException {
        Path sourceRoot = Paths.get(System.getProperty("user.dir"),
                "src/main/java/it/cnr/ilc/lexo");
        List<Path> retainedFiles = Arrays.asList(
                sourceRoot.resolve("service/Metadata.java"),
                sourceRoot.resolve("service/Texts.java"),
                sourceRoot.resolve("service/Attestations.java"),
                sourceRoot.resolve("service/Lexicon.java"),
                sourceRoot.resolve("service/Conversion.java"),
                sourceRoot.resolve("manager/JobManager.java"));
        for (Path file : retainedFiles) {
            assertClean(file);
        }
        assertCleanTree(sourceRoot.resolve("manager/text"));
        assertCleanTree(sourceRoot.resolve("manager/converter"));
    }

    private static void assertCleanTree(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(LoggingBackendTest::assertCleanUnchecked);
        }
    }

    private static void assertCleanUnchecked(Path file) {
        try {
            assertClean(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertClean(Path file) throws IOException {
        String source = new String(Files.readAllBytes(file),
                StandardCharsets.UTF_8);
        for (String token : FORBIDDEN) {
            assertThat(source)
                    .describedAs("%s must not contain %s", file, token)
                    .doesNotContain(token);
        }
    }
}
