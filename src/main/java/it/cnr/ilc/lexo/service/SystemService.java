package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.service.data.Info;
import it.cnr.ilc.lexo.service.helper.InfoHelper;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/** Runtime information and GraphDB-backed manager maintenance endpoints. */
@Path("system")
public class SystemService extends Service {

    private final InfoHelper infoHelper = new InfoHelper();

    @GET
    @Path("info")
    @Produces(MediaType.APPLICATION_JSON)
    public Response info() throws JsonProcessingException {
        Info data = infoHelper.newData();
        return Response.ok(infoHelper.toJson(data)).build();
    }

    @GET
    @Path("caches")
    public Response caches(@HeaderParam("Authorization") String key) {
        try {
            checkKey(key);
            ManagerFactory.loadCaches();
            return Response.ok().build();
        } catch (AuthorizationException | ServiceException e) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("Unauthorized").build();
        } catch (RuntimeException e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }
}
