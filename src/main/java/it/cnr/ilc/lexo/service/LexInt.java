package it.cnr.ilc.lexo.service;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.LexIntManager;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.lexint.LexIntActor;
import it.cnr.ilc.lexo.manager.lexint.LexIntJsonCodec;
import it.cnr.ilc.lexo.service.data.lexint.input.ClaimCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.ClaimPatchRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.DecisionCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.DecisionPatchRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.TargetInput;
import it.cnr.ilc.lexo.service.data.lexint.output.ClaimDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.DecisionDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.TargetDetails;
import java.io.InputStream;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

@Path("lexint")
@Api("LexInt Management")
@Produces(MediaType.APPLICATION_JSON)
public final class LexInt extends LexIntServiceBase {
    @Context private UriInfo uriInfo;
    private final LexIntManager supplied;
    public LexInt() { this(null); }
    public LexInt(LexIntManager manager) { this.supplied = manager; }
    private LexIntManager manager() { return supplied == null
            ? ManagerFactory.getManager(LexIntManager.class) : supplied; }

    @POST @Path("claims") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Create an interpretive claim and its RDF 1.1 targets",
            response=ClaimDetails.class,
            notes="Creates the claim, all named target assertions and server audit metadata atomically; candidate triples are not materialized.")
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",
            dataTypeClass=ClaimCreateRequest.class,required=true,
            value="Strict claim JSON with description and one or more targets"))
    public Response createClaim(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Strict claim JSON",hidden=true) InputStream body) {
        return invoke(authorization, () -> {
            ClaimCreateRequest request=LexIntJsonCodec.read(body,ClaimCreateRequest.class);
            ClaimDetails result=manager().createClaim(request,actor(request.author,false));
            String location=uriInfo==null?"claims/"+result.id:uriInfo.getAbsolutePathBuilder().path(result.id).build().toString();
            return created(result,location,result.etag);
        });
    }

    @GET @Path("claims")
    @ApiOperation(value="List interpretive claims",response=ClaimDetails.class,
            responseContainer="List",notes="Stable IRI order with bounded pagination and conjunctive filters.")
    public Response listClaims(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author,
            @ApiParam(value="Candidate subject IRI") @QueryParam("entryIri") String entryIri,
            @ApiParam(value="Exact target assertion IRI") @QueryParam("targetIri") String targetIri,
            @ApiParam(value="Exact creator literal") @QueryParam("creator") String creator,
            @ApiParam(value="Whether a decision exists") @QueryParam("hasDecision") String hasDecision,
            @ApiParam(value="Page size") @QueryParam("limit") String limit,
            @ApiParam(value="Page offset") @QueryParam("offset") String offset) {
        return invoke(authorization,()->{ actor(author,false); return json(manager().listClaims(entryIri,targetIri,creator,bool(hasDecision),integer(limit,50),integer(offset,0))); });
    }

    @GET @Path("claims/{id}")
    @ApiOperation(value="Read an interpretive claim",response=ClaimDetails.class)
    public Response getClaim(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical claim UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization,()->{ actor(author,false); ClaimDetails r=manager().getClaim(id); return json(r,r.etag); });
    }

    @PATCH @Path("claims/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Update a draft interpretive claim",response=ClaimDetails.class,
            notes="Requires If-Match and is rejected after a finalized decision or publication reference.")
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",dataTypeClass=ClaimPatchRequest.class,required=true,value="Description and/or complete evidence replacement"))
    public Response patchClaim(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Current claim ETag",required=true) @HeaderParam("If-Match") String ifMatch,
            @ApiParam(value="Canonical claim UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Strict claim patch JSON",hidden=true) InputStream body) {
        return invoke(authorization,()->{ ClaimPatchRequest r=LexIntJsonCodec.read(body,ClaimPatchRequest.class); ClaimDetails out=manager().patchClaim(id,r,ifMatch,actor(r.author,false)); return json(out,out.etag); });
    }

    @DELETE @Path("claims/{id}")
    @ApiOperation(value="Delete an unreferenced draft claim",code=204)
    public Response deleteClaim(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical claim UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization,()->{ actor(author,false); manager().deleteClaim(id); return Response.noContent().build(); });
    }

    @POST @Path("claims/{id}/targets") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Add one RDF 1.1 target to a draft claim",response=TargetDetails.class)
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",dataTypeClass=TargetInput.class,required=true,value="Complete classic-reification target"))
    public Response addTarget(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical claim UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author,
            @ApiParam(value="Strict target JSON",hidden=true) InputStream body) {
        return invoke(authorization,()->{ TargetDetails out=manager().addTarget(id,LexIntJsonCodec.read(body,TargetInput.class),actor(author,false)); String location=uriInfo==null?"targets/"+out.id:uriInfo.getBaseUriBuilder().path("lexint/targets/").path(out.id).build().toString(); return created(out,location,out.etag); });
    }

    @GET @Path("targets/{id}")
    @ApiOperation(value="Read one target assertion",response=TargetDetails.class)
    public Response getTarget(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical target UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization,()->{ actor(author,false); TargetDetails out=manager().getTarget(id); return json(out,out.etag); });
    }

    @PATCH @Path("targets/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Replace a draft target RDF statement",response=TargetDetails.class)
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",dataTypeClass=TargetInput.class,required=true,value="Complete replacement target"))
    public Response patchTarget(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Current target ETag",required=true) @HeaderParam("If-Match") String ifMatch,
            @ApiParam(value="Canonical target UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author,
            @ApiParam(value="Strict target JSON",hidden=true) InputStream body) {
        return invoke(authorization,()->{ TargetDetails out=manager().patchTarget(id,LexIntJsonCodec.read(body,TargetInput.class),ifMatch,actor(author,false)); return json(out,out.etag); });
    }

    @DELETE @Path("targets/{id}")
    @ApiOperation(value="Delete a draft target while preserving claim cardinality",code=204)
    public Response deleteTarget(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical target UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization,()->{ manager().deleteTarget(id,actor(author,false)); return Response.noContent().build(); });
    }

    @POST @Path("decisions") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Create a version-scoped lexicographic decision",response=DecisionDetails.class,
            notes="Creates a draft PROV activity; it never materializes lexical RDF.")
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",dataTypeClass=DecisionCreateRequest.class,required=true,value="Claim, reserved version, outcome and description"))
    public Response createDecision(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Strict decision JSON",hidden=true) InputStream body) {
        return invoke(authorization,()->{ DecisionCreateRequest r=LexIntJsonCodec.read(body,DecisionCreateRequest.class); DecisionDetails out=manager().createDecision(r,actor(r.author,false)); String location=uriInfo==null?"decisions/"+out.id:uriInfo.getAbsolutePathBuilder().path(out.id).build().toString(); return created(out,location,out.etag); });
    }

    @GET @Path("decisions")
    @ApiOperation(value="List lexicographic decisions",response=DecisionDetails.class,responseContainer="List")
    public Response listDecisions(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author,
            @ApiParam(value="Exact claim IRI") @QueryParam("claimIri") String claimIri,
            @ApiParam(value="Exact target-version IRI") @QueryParam("versionIri") String versionIri,
            @ApiParam(value="Decision outcome") @QueryParam("outcome") String outcome,
            @ApiParam(value="Exact creator literal") @QueryParam("creator") String creator,
            @ApiParam(value="Finalized flag") @QueryParam("finalized") String finalized,
            @ApiParam(value="Page size") @QueryParam("limit") String limit,
            @ApiParam(value="Page offset") @QueryParam("offset") String offset) {
        return invoke(authorization,()->{ actor(author,false); return json(manager().listDecisions(claimIri,versionIri,outcome,creator,bool(finalized),integer(limit,50),integer(offset,0))); });
    }

    @GET @Path("decisions/{id}")
    @ApiOperation(value="Read a lexicographic decision",response=DecisionDetails.class)
    public Response getDecision(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical decision UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization,()->{ actor(author,false); DecisionDetails out=manager().getDecision(id); return json(out,out.etag); });
    }

    @PATCH @Path("decisions/{id}") @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Update a non-finalized decision",response=DecisionDetails.class)
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",dataTypeClass=DecisionPatchRequest.class,required=true,value="Mutable decision outcome, description or replacement link"))
    public Response patchDecision(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Current decision ETag",required=true) @HeaderParam("If-Match") String ifMatch,
            @ApiParam(value="Canonical decision UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Strict decision patch JSON",hidden=true) InputStream body) {
        return invoke(authorization,()->{ DecisionPatchRequest r=LexIntJsonCodec.read(body,DecisionPatchRequest.class); DecisionDetails out=manager().patchDecision(id,r,ifMatch,actor(r.author,false)); return json(out,out.etag); });
    }

    @DELETE @Path("decisions/{id}")
    @ApiOperation(value="Delete an unused non-finalized decision",code=204)
    public Response deleteDecision(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical decision UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization,()->{ actor(author,false); manager().deleteDecision(id); return Response.noContent().build(); });
    }

    @POST @Path("decisions/{id}/finalize")
    @ApiOperation(value="Finalize a decision without publishing RDF",response=DecisionDetails.class)
    public Response finalizeDecision(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical decision UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization,()->{ DecisionDetails out=manager().finalizeDecision(id,actor(author,false)); return json(out,out.etag); });
    }
}
