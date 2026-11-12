package it.cnr.ilc.lexo.service;

import io.swagger.annotations.*;
import it.cnr.ilc.lexo.manager.CppRunManager;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.cpp.*;
import it.cnr.ilc.lexo.service.data.cpp.input.*;
import it.cnr.ilc.lexo.service.data.cpp.output.*;
import java.util.List;
import java.util.function.Supplier;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import org.slf4j.LoggerFactory;

@Path("cpp")
@Api("CPP Runs")
@Produces(MediaType.APPLICATION_JSON)
public class CppRuns extends Service {
    @Context private UriInfo uriInfo;
    private final CppRunManager supplied;
    public CppRuns() { this(null); }
    public CppRuns(CppRunManager manager) { this.supplied=manager; }
    private CppRunManager manager() { return supplied==null?ManagerFactory.getManager(CppRunManager.class):supplied; }

    @POST @Path("runs") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Register a successful collocation extraction run",response=CppRunDetails.class,
            notes="Records declared effective parameters and existing FrAC IRIs without running Lucene. ordered is a separate client declaration. An Idempotency-Key replay returns 200.")
    @ApiImplicitParams({@ApiImplicitParam(name="request",paramType="body",dataTypeClass=CppRunCreateRequest.class,required=true,value="Extraction parameters, explicit ordered declaration, snapshot, index, software, optional author and selected FrAC IRIs")})
    public Response create(
            @ApiParam(value="Optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value="Optional caller-scoped retry key, up to 128 safe characters") @HeaderParam("Idempotency-Key") String idempotency,
            @ApiParam(value="Strict CPP JSON body",hidden=true) java.io.InputStream raw) {
        return invoke(key,()->{
            CppRunCreateRequest r=CppJsonCodec.read(raw,CppRunCreateRequest.class);
            CppRunManager.Creation created=manager().create(r,actor(r.author),idempotency);
            return Response.status(created.replayed?200:201)
                    .header("Location",uriInfo == null ? "runs/"+created.run.id : uriInfo.getAbsolutePathBuilder().path(created.run.id).build())
                    .type(MediaType.APPLICATION_JSON).entity(CppJsonCodec.write(created.run)).build();
        });
    }
    @GET @Path("runs")
    @ApiOperation(value="List recorded CPP runs",response=CppRunPage.class,
            notes="Exact filters combine in AND. Ordered by registration time descending, then IRI ascending. No inferred statements are consulted.")
    public Response list(
            @ApiParam(value="Optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value="Exact node string") @QueryParam("node") String node,
            @ApiParam(value="Associated FrAC IRI") @QueryParam("collocationIRI") String col,
            @ApiParam(value="Snapshot member IRI") @QueryParam("corpusIRI") String corpus,
            @ApiParam(value="Lucene metric name or CollScore measure individual IRI") @QueryParam("measure") String measure,
            @ApiParam(value="Exact registration author") @QueryParam("author") String author,
            @ApiParam(value="Inclusive registration timestamp lower bound") @QueryParam("from") String from,
            @ApiParam(value="Inclusive registration timestamp upper bound") @QueryParam("to") String to,
            @ApiParam(value="Page size, default 50, maximum configured by CPP.list.maxLimit") @QueryParam("limit") String limit,
            @ApiParam(value="Non-negative offset, default 0") @QueryParam("offset") String offset) {
        return invoke(key,()->{
            CppRunFilter f=new CppRunFilter(); f.node=node; f.collocationIRI=col; f.corpusIRI=corpus;
            f.measure=measure; f.author=author; f.from=from; f.to=to;
            f.limit=integer(limit,50); f.offset=integer(offset,0);
            return json(manager().list(f));
        });
    }
    @GET @Path("runs/{id}")
    @ApiOperation(value="Read a CPP run and its effective extraction parameters",response=CppRunDetails.class)
    public Response get(
            @ApiParam(value="Optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value="Canonical lowercase run UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller for audit context") @QueryParam("author") String author) {
        return invoke(key,()->{ actor(author); return json(manager().get(id)); });
    }
    @PATCH @Path("runs/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Correct only the collocation associations of a CPP run",response=CppRunDetails.class,
            notes="Historical methodological parameters are immutable. The last association cannot be removed; delete the run instead. Repeated adds and removals are idempotent.")
    @ApiImplicitParams({@ApiImplicitParam(name="request",paramType="body",dataTypeClass=CppRunPatchRequest.class,required=true,value="Non-overlapping addCollocations and removeCollocations arrays")})
    public Response patch(
            @ApiParam(value="Optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value="Canonical lowercase run UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller for audit context") @QueryParam("author") String author,
            @ApiParam(value="Strict association JSON body",hidden=true) java.io.InputStream raw) {
        return invoke(key,()->json(manager().patchAssociations(id,CppJsonCodec.read(raw,CppRunPatchRequest.class),actor(author))));
    }
    @DELETE @Path("runs/{id}")
    @ApiOperation(value="Delete CPP provenance while preserving FrAC data",code=204)
    public Response delete(
            @ApiParam(value="Optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value="Canonical lowercase run UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller for audit context") @QueryParam("author") String author) {
        return invoke(key,()->{ actor(author); manager().delete(id); return Response.noContent().build(); });
    }
    @GET @Path("runs/export") @Produces({"text/turtle",MediaType.APPLICATION_JSON})
    @ApiOperation(value="Export selected or all CPP runs as flattened Turtle",produces="text/turtle",
            notes="A validated RDF extract, not a named-graph backup. Optional FrAC descriptions preserve existing scores. Limits fail explicitly before the download begins.")
    public Response export(
            @ApiParam(value="Optional authorization header") @HeaderParam("Authorization") String key,
            @ApiParam(value="Repeatable absolute run IRI; omitted selects all") @QueryParam("run") List<String> runs,
            @ApiParam(value="Include explicit FrAC descriptions and internal score/count data; default false") @QueryParam("includeCollocations") String include,
            @ApiParam(value="Optional caller for audit context") @QueryParam("author") String author) {
        return invoke(key,()->{
            actor(author);
            if(include!=null && !"true".equals(include) && !"false".equals(include)) { throw CppException.invalid("includeCollocations must be true or false"); }
            CppTurtleExport file=manager().exportTurtle(runs,"true".equals(include));
            try {
                return Response.ok((StreamingOutput) file::writeTo,"text/turtle; charset=UTF-8")
                        .header("Content-Disposition","attachment; filename=\"cpp-runs.ttl\"")
                        .header("X-Content-Type-Options","nosniff").build();
            } catch(RuntimeException e) {
                try { file.close(); } catch(java.io.IOException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
        });
    }
    private String actor(String author) { return LexiconCrudSupport.author(getUser(author)); }
    private Response invoke(String key,Supplier<Response> action) {
        try { authenticationData=null; checkKey(key); return action.get(); }
        catch(AuthorizationException | ServiceException e) { return error(401,"CPP_UNAUTHORIZED","Authorization required"); }
        catch(CppException e) { return error(e.status,e.code,e.getMessage()); }
        catch(RuntimeException e) {
            LoggerFactory.getLogger(getClass()).error("CPP operation failed",e);
            return error(500,"CPP_STORAGE_ERROR","CPP repository operation failed");
        }
    }
    private Response json(Object value) { return Response.ok(CppJsonCodec.write(value),MediaType.APPLICATION_JSON).build(); }
    private Response error(int status,String code,String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(CppJsonCodec.write(new CppError(code,message))).build();
    }
    private int integer(String text,int fallback) {
        try { return text==null?fallback:Integer.parseInt(text); }
        catch(NumberFormatException e) { throw CppException.invalid("Pagination requires integers"); }
    }
}
