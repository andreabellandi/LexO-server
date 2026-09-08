package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.manager.AttestationManager;
import it.cnr.ilc.lexo.manager.ManagerException;
import it.cnr.ilc.lexo.manager.WebAnnotationExportException;
import it.cnr.ilc.lexo.service.data.attestation.output.WebAnnotationDocument;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class AttestationsExportTest {
    @Test void documentsRoutingAndAllParameters() throws Exception {
        Method method = Attestations.class.getMethod("exportWebAnnotations", String.class, List.class, String.class);
        assertThat(method.getAnnotation(GET.class)).isNotNull();
        assertThat(method.getAnnotation(Path.class).value()).isEqualTo("export/web-annotation");
        assertThat(method.getAnnotation(ApiOperation.class).response()).isEqualTo(WebAnnotationDocument.class);
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            assertThat(Arrays.stream(annotations).anyMatch(a -> a instanceof ApiParam)).isTrue();
        }
    }

    @Test void validatesBooleanBeforeManagerAndSerializesOnlyCompletedDocuments() throws Exception {
        Stub manager = new Stub();
        Attestations service = service(manager);
        for (String bad : Arrays.asList("", "yes", "1", " true ")) {
            try (Response response = service.exportWebAnnotations(null, null, bad)) {
                assertThat(response.getStatus()).isEqualTo(400);
                assertThat(response.getEntity().toString()).startsWith("WA_INVALID_BOOLEAN:");
            }
        }
        assertThat(manager.calls).isZero();
        try (Response response = service.exportWebAnnotations(null, Arrays.asList("graph-a", "graph-b"), "TRUE")) {
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getMediaType().toString()).isEqualTo("application/ld+json;charset=UTF-8");
            assertThat(response.getHeaderString("Content-Disposition")).contains("attestations-web-annotation.jsonld");
            assertThat(new ObjectMapper().readTree((byte[]) response.getEntity()).path("@graph").isArray()).isTrue();
        }
        assertThat(manager.contexts).containsExactly("graph-a", "graph-b");
        assertThat(manager.metadata).isTrue();
        try (Response ignored = service.exportWebAnnotations(null, null, null)) {
            assertThat(manager.metadata).isFalse();
        }
    }

    @Test void reportsConversionAndRepositoryErrorsWithoutSuccessPayload() {
        Stub manager = new Stub();
        Attestations service = service(manager);
        manager.failure = new WebAnnotationExportException(422, "WA_MISSING_BODY", "attestation=x graph=y");
        try (Response response = service.exportWebAnnotations(null, null, null)) {
            assertThat(response.getStatus()).isEqualTo(422);
            assertThat(response.getMediaType().toString()).isEqualTo("text/plain");
            assertThat(response.getEntity().toString()).startsWith("WA_MISSING_BODY:");
            assertThat(response.getHeaderString("Content-Disposition")).isNull();
        }
        manager.failure = new ManagerException("repository failed");
        try (Response response = service.exportWebAnnotations(null, null, null)) {
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getEntity().toString()).startsWith("WA_EXPORT_FAILED:");
        }
    }

    @Test void checksAuthorizationBeforeReadingRepository() {
        Stub manager = new Stub();
        Attestations service = new Attestations(manager) {
            @Override protected void checkKey(String key) throws AuthorizationException {
                throw new AuthorizationException("denied");
            }
        };
        try (Response response = service.exportWebAnnotations(null, null, null)) {
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(manager.calls).isZero();
        }
    }

    private Attestations service(Stub manager) {
        return new Attestations(manager) {
            @Override protected void checkKey(String key) { }
        };
    }

    private static class Stub extends AttestationManager {
        int calls;
        boolean metadata;
        List<String> contexts;
        ManagerException failure;
        @Override public WebAnnotationDocument exportWebAnnotations(List<String> contexts, boolean metadata) throws ManagerException {
            calls++;
            this.contexts = contexts;
            this.metadata = metadata;
            if (failure != null) throw failure;
            WebAnnotationDocument document = new WebAnnotationDocument();
            document.context.add("http://www.w3.org/ns/anno.jsonld");
            return document;
        }
    }
}
