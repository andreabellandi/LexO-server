package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import io.swagger.annotations.*;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Swagger;
import it.cnr.ilc.lexo.manager.CppRunManager;
import it.cnr.ilc.lexo.manager.cpp.*;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import javax.ws.rs.Path;
import javax.ws.rs.core.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.*;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.*;

class CppRunsTest {
    private SailRepository memory;
    private CppRuns service;
    private final ObjectMapper json=new ObjectMapper();
    private final String raw="{\"search\":{\"node\":\"crime\"},\"cppOptions\":{\"ordered\":false},\"corpusSnapshot\":{\"memberIRIs\":[\"urn:corpus\"]},\"index\":{\"id\":\"lucene\"},\"software\":{\"name\":\"LexO\"},\"collocations\":[\"urn:collocation\"]}";
    @BeforeEach void setup() {
        memory=new SailRepository(new MemoryStore());memory.init();
        try(RepositoryConnection c=memory.getConnection()) {
            c.add(SimpleValueFactory.getInstance().createIRI("urn:collocation"),RDF.TYPE,
                    CppVocabulary.iri(CppVocabulary.FRAC+"Collocation"),CppVocabulary.iri("https://lexo.ilc.cnr.it/graphs/lexical/lexica"));
        }
        service=new CppRuns(new CppRunManager(new CppRunRepository(memory)));
    }
    @AfterEach void close() { memory.shutDown(); }
    @Test void exposesExactlySixOperationsWithDocumentedParametersAndDtoSchemas() {
        Swagger swagger=new Reader(new Swagger()).read(CppRuns.class);
        assertThat(swagger.getPaths()).containsOnlyKeys("/cpp/runs","/cpp/runs/{id}","/cpp/runs/export");
        assertThat(swagger.getPaths().values().stream().mapToInt(p->p.getOperations().size()).sum()).isEqualTo(6);
        assertThat(swagger.getPath("/cpp/runs/export").getGet()).isNotNull();
        assertThat(swagger.getDefinitions()).containsKeys("CppRunCreateRequest","CppRunPatchRequest","CppRunDetails","CppRunPage");
        for(Method m:CppRuns.class.getDeclaredMethods()) {
            if(m.getAnnotation(Path.class)==null) { continue; }
            assertThat(m.getAnnotation(ApiOperation.class)).isNotNull();
            for(Annotation[] annotations:m.getParameterAnnotations()) {
                assertThat(Arrays.stream(annotations).anyMatch(a->a instanceof ApiParam)).isTrue();
            }
        }
    }
    @Test void freeModeCreateReplayReadPatchListExportAndDelete() throws Exception {
        String id,iri;
        try(Response r=service.create(null,"retry",body(raw))) {
            assertThat(r.getStatus()).isEqualTo(201);
            JsonNode d=json.readTree(r.getEntity().toString());id=d.path("id").asText();iri=d.path("iri").asText();
            assertThat(r.getHeaderString("Location")).endsWith("runs/"+id);
            assertThat(d.path("author").asText()).isEqualTo("anonymous");
        }
        try(Response r=service.create(null,"retry",body(raw))) { assertThat(r.getStatus()).isEqualTo(200); }
        try(Response r=service.get(null,id,null)) { assertThat(r.getStatus()).isEqualTo(200); }
        try(Response r=service.patch(null,id,"editor",body("{\"addCollocations\":[\"urn:collocation\"]}"))) { assertThat(r.getStatus()).isEqualTo(200); }
        try(Response r=service.list(null,"crime",null,null,null,null,null,null,null,null)) {
            assertThat(r.getStatus()).isEqualTo(200);assertThat(json.readTree(r.getEntity().toString()).path("total").asInt()).isEqualTo(1);
        }
        try(Response r=service.export(null,Arrays.asList(iri),"true",null)) {
            assertThat(r.getStatus()).isEqualTo(200);assertThat(r.getHeaderString("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(r.getHeaderString("Content-Disposition")).isEqualTo("attachment; filename=\"cpp-runs.ttl\"");
            ByteArrayOutputStream out=new ByteArrayOutputStream();((StreamingOutput)r.getEntity()).write(out);
            assertThat(Rio.parse(new ByteArrayInputStream(out.toByteArray()),"urn:export:",RDFFormat.TURTLE)).isNotEmpty();
        }
        try(Response r=service.delete(null,id,null)) { assertThat(r.getStatus()).isEqualTo(204);assertThat(r.hasEntity()).isFalse(); }
        try(Response r=service.get(null,id,null)) { assertError(r,404,"CPP_RUN_NOT_FOUND"); }
    }
    @Test void returnsStableJsonErrorsBeforeDownloadAndHonorsAuthorization() throws Exception {
        try(Response r=service.create(null,null,body("{\"unexpected\":1}"))) { assertError(r,400,"CPP_UNKNOWN_FIELD"); }
        try(Response r=service.export(null,null,"yes",null)) { assertError(r,400,"CPP_INVALID_PARAMETER");assertThat(r.getHeaderString("Content-Disposition")).isNull(); }
        try(Response r=service.get(null,"export",null)) { assertError(r,400,"CPP_INVALID_PARAMETER"); }
        try(Response r=service.list(null,null,null,null,null,null,null,null,"x",null)) { assertError(r,400,"CPP_INVALID_PARAMETER"); }
        CppRuns secured=new CppRuns(new CppRunManager(new CppRunRepository(memory))) {
            @Override protected void checkKey(String key) throws AuthorizationException { throw new AuthorizationException("secret"); }
        };
        try(Response r=secured.create(null,null,body(raw))) { assertError(r,401,"CPP_UNAUTHORIZED");assertThat(r.getEntity().toString()).doesNotContain("secret"); }
        try(RepositoryConnection c=memory.getConnection()) { assertThat(c.size(new CppRunRepository(memory).graph)).isZero(); }
    }
    private InputStream body(String raw) { return new ByteArrayInputStream(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private void assertError(Response r,int status,String code) throws Exception {
        assertThat(r.getStatus()).isEqualTo(status);assertThat(r.getMediaType().toString()).isEqualTo("application/json");
        assertThat(json.readTree(r.getEntity().toString()).path("code").asText()).isEqualTo(code);
    }
}
