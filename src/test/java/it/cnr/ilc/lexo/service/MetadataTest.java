package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.service.data.metadata.MetadataDeleteRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.Path;
import org.junit.jupiter.api.Test;

class MetadataTest {

    @Test
    void exposesDocumentedCommonMetadataCrud() throws Exception {
        assertThat(Metadata.class.getAnnotation(Path.class).value())
                .isEqualTo("metadata");
        assertThat(Metadata.class.getAnnotation(Api.class).value())
                .isEqualTo("Metadata");
        Method read = Metadata.class.getMethod("read", String.class,
                String.class, String.class, String.class, String.class);
        assertDocumented(read, GET.class);
        ApiParam entityType = apiParam(read.getParameterAnnotations()[1]);
        assertThat(entityType.allowableValues())
                .contains("lexicalSense")
                .contains("form");
        assertDocumented(Metadata.class.getMethod("patch", String.class,
                MetadataPatchRequest.class), PATCH.class);
        assertDocumented(Metadata.class.getMethod("delete", String.class,
                MetadataDeleteRequest.class), DELETE.class);
    }

    private void assertDocumented(Method method,
                                  Class<? extends Annotation> httpMethod) {
        assertThat(method.getAnnotation(httpMethod)).isNotNull();
        assertThat(method.getAnnotation(ApiOperation.class)).isNotNull();
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            boolean found = false;
            for (Annotation annotation : annotations) {
                found |= annotation instanceof ApiParam;
            }
            assertThat(found).isTrue();
        }
    }

    private ApiParam apiParam(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof ApiParam) {
                return (ApiParam) annotation;
            }
        }
        throw new AssertionError("missing ApiParam");
    }
}
