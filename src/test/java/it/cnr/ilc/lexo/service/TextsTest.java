package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.service.data.text.input.TextBulkDeletionInput;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import org.junit.jupiter.api.Test;

class TextsTest {

    @Test
    void exposesDocumentedAsynchronousBulkDeletionEndpoints() throws Exception {
        Method delete = Texts.class.getMethod("deleteBulk", String.class,
                TextBulkDeletionInput.class);
        assertThat(delete.getAnnotation(DELETE.class)).isNotNull();
        assertThat(delete.getAnnotation(Path.class).value()).isEqualTo("/bulk");
        assertThat(delete.getAnnotation(ApiOperation.class).value())
                .isEqualTo("Asynchronous bulk text deletion");
        assertEveryParameterDocumented(delete);

        Method status = Texts.class.getMethod("deletionStatus", String.class,
                String.class);
        assertThat(status.getAnnotation(GET.class)).isNotNull();
        assertThat(status.getAnnotation(Path.class).value())
                .isEqualTo("/deletions/{bulkId}/status");
        assertThat(status.getAnnotation(ApiOperation.class).value())
                .isEqualTo("Bulk text deletion status");
        assertEveryParameterDocumented(status);
    }

    private void assertEveryParameterDocumented(Method method) {
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            boolean documented = false;
            for (Annotation annotation : annotations) {
                documented |= annotation instanceof ApiParam;
            }
            assertThat(documented).isTrue();
        }
    }
}
