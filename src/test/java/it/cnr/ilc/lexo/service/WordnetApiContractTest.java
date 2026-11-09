package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SynsetPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.EntryPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.RelationPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.WordnetPatch;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

class WordnetApiContractTest {

    @Test
    void normalizesMissingAndBlankAuthors() {
        Wordnet service = new Wordnet();
        assertThat(service.resolveAuthor(null)).isEqualTo("anonymous");
        assertThat(service.resolveAuthor("")).isEqualTo("anonymous");
        assertThat(service.resolveAuthor("   ")).isEqualTo("anonymous");
        assertThat(service.resolveAuthor("editor")).isEqualTo("editor");
    }

    @Test
    void deserializesFlatPresenceAwareNestedChildPatches() throws Exception {
        SynsetPatch patch = new ObjectMapper().readValue(
                "{\"definitions\":{\"update\":[{\"id\":\"child\","
                + "\"text\":\"changed\",\"notes\":[],\"metadata\":{"
                + "\"addValues\":[{\"property\":\"https://example.org/p\","
                + "\"values\":[{\"type\":\"literal\",\"value\":\"v\"}]}]}}]}}",
                SynsetPatch.class);

        assertThat(patch.definitions.update).hasSize(1);
        assertThat(patch.definitions.update.get(0).id).isEqualTo("child");
        assertThat(patch.definitions.update.get(0).hasText()).isTrue();
        assertThat(patch.definitions.update.get(0).getText()).isEqualTo("changed");
        assertThat(patch.definitions.update.get(0).hasNotes()).isTrue();
        assertThat(patch.definitions.update.get(0).metadata.addValues).hasSize(1);
    }

    @Test
    void tracksExplicitNullForImmutableAndReservedPatchFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WordnetPatch root = mapper.readValue(
                "{\"wordnetId\":null,\"language\":null}",
                WordnetPatch.class);
        EntryPatch entry = mapper.readValue(
                "{\"workflowStatus\":null,\"workflowTransition\":null,"
                + "\"lexo:status\":null,\"status\":null}", EntryPatch.class);
        FormPatch form = mapper.readValue(
                "{\"entryId\":null,\"kind\":null}", FormPatch.class);
        RelationPatch relation = mapper.readValue("{\"scope\":null}",
                RelationPatch.class);

        assertThat(root.hasWordnetId()).isTrue();
        assertThat(root.hasLanguage()).isTrue();
        assertThat(entry.hasWorkflowStatus()).isTrue();
        assertThat(entry.hasWorkflowTransition()).isTrue();
        assertThat(entry.hasLexoStatus()).isTrue();
        assertThat(entry.hasRawStatus()).isTrue();
        assertThat(form.hasEntryId()).isTrue();
        assertThat(form.hasKind()).isTrue();
        assertThat(relation.hasScope()).isTrue();
    }

    @Test
    void exposesOnlyTheCataloguedWordnetRoutesWithSwaggerAndAuthor() {
        assertThat(Wordnet.class.getAnnotation(Path.class).value())
                .isEqualTo("wordnet");
        assertThat(Wordnet.class.getAnnotation(Api.class).value())
                .isEqualTo("Wordnet");

        List<String> routes = new ArrayList<String>();
        for (Method method : Wordnet.class.getDeclaredMethods()) {
            String verb = httpVerb(method);
            if (verb == null) {
                continue;
            }
            Path path = method.getAnnotation(Path.class);
            routes.add(verb + " " + (path == null ? "" : path.value()));
            assertThat(method.getAnnotation(ApiOperation.class))
                    .as(method.getName()).isNotNull();
            assertEveryParameterHasApiParam(method);
            assertThat(hasAuthorParameter(method)).as(method.getName()).isTrue();
        }

        assertThat(routes).containsExactlyInAnyOrderElementsOf(Arrays.asList(
                "GET ", "POST ", "GET schema",
                "GET {wordnetId}", "PATCH {wordnetId}", "DELETE {wordnetId}",
                "GET {wordnetId}/entries", "POST {wordnetId}/entries",
                "GET {wordnetId}/entries/{id}", "PATCH {wordnetId}/entries/{id}",
                "DELETE {wordnetId}/entries/{id}",
                "GET {wordnetId}/entries/{id}/status",
                "PATCH {wordnetId}/entries/status",
                "GET {wordnetId}/forms", "POST {wordnetId}/forms",
                "GET {wordnetId}/forms/{id}", "PATCH {wordnetId}/forms/{id}",
                "DELETE {wordnetId}/forms/{id}",
                "GET {wordnetId}/senses", "POST {wordnetId}/senses",
                "GET {wordnetId}/senses/{id}", "PATCH {wordnetId}/senses/{id}",
                "DELETE {wordnetId}/senses/{id}",
                "GET {wordnetId}/synsets", "POST {wordnetId}/synsets",
                "GET {wordnetId}/synsets/{id}", "PATCH {wordnetId}/synsets/{id}",
                "DELETE {wordnetId}/synsets/{id}",
                "GET {wordnetId}/relations", "POST {wordnetId}/relations",
                "GET {wordnetId}/relations/{id}", "PATCH {wordnetId}/relations/{id}",
                "DELETE {wordnetId}/relations/{id}"));
        assertThat(routes).noneMatch(route -> route.contains("import")
                || route.contains("export") || route.contains("validate"));
    }

    private static String httpVerb(Method method) {
        if (method.isAnnotationPresent(GET.class)) return "GET";
        if (method.isAnnotationPresent(POST.class)) return "POST";
        if (method.isAnnotationPresent(PATCH.class)) return "PATCH";
        if (method.isAnnotationPresent(DELETE.class)) return "DELETE";
        return null;
    }

    private static void assertEveryParameterHasApiParam(Method method) {
        Annotation[][] annotations = method.getParameterAnnotations();
        for (int index = 0; index < annotations.length; index++) {
            assertThat(Arrays.stream(annotations[index])
                    .anyMatch(annotation -> annotation instanceof ApiParam))
                    .as(method.getName() + " parameter " + index).isTrue();
        }
    }

    private static boolean hasAuthorParameter(Method method) {
        for (Annotation[] annotations : method.getParameterAnnotations()) {
            for (Annotation annotation : annotations) {
                if (annotation instanceof QueryParam
                        && "author".equals(((QueryParam) annotation).value())) {
                    return true;
                }
            }
        }
        return false;
    }
}
