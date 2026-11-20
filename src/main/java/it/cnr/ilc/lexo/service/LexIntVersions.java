package it.cnr.ilc.lexo.service;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.LexIntPublicationManager;
import it.cnr.ilc.lexo.manager.ManagerFactory;
import it.cnr.ilc.lexo.manager.lexint.LexIntException;
import it.cnr.ilc.lexo.manager.lexint.LexIntJsonCodec;
import it.cnr.ilc.lexo.manager.lexint.LexIntResourceUris;
import it.cnr.ilc.lexo.service.data.lexint.input.VersionBaselineRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.VersionDraftRequest;
import it.cnr.ilc.lexo.service.data.lexint.output.VersionDetails;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
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
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

@Path("lexint/versions")
@Api("LexInt Versions")
@Produces(MediaType.APPLICATION_JSON)
public final class LexIntVersions extends LexIntServiceBase {
    @Context private UriInfo uriInfo;
    private final LexIntPublicationManager supplied;

    public LexIntVersions() { this(null); }
    public LexIntVersions(LexIntPublicationManager manager) {
        this.supplied = manager;
    }
    private LexIntPublicationManager manager() {
        return supplied == null
                ? ManagerFactory.getManager(LexIntPublicationManager.class)
                : supplied;
    }

    @POST
    @Path("baseline")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Create or retrieve the immutable baseline version",
            response=VersionDetails.class,
            notes="Publisher-only when authentication is active. Repeating the operation returns the existing baseline without copying the active graph again.")
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",
            dataTypeClass=VersionBaselineRequest.class,required=true,
            value="Language and optional free-mode author"))
    public Response baseline(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Strict baseline JSON",hidden=true) InputStream body) {
        return invoke(authorization, () -> {
            VersionBaselineRequest request = LexIntJsonCodec.read(body,
                    VersionBaselineRequest.class);
            LexIntPublicationManager.VersionCreation result = manager().baseline(
                    request.language, actor(request.author, true));
            if (result.replayed) {
                return json(result.version, result.version.etag);
            }
            return created(result.version, versionLocation(result.version),
                    result.version.etag);
        });
    }

    @POST
    @Path("drafts")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value="Reserve the next draft version",
            response=VersionDetails.class,
            notes="Reserves a monotonic server version code without creating a snapshot graph.")
    @ApiImplicitParams(@ApiImplicitParam(name="request",paramType="body",
            dataTypeClass=VersionDraftRequest.class,required=true,
            value="Language and optional free-mode author"))
    public Response reserveDraft(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Strict version JSON",hidden=true) InputStream body) {
        return invoke(authorization, () -> {
            VersionDraftRequest request = LexIntJsonCodec.read(body,
                    VersionDraftRequest.class);
            VersionDetails result = manager().reserveDraft(request.language,
                    actor(request.author, true));
            return created(result, versionLocation(result), result.etag);
        });
    }

    @GET
    @ApiOperation(value="List LexInt versions",response=VersionDetails.class,
            responseContainer="List")
    public Response list(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author,
            @ApiParam(value="Optional ISO 639 language") @QueryParam("language") String language,
            @ApiParam(value="Page size") @QueryParam("limit") String limit,
            @ApiParam(value="Page offset") @QueryParam("offset") String offset) {
        return invoke(authorization, () -> {
            actor(author, false);
            return json(manager().listVersions(language, integer(limit, 50),
                    integer(offset, 0)));
        });
    }

    @GET
    @Path("{id}")
    @ApiOperation(value="Read one LexInt version",response=VersionDetails.class)
    public Response get(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical version UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization, () -> {
            actor(author, false);
            VersionDetails result = manager().getVersion(id);
            return json(result, result.etag);
        });
    }

    @DELETE
    @Path("{id}")
    @ApiOperation(value="Delete an unused reserved version",code=204)
    public Response delete(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical version UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization, () -> {
            actor(author, true);
            manager().deleteReservedVersion(id);
            return Response.noContent().build();
        });
    }

    @GET
    @Path("{id}/snapshot")
    @Produces("text/turtle")
    @ApiOperation(value="Download the immutable version snapshot as Turtle",
            response=String.class)
    public Response snapshot(
            @ApiParam(value="Optional bearer authorization header") @HeaderParam("Authorization") String authorization,
            @ApiParam(value="Canonical version UUID",required=true) @PathParam("id") String id,
            @ApiParam(value="Optional caller identity in free mode") @QueryParam("author") String author) {
        return invoke(authorization, () -> {
            actor(author, false);
            Model model = manager().snapshot(id);
            int maximum = LexIntResourceUris.limit(
                    "LexInt.write.maxBytes", 1048576);
            BoundedByteArrayOutputStream output =
                    new BoundedByteArrayOutputStream(maximum);
            Rio.write(model, output, RDFFormat.TURTLE);
            byte[] bytes = output.toByteArray();
            return Response.ok(new String(bytes, StandardCharsets.UTF_8),
                    "text/turtle; charset=UTF-8").build();
        });
    }

    private String versionLocation(VersionDetails version) {
        return uriInfo == null ? "versions/" + version.id
                : uriInfo.getBaseUriBuilder().path("lexint/versions/")
                        .path(version.id).build().toString();
    }

    private static final class BoundedByteArrayOutputStream
            extends ByteArrayOutputStream {
        private final int maximum;

        BoundedByteArrayOutputStream(int maximum) {
            super(Math.min(maximum, 8192));
            this.maximum = maximum;
        }

        @Override public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override public synchronized void write(byte[] value, int offset,
                int length) {
            requireCapacity(length);
            super.write(value, offset, length);
        }

        private void requireCapacity(int additional) {
            if (additional < 0 || (long) count + additional > maximum) {
                throw new LexIntException(413, "LEXINT_LIMIT_EXCEEDED",
                        "Snapshot serialization exceeds the configured limit");
            }
        }
    }
}
