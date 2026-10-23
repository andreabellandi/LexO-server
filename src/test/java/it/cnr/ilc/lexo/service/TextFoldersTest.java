package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Swagger;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import org.junit.jupiter.api.Test;

class TextFoldersTest {

    @Test
    void exposesEveryDocumentedRouteUnderTheTextOrganizationTag() {
        Map<String, String> routes = new LinkedHashMap<String, String>();
        routes.put("create", "");
        routes.put("tree", "");
        routes.put("get", "/{folderId}");
        routes.put("update", "/{folderId}");
        routes.put("move", "/{folderId}/move");
        routes.put("delete", "/{folderId}");
        routes.put("assign", "/{folderId}/texts/{fileId}");
        routes.put("remove", "/{folderId}/texts/{fileId}");
        routes.put("bulkMove", "/move-texts");
        routes.put("unfiled", "/unfiled");

        assertThat(TextFolders.class.getAnnotation(Api.class).value())
                .isEqualTo("Text Organization");
        Swagger swagger = new Reader(new Swagger()).read(TextFolders.class);
        for (Map.Entry<String, String> route : routes.entrySet()) {
            Method method = method(route.getKey());
            javax.ws.rs.Path path = method.getAnnotation(javax.ws.rs.Path.class);
            assertThat(path == null ? "" : path.value()).isEqualTo(route.getValue());
            assertThat(method.getAnnotation(ApiOperation.class)).isNotNull();
            assertThat(method.getAnnotation(ApiOperation.class).notes()).isNotBlank();
            assertEveryParameterDocumented(method);
            assertThat(swagger.getPath("/text-folders" + route.getValue()))
                    .as(route.getKey()).isNotNull();
        }
    }

    @Test
    void usesTheExpectedHttpMethods() {
        assertThat(method("create").getAnnotation(POST.class)).isNotNull();
        assertThat(method("tree").getAnnotation(GET.class)).isNotNull();
        assertThat(method("get").getAnnotation(GET.class)).isNotNull();
        assertThat(method("update").getAnnotation(PUT.class)).isNotNull();
        assertThat(method("move").getAnnotation(POST.class)).isNotNull();
        assertThat(method("delete").getAnnotation(DELETE.class)).isNotNull();
        assertThat(method("assign").getAnnotation(PUT.class)).isNotNull();
        assertThat(method("remove").getAnnotation(DELETE.class)).isNotNull();
        assertThat(method("bulkMove").getAnnotation(POST.class)).isNotNull();
        assertThat(method("unfiled").getAnnotation(GET.class)).isNotNull();
    }

    private static Method method(String name) {
        for (Method method : TextFolders.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        throw new AssertionError("Missing endpoint " + name);
    }

    private static void assertEveryParameterDocumented(Method method) {
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            assertThat(Arrays.asList(annotations).stream()
                    .anyMatch(annotation -> annotation instanceof ApiParam)).isTrue();
        }
    }
}
