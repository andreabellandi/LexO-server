/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package it.cnr.ilc.lexo.service;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import it.cnr.ilc.lexo.manager.ExportManager;
import it.cnr.ilc.lexo.manager.ManagerException;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.service.data.lexicon.input.ExportSetting;
import it.cnr.ilc.lexo.service.helper.HelperException;
import it.cnr.ilc.lexo.util.LogUtil;
import java.io.File;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author andreabellandi
 */
@Path("export")
@Api("Export")
public class Export extends Service {

    private static final Logger logger = LoggerFactory.getLogger(Export.class);
    Logger statLog = LoggerFactory.getLogger("statistics");

    private final ExportManager exportManager = ManagerFactory.getManager(ExportManager.class);

    @POST
    @Path("lexicon")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Lexicon export",
            notes = "This method exports the lexicon according to the input settings")
    public Response export(@QueryParam("key") String key, ExportSetting set) throws HelperException {
        try {
            log(Level.INFO, "export/lexicon\n" + LogUtil.getLogFromPayload(set));
            File export = exportManager.export(set);
            return Response.ok(export)
                    .type(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=\"" + export.getName() + "\"")
                    .header("Access-Control-Allow-Headers", "content-type")
                    .header("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, OPTIONS")
                    .build();
        } catch (ManagerException ex) {
             log(Level.ERROR, "export/lexicon: " + ex.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).type(MediaType.TEXT_PLAIN).entity(ex.getMessage()).build();
        }
    }

}
