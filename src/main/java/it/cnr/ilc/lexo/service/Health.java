package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.bootstrap.GraphDbBootstrap;
import java.util.function.BooleanSupplier;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/** Liveness and dependency-aware readiness endpoints for runtime orchestration. */
@Path("health")
@Api("Health")
public final class Health {

    interface RepositoryProbe {
        boolean isAvailable(RepositoryTarget target);
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final RepositoryProbe repositoryProbe;
    private final BooleanSupplier bootstrapProbe;

    public Health() {
        this(target -> GraphDbUtil.isAvailable(target),
                () -> GraphDbBootstrap.isInitialized());
    }

    Health(RepositoryProbe repositoryProbe, BooleanSupplier bootstrapProbe) {
        this.repositoryProbe = repositoryProbe;
        this.bootstrapProbe = bootstrapProbe;
    }

    @GET
    @Path("live")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Application liveness",
            notes = "Returns success while the LexO-server web application can serve HTTP requests")
    public Response live() {
        HealthStatus status = new HealthStatus();
        status.status = "UP";
        status.version = LexOProperties.getProperty("application.version", "unknown");
        return response(Response.Status.OK, status);
    }

    @GET
    @Path("ready")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Application readiness",
            notes = "Returns success only after bootstrap and both GraphDB repositories are available")
    public Response ready() {
        boolean bootstrapEnabled = Boolean.parseBoolean(
                LexOProperties.getProperty("Bootstrap.enabled", "true"));
        HealthStatus status = new HealthStatus();
        status.version = LexOProperties.getProperty("application.version", "unknown");
        status.bootstrap = !bootstrapEnabled || bootstrapProbe.getAsBoolean();
        status.lexiconRepository = repositoryProbe.isAvailable(RepositoryTarget.LEXICON);
        status.textRepository = repositoryProbe.isAvailable(RepositoryTarget.TEXT);
        boolean ready = status.bootstrap && status.lexiconRepository
                && status.textRepository;
        status.status = ready ? "UP" : "DOWN";
        return response(ready ? Response.Status.OK
                : Response.Status.SERVICE_UNAVAILABLE, status);
    }

    private Response response(Response.Status responseStatus, HealthStatus status) {
        try {
            return Response.status(responseStatus)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(mapper.writeValueAsString(status))
                    .build();
        } catch (JsonProcessingException ex) {
            return Response.serverError().type(MediaType.APPLICATION_JSON)
                    .entity("{\"status\":\"DOWN\"}").build();
        }
    }

    public static final class HealthStatus {
        public String status;
        public String version;
        public boolean bootstrap;
        public boolean lexiconRepository;
        public boolean textRepository;
    }
}
