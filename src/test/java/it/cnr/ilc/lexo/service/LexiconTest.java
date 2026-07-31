package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryStatusChangeRequest;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PATCH;
import javax.ws.rs.Path;
import org.junit.jupiter.api.Test;

class LexiconTest {

    @Test
    void exposesTheNewLexicalServiceRoot() {
        Path path = Lexicon.class.getAnnotation(Path.class);
        Api api = Lexicon.class.getAnnotation(Api.class);

        assertThat(path.value()).isEqualTo("lexica");
        assertThat(api.value()).isEqualTo("Lexica");
    }

    @Test
    void normalizesEmptyAuthorAfterServiceResolution() {
        Lexicon service = new Lexicon();

        assertThat(service.resolveAuthor(null)).isEqualTo("anonymous");
        assertThat(service.resolveAuthor("")).isEqualTo("anonymous");
        assertThat(service.resolveAuthor("   ")).isEqualTo("anonymous");
        assertThat(service.resolveAuthor("editor")).isEqualTo("editor");
    }

    @Test
    void exposesDocumentedPostEntryEndpointAndEveryParameter() throws Exception {
        Method method = Lexicon.class.getMethod("createEntry", String.class,
                String.class, LexicalEntryCreationRequest.class);

        assertThat(method.getAnnotation(POST.class)).isNotNull();
        assertThat(method.getAnnotation(Path.class).value()).isEqualTo("entry");
        assertThat(method.getAnnotation(ApiOperation.class).value())
                .isEqualTo("Lexical entry creation");
        Annotation[][] annotations = method.getParameterAnnotations();
        for (Annotation[] parameterAnnotations : annotations) {
            assertThat(hasApiParam(parameterAnnotations)).isTrue();
        }
    }

    @Test
    void exposesDocumentedPostLexicalConceptEndpointAndEveryParameter()
            throws Exception {
        Method method = Lexicon.class.getMethod("createLexicalConcept",
                String.class, String.class, LexicalConceptCreationRequest.class);

        assertThat(method.getAnnotation(POST.class)).isNotNull();
        assertThat(method.getAnnotation(Path.class).value())
                .isEqualTo("lexicalConcept");
        assertThat(method.getAnnotation(ApiOperation.class).value())
                .isEqualTo("Lexical concept creation");
        Annotation[][] annotations = method.getParameterAnnotations();
        for (Annotation[] parameterAnnotations : annotations) {
            assertThat(hasApiParam(parameterAnnotations)).isTrue();
        }
    }

    @Test
    void exposesDocumentedAdvancedEntryListAndEveryParameter() throws Exception {
        Method method = Lexicon.class.getMethod("listEntries", String.class,
                String.class, String.class, String.class, String.class,
                String.class, String.class, String.class, String.class,
                String.class);

        assertThat(method.getAnnotation(GET.class)).isNotNull();
        assertThat(method.getAnnotation(Path.class).value())
                .isEqualTo("{language}/entries");
        assertThat(method.getAnnotation(ApiOperation.class).value())
                .isEqualTo("Advanced lexical entry list");
        Annotation[][] annotations = method.getParameterAnnotations();
        for (Annotation[] parameterAnnotations : annotations) {
            assertThat(hasApiParam(parameterAnnotations)).isTrue();
        }
    }

    @Test
    void exposesDocumentedPatchEntryStatusesEndpointAndEveryParameter()
            throws Exception {
        Method method = Lexicon.class.getMethod("changeEntryStatuses", String.class,
                String.class, LexicalEntryStatusChangeRequest.class);

        assertThat(method.getAnnotation(PATCH.class)).isNotNull();
        assertThat(method.getAnnotation(Path.class).value())
                .isEqualTo("entries/status");
        assertThat(method.getAnnotation(ApiOperation.class).value())
                .isEqualTo("Lexical entry status change");
        Annotation[][] annotations = method.getParameterAnnotations();
        for (Annotation[] parameterAnnotations : annotations) {
            assertThat(hasApiParam(parameterAnnotations)).isTrue();
        }
    }

    private boolean hasApiParam(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            if (annotation instanceof ApiParam) {
                return true;
            }
        }
        return false;
    }
}
