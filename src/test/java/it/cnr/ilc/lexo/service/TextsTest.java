package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Operation;
import io.swagger.models.Swagger;
import it.cnr.ilc.lexo.service.data.text.input.TextBulkDeletionInput;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Test
    void documentsEveryNewLuceneCorpusEndpointAndParameter() {
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("fullTextSearch", "/search/fulltext");
        expected.put("resizeKwic", "/search/kwic/resize");
        expected.put("cooccurrences", "/search/cooccurrences");
        expected.put("frequency", "/search/frequency");
        expected.put("extractCollocates", "/collocations/extract");
        expected.put("persistCollocation", "/collocations/persist");
        expected.put("indexStatus", "/index/status");
        expected.put("verifyIndex", "/index/verify");
        expected.put("reindex", "/index/reindex/{fileId}");
        expected.put("rebuildIndex", "/index/rebuild");
        Swagger swagger = new Reader(new Swagger()).read(Texts.class);

        for (Map.Entry<String, String> endpoint : expected.entrySet()) {
            Method method = method(endpoint.getKey());
            assertThat(method.getAnnotation(Path.class).value())
                    .isEqualTo(endpoint.getValue());
            assertThat(method.getAnnotation(ApiOperation.class))
                    .as(endpoint.getKey()).isNotNull();
            assertThat(method.getAnnotation(ApiOperation.class).notes())
                    .as(endpoint.getKey()).isNotBlank();
            assertThat(method.getAnnotation(ApiOperation.class).tags())
                    .as(endpoint.getKey() + " Swagger group")
                    .containsExactly("Text Search");
            Operation operation = method.getAnnotation(GET.class) == null
                    ? swagger.getPath("/texts" + endpoint.getValue()).getPost()
                    : swagger.getPath("/texts" + endpoint.getValue()).getGet();
            assertThat(operation.getTags())
                    .as(endpoint.getKey() + " generated Swagger tags")
                    .containsExactly("Text Search");
            assertEveryParameterDocumented(method);
        }

        assertThat(swagger.getPath("/texts").getGet().getTags())
                .as("existing text catalog Swagger group")
                .containsExactly("Text Corpus NIF");
    }

    private static Method method(String name) {
        for (Method method : Texts.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("Missing endpoint method " + name);
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
