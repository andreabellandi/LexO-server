package it.cnr.ilc.lexo.service;

import it.cnr.ilc.lexo.manager.lexint.LexIntActor;
import it.cnr.ilc.lexo.manager.lexint.LexIntAuthPolicy;
import it.cnr.ilc.lexo.manager.lexint.LexIntException;
import it.cnr.ilc.lexo.manager.lexint.LexIntJsonCodec;
import it.cnr.ilc.lexo.manager.lexint.LexIntRateLimiter;
import it.cnr.ilc.lexo.service.data.lexint.output.LexIntError;
import java.util.UUID;
import java.util.function.Supplier;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Shared identity, errors and strict JSON for the LexInt resources. */
abstract class LexIntServiceBase extends Service {
    private final LexIntAuthPolicy auth = new LexIntAuthPolicy();
    private static final LexIntRateLimiter RATE_LIMITER =
            new LexIntRateLimiter();

    protected Response invoke(String authorization, Supplier<Response> action) {
        try {
            authenticationData = null;
            checkKey(authorization);
            auth.requireEnabled();
            return action.get();
        } catch (AuthorizationException | ServiceException ex) {
            return error(new LexIntException(401, "LEXINT_AUTH_REQUIRED",
                    "A valid bearer token is required"));
        } catch (LexIntException ex) {
            return error(ex);
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(getClass()).error(
                    "Unexpected LexInt operation failure", ex);
            return error(new LexIntException(500,
                    "LEXINT_PUBLICATION_FAILURE",
                    "Unexpected LexInt operation failure"));
        }
    }

    protected LexIntActor actor(String declaredAuthor, boolean publisher) {
        LexIntActor resolved = auth.resolve(authenticationData, declaredAuthor);
        RATE_LIMITER.check(resolved);
        setActorContext(resolved.username);
        if (publisher) { auth.requirePublisher(resolved); }
        return resolved;
    }

    protected Response json(Object value) {
        return Response.ok(LexIntJsonCodec.write(value),
                MediaType.APPLICATION_JSON).build();
    }

    protected Response json(Object value, String etag) {
        Response.ResponseBuilder response = Response.ok(
                LexIntJsonCodec.write(value), MediaType.APPLICATION_JSON);
        if (etag != null) { response.header("ETag", etag); }
        return response.build();
    }

    protected Response created(Object value, String location, String etag) {
        Response.ResponseBuilder response = Response.status(201)
                .type(MediaType.APPLICATION_JSON)
                .entity(LexIntJsonCodec.write(value));
        if (location != null) { response.header("Location", location); }
        if (etag != null) { response.header("ETag", etag); }
        return response.build();
    }

    protected int integer(String value, int fallback) {
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException ex) {
            throw LexIntException.invalid("Pagination requires integers");
        }
    }

    protected Boolean bool(String value) {
        if (value == null) { return null; }
        if ("true".equals(value)) { return Boolean.TRUE; }
        if ("false".equals(value)) { return Boolean.FALSE; }
        throw LexIntException.invalid("Boolean query values must be true or false");
    }

    protected String requestId() {
        String id = MDC.get("requestId");
        return id == null || id.trim().isEmpty()
                ? UUID.randomUUID().toString() : id;
    }

    private Response error(LexIntException ex) {
        LexIntError error = new LexIntError(ex.code, ex.getMessage(),
                requestId(), ex.details);
        return Response.status(ex.status).type(MediaType.APPLICATION_JSON)
                .entity(LexIntJsonCodec.write(error)).build();
    }
}
