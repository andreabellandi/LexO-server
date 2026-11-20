package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Swagger;
import it.cnr.ilc.lexo.LexOProperties;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class LexIntApiContractTest {
    @Test
    void blankKeycloakUrlKeepsBearerAuthenticationOptional() throws Exception {
        String previous = LexOProperties.getProperty("keycloack.url");
        try {
            LexOProperties.setProperty("keycloack.url", "   ");
            new AuthenticationProbe().check(null);
        } finally {
            if (previous == null) {
                LexOProperties.clearPropertyOverride("keycloack.url");
            } else {
                LexOProperties.setProperty("keycloack.url", previous);
            }
        }
    }

    @Test
    void configuredKeycloakRejectsMissingBearerBeforeManagerAccess() {
        String previous = LexOProperties.getProperty("keycloack.url");
        try {
            LexOProperties.setProperty("keycloack.url",
                    "https://identity.invalid/realm");
            Response response = new LexInt(null).getClaim(null,
                    "00000000-0000-4000-8000-000000000000", "ignored");
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getEntity().toString())
                    .contains("LEXINT_AUTH_REQUIRED");
        } finally {
            if (previous == null) {
                LexOProperties.clearPropertyOverride("keycloack.url");
            } else {
                LexOProperties.setProperty("keycloack.url", previous);
            }
        }
    }

    @Test
    void exposesManagementVersionPublicationAndAuditRoutesInSwagger() {
        Swagger swagger = new Swagger();
        Reader reader = new Reader(swagger);
        reader.read(LexInt.class);
        reader.read(LexIntVersions.class);
        reader.read(LexIntPublications.class);

        assertThat(swagger.getPaths()).containsOnlyKeys(
                "/lexint/claims", "/lexint/claims/{id}",
                "/lexint/claims/{id}/targets", "/lexint/targets/{id}",
                "/lexint/decisions", "/lexint/decisions/{id}",
                "/lexint/decisions/{id}/finalize",
                "/lexint/versions", "/lexint/versions/baseline",
                "/lexint/versions/drafts", "/lexint/versions/{id}",
                "/lexint/versions/{id}/snapshot",
                "/lexint/publications", "/lexint/publications/preview",
                "/lexint/publications/{id}",
                "/lexint/publications/{id}/audit",
                "/lexint/publications/{id}/verify");
        assertThat(swagger.getPath("/lexint/publications/preview").getPost())
                .isNotNull();
        assertThat(swagger.getPath("/lexint/publications/{id}/verify")
                .getPost()).isNotNull();
        assertThat(swagger.getDefinitions()).containsKeys(
                "ClaimCreateRequest", "ClaimDetails", "DecisionCreateRequest",
                "DecisionDetails", "PublicationRequest", "PublicationDetails",
                "VersionBaselineRequest", "VersionDetails",
                "AuditReportDetails");
        assertDocumented(LexInt.class);
        assertDocumented(LexIntVersions.class);
        assertDocumented(LexIntPublications.class);
    }

    private void assertDocumented(Class<?> resource) {
        for (Method method : resource.getDeclaredMethods()) {
            if (!isEndpoint(method)) { continue; }
            assertThat(method.getAnnotation(ApiOperation.class))
                    .as(resource.getSimpleName() + "." + method.getName())
                    .isNotNull();
            for (Annotation[] parameter : method.getParameterAnnotations()) {
                assertThat(Arrays.stream(parameter)
                        .anyMatch(annotation -> annotation instanceof ApiParam))
                        .as(resource.getSimpleName() + "." + method.getName()
                                + " parameter")
                        .isTrue();
            }
        }
    }

    private boolean isEndpoint(Method method) {
        return method.getAnnotation(GET.class) != null
                || method.getAnnotation(POST.class) != null
                || method.getAnnotation(PATCH.class) != null
                || method.getAnnotation(DELETE.class) != null;
    }

    private static final class AuthenticationProbe extends Service {
        void check(String value) throws AuthorizationException,
                ServiceException {
            checkKey(value);
        }
    }
}
