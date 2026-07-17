/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package it.cnr.ilc.lexo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import it.cnr.ilc.lexo.manager.AdministrationManager;
import it.cnr.ilc.lexo.manager.ManagerException;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.RepositoryStatisticsManager;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics;
import it.cnr.ilc.lexo.service.data.administration.output.SystemInfo;
import it.cnr.ilc.lexo.service.helper.SystemInfoHelper;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.log4j.Level;

/**
 *
 * @author andreabellandi
 */
@Path("administration")
@Api("Administration")
public class Administration extends Service {


    private final AdministrationManager administrationManager = ManagerFactory.getManager(AdministrationManager.class);
    private final RepositoryStatisticsManager repositoryStatisticsManager =
            ManagerFactory.getManager(RepositoryStatisticsManager.class);
    private final SystemInfoHelper systemInfoHelper = new SystemInfoHelper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @GET
    @Path("systemInfo")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "System information",
            notes = "This method returns some basic information system e.g., GraphDB version, disk space, attached repo")
    public Response nodeSummary(@HeaderParam("Authorization") String key) {
        String json = "";
        try {
            log(org.apache.log4j.Level.INFO, "administration/systemInfo");
            SystemInfo info = administrationManager.getSystemInfo();
            json = systemInfoHelper.toJson(info);
        } catch (ManagerException ex) {
            log(Level.ERROR, ex.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).type(MediaType.TEXT_PLAIN).entity(ex.getMessage()).build();
        }
        return Response.ok(json)
                    .type(MediaType.TEXT_PLAIN)
                    .header("Access-Control-Allow-Headers", "content-type")
                    .header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, OPTIONS")
                    .build();
    }

    @GET
    @Path("repositories")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Lexical and text repository statistics",
            notes = "Returns statement metrics, lexical resources and NIF corpus/text metadata")
    public Response repositories(@HeaderParam("Authorization") String key) {
        try {
            log(Level.INFO, "administration/repositories");
            RepositoryStatistics statistics = repositoryStatisticsManager.getStatistics();
            return Response.ok(objectMapper.writeValueAsString(statistics))
                    .type(MediaType.APPLICATION_JSON)
                    .header("Access-Control-Allow-Headers", "content-type")
                    .header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, OPTIONS")
                    .build();
        } catch (ManagerException | JsonProcessingException ex) {
            log(Level.ERROR, ex.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .type(MediaType.TEXT_PLAIN)
                    .entity(ex.getMessage())
                    .build();
        }
    }

}
