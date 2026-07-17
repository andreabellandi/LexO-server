package it.cnr.ilc.lexo.service;

import io.swagger.annotations.ExternalDocs;
import io.swagger.annotations.Info;
import io.swagger.annotations.SwaggerDefinition;
import it.cnr.ilc.lexo.LexOFilter;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.UserManager;
import it.cnr.ilc.lexo.service.data.AuthenticationData;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

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
        if (LexOProperties.getProperty("keycloack.url") != null) {
            if (userManager == null) {
                userManager = ManagerFactory.getManager(UserManager.class);
            }
            try {
                // LexO-server server was configured with Keycloack
                if (null != key) {
                    authenticationData = userManager.authorize(key.substring(7));
                } else {
                    throw new ServiceException("authorization key is null");
                }
            } catch (Exception ex) {
                throw new AuthorizationException(ex.getMessage());
            }
        }
    }

    protected String getUser(String author) {
        if (authenticationData != null) {
            return authenticationData.getUsername();
        } else {
            if (author != null) {
                return author;
            } else {
                return "anonymous";
            }
        }
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

}
