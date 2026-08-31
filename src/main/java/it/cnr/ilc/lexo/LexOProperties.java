package it.cnr.ilc.lexo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author andreabellandi
 */
public class LexOProperties {

    private static final String CLASSPATH_CONFIGURATION = "lexo-server.properties";
    private static final String CONFIG_FILE_PROPERTY = "lexo.config.file";
    private static final String CONFIG_FILE_ENVIRONMENT = "LEXO_CONFIG_FILE";
    private static final Properties PROPERTIES = new Properties();
    private static final Properties RUNTIME_OVERRIDES = new Properties();
    private static final Logger LOGGER = LoggerFactory.getLogger(LexOProperties.class);

    static {
        load();
    }

    public static synchronized void load() {
        Properties resolved = new Properties();
        loadClasspathDefaults(resolved);

        String externalConfiguration = externalConfigurationPath();
        if (externalConfiguration != null) {
            loadExternalConfiguration(resolved, Paths.get(externalConfiguration));
        }

        resolved = applySystemAndEnvironmentOverrides(resolved,
                System.getProperties(), System.getenv());
        resolved.putAll(RUNTIME_OVERRIDES);
        PROPERTIES.clear();
        PROPERTIES.putAll(resolved);
        LOGGER.debug("Loaded LexO configuration with {} effective properties",
                Integer.valueOf(PROPERTIES.size()));
    }

    public static synchronized String getProperty(String key) {
        return PROPERTIES.getProperty(key);
    }

    public static synchronized String getProperty(String key, String defaultValue) {
        return PROPERTIES.getProperty(key, defaultValue);
    }

    public static synchronized void setProperty(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("Property key and value are required");
        }
        RUNTIME_OVERRIDES.setProperty(key, value);
        PROPERTIES.setProperty(key, value);
    }

    static String environmentVariableName(String propertyName) {
        String value = propertyName == null ? "" : propertyName.trim();
        if (value.startsWith("lexo.")) {
            value = value.substring("lexo.".length());
        }
        StringBuilder normalized = new StringBuilder("LEXO_");
        char previous = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isLetterOrDigit(current)) {
                if (Character.isUpperCase(current) && index > 0
                        && (Character.isLowerCase(previous)
                        || Character.isDigit(previous))
                        && normalized.charAt(normalized.length() - 1) != '_') {
                    normalized.append('_');
                }
                normalized.append(Character.toUpperCase(current));
            } else if (normalized.charAt(normalized.length() - 1) != '_') {
                normalized.append('_');
            }
            previous = current;
        }
        return normalized.toString();
    }

    static Properties applySystemAndEnvironmentOverrides(Properties source,
            Properties systemProperties, Map<String, String> environment) {
        Properties resolved = new Properties();
        resolved.putAll(source);
        List<String> keys = new ArrayList<String>(resolved.stringPropertyNames());
        for (String key : keys) {
            String environmentValue = environment == null
                    ? null : environment.get(environmentVariableName(key));
            if (notBlank(environmentValue)) {
                resolved.setProperty(key, environmentValue.trim());
            }
            String systemValue = systemProperties == null
                    ? null : systemProperties.getProperty(key);
            if (notBlank(systemValue)) {
                resolved.setProperty(key, systemValue.trim());
            }
        }
        return resolved;
    }

    private static void loadClasspathDefaults(Properties target) {
        try (InputStream input = LexOProperties.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_CONFIGURATION)) {
            if (input == null) {
                throw new IllegalStateException("Missing classpath configuration: "
                        + CLASSPATH_CONFIGURATION);
            }
            target.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load classpath configuration: "
                    + CLASSPATH_CONFIGURATION, e);
        }
    }

    private static void loadExternalConfiguration(Properties target, Path path) {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException("External LexO configuration is not readable: "
                    + path.toAbsolutePath());
        }
        try (InputStream input = Files.newInputStream(path)) {
            target.load(input);
            LOGGER.info("Loaded external LexO configuration from {}",
                    path.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load external LexO configuration: "
                    + path.toAbsolutePath(), e);
        }
    }

    private static String externalConfigurationPath() {
        String systemValue = System.getProperty(CONFIG_FILE_PROPERTY);
        if (notBlank(systemValue)) {
            return systemValue.trim();
        }
        String environmentValue = System.getenv(CONFIG_FILE_ENVIRONMENT);
        return notBlank(environmentValue) ? environmentValue.trim() : null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
