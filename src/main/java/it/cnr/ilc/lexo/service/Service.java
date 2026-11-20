package it.cnr.ilc.lexo.service;

import io.swagger.annotations.ExternalDocs;
import io.swagger.annotations.Info;
import io.swagger.annotations.SwaggerDefinition;
import it.cnr.ilc.lexo.LexOFilter;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.UserManager;
import it.cnr.ilc.lexo.service.data.AuthenticationData;
import it.cnr.ilc.lexo.util.LogSanitizer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 *
 * @author andreabellandi
 */
@SwaggerDefinition(
        info = @Info(
                description = "OntoLex-Lemon lexica manager",
                version = "V0.1",
                title = "LexO API"//,
        //                license = @License(
        //                        name = "Apache 2.0",
        //                        url = "http://www.apache.org/licenses/LICENSE-2.0"
        //                )
        ),
        consumes = {"application/json", "application/xml"},
        produces = {"application/json", "application/xml"},
        schemes = {SwaggerDefinition.Scheme.HTTPS, SwaggerDefinition.Scheme.HTTP},
        externalDocs = @ExternalDocs(value = "LexO-backend", url = "https://github.com/andreabellandi/LexO-backend")
)
abstract class Service {

    protected AuthenticationData authenticationData;
    private final String ANONYMOUS_USER = "ANONYMOUS";

    private UserManager userManager = null;

    protected void checkKey(String key) throws AuthorizationException, ServiceException {
        String keycloakUrl = LexOProperties.getProperty("keycloack.url");
        if (keycloakUrl != null && !keycloakUrl.trim().isEmpty()) {
            if (key == null || key.length() <= 7
                    || !key.regionMatches(true, 0, "Bearer ", 0, 7)
                    || key.substring(7).trim().isEmpty()) {
                throw new ServiceException(
                        "A valid bearer authorization header is required");
            }
            if (userManager == null) {
                userManager = ManagerFactory.getManager(UserManager.class);
            }
            try {
                // LexO-server server was configured with Keycloack
                authenticationData = userManager.authorize(
                        key.substring(7).trim());
                setActorContext(authenticationData == null
                        ? null : authenticationData.getUsername());
            } catch (Exception ex) {
                throw new AuthorizationException(ex.getMessage());
            }
        }
    }

    protected String getUser(String author) {
        String user;
        if (authenticationData != null) {
            user = authenticationData.getUsername();
        } else {
            if (author != null) {
                user = author;
            } else {
                user = "anonymous";
            }
        }
        setActorContext(user);
        return user;
    }
    
    protected void log(Level level, String message) {
        Logger.getLogger(LexOFilter.CONTEXT).log(level,
                "[" + (authenticationData != null ? authenticationData.getUsername().toUpperCase() : ANONYMOUS_USER) + "] "
                + message);
    }

    protected void log(Level level, String message, Throwable t) {
        String user = authenticationData == null ? ANONYMOUS_USER : authenticationData.getUsername();
        Logger.getLogger(LexOFilter.CONTEXT).log(level, "[" + user + "] " + message, t);
    }

    /** Logging entry point for retained services migrated to SLF4J 2. */
    protected void log(org.slf4j.event.Level level, String message) {
        LoggerFactory.getLogger(getClass()).atLevel(level)
                .log(LogSanitizer.singleLine(message));
    }

    /** Logging entry point for unexpected retained-service failures. */
    protected void log(org.slf4j.event.Level level, String message, Throwable t) {
        LoggerFactory.getLogger(getClass()).atLevel(level)
                .setCause(t)
                .log(LogSanitizer.singleLine(message));
    }

    protected final void setActorContext(String actor) {
        if (actor == null || actor.trim().isEmpty()) {
            MDC.remove("actor");
        } else {
            MDC.put("actor", LogSanitizer.singleLine(actor.trim()));
        }
    }

}
