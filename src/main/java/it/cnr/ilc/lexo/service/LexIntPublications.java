package it.cnr.ilc.lexo.service;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.LexIntPublicationManager;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.lexint.LexIntJsonCodec;
import it.cnr.ilc.lexo.service.data.lexint.input.PublicationRequest;
import it.cnr.ilc.lexo.service.data.lexint.output.AuditReportDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.PublicationDetails;
import java.io.InputStream;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

@Path("lexint/publications")
@Api("LexInt Publication & Audit")
@Produces(MediaType.APPLICATION_JSON)
public final class LexIntPublications extends LexIntServiceBase {
    @Context private UriInfo uriInfo;
    private final LexIntPublicationManager supplied;

    public LexIntPublications() { this(null); }
    public LexIntPublications(LexIntPublicationManager manager) {
        this.supplied = manager;
    }
    private LexIntPublicationManager manager() {
        return supplied == null
                ? ManagerFactory.getManager(LexIntPublicationManager.class)
                : supplied;
    }

    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Preview an explicit per-target publication plan",
            notes="Performs authorization, version, SHACL and delta checks without modifying any graph.")
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",
            dataTypeClass=PublicationRequest.class,required=true,
            value="Source/target versions and explicit target operations"))
    public Response preview(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Strict publication JSON",hidden=true) InputStream body) {
        return invoke(authorization, () -> {
            PublicationRequest request = LexIntJsonCodec.read(body,
                    PublicationRequest.class);
            return json(manager().preview(request, actor(request.author, true)));
        });
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Publish one validated version transactionally",
            response=PublicationDetails.class,
            notes="Creates an immutable snapshot, updates the current application graph and audit ledger in one repository transaction. Idempotent replay returns HTTP 200.")
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",
            dataTypeClass=PublicationRequest.class,required=true,
            value="Source/target versions and explicit target operations"))
    public Response publish(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Current source-version ETag",required=true) @HeaderParam("If-Match") String ifMatch,
            @ApiParam(value="Stable idempotency key",required=true) @HeaderParam("Idempotency-Key") String idempotencyKey,
            @ApiParam(value="Strict publication JSON",hidden=true) InputStream body) {
        return invoke(authorization, () -> {
            PublicationRequest request = LexIntJsonCodec.read(body,
                    PublicationRequest.class);
            PublicationDetails result = manager().publish(request,
                    actor(request.author, true), ifMatch, idempotencyKey,
                    requestId());
            if (result.replayed) {
                return json(result, result.targetEtag);
            }
            return created(result, publicationLocation(result),
                    result.targetEtag);
        });
    }

    @GET
    @ApiOperation(value="List publication records",response=PublicationDetails.class,
            responseContainer="List")
    public Response list(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author,
            @ApiParam(value="Optional ISO 639 language") @QueryParam("language") String language,
            @ApiParam(value="Source or target version IRI") @QueryParam("versionIri") String versionIri,
            @ApiParam(value="Resolved publication username") @QueryParam("username") String username,
            @ApiParam(value="Inclusive ISO date-time lower bound") @QueryParam("from") String from,
            @ApiParam(value="Inclusive ISO date-time upper bound") @QueryParam("to") String to,
            @ApiParam(value="Page size") @QueryParam("limit") String limit,
            @ApiParam(value="Page offset") @QueryParam("offset") String offset) {
        return invoke(authorization, () -> {
            actor(author, false);
            return json(manager().listPublications(language, versionIri,
                    username, from, to, integer(limit, 50), integer(offset, 0),
                    requestId()));
        });
    }

    @GET
    @Path("{id}")
    @ApiOperation(value="Read one publication and its audit history",
            response=PublicationDetails.class)
    public Response get(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical publication UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization, () -> {
            actor(author, false);
            return json(manager().getPublication(id, requestId()));
        });
    }

    @GET
    @Path("{id}/audit")
    @ApiOperation(value="Read the historical verification records",
            response=AuditReportDetails.class,responseContainer="List")
    public Response audit(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical publication UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization, () -> {
            actor(author, false);
            return json(manager().auditHistory(id));
        });
    }

    @POST
    @Path("{id}/verify")
    @ApiOperation(value="Recompute a historical publication audit",
            response=AuditReportDetails.class,
            notes="By default compares the immutable target snapshot. checkActive=true is allowed only while that version is current.")
    public Response verify(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical publication UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Also verify the active graph") @QueryParam("checkActive") String checkActive,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization, () -> {
            AuditReportDetails result = manager().verify(id,
                    Boolean.TRUE.equals(bool(checkActive)),
                    actor(author, true));
            String location = uriInfo == null
                    ? id + "/audit/" + result.verificationId
                    : uriInfo.getBaseUriBuilder().path("lexint/publications/")
                            .path(id).path("audit/")
                            .path(result.verificationId).build().toString();
            return created(result, location, null);
        });
    }

    private String publicationLocation(PublicationDetails publication) {
        return uriInfo == null ? "publications/" + publication.id
                : uriInfo.getBaseUriBuilder().path("lexint/publications/")
                        .path(publication.id).build().toString();
    }
}
