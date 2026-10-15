package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;
import javax.ws.rs.client.*;
import javax.ws.rs.core.Response;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.*;
import org.eclipse.rdf4j.repository.http.HTTPRepository;
import org.eclipse.rdf4j.rio.*;
import org.glassfish.jersey.client.ClientProperties;
import org.junit.jupiter.api.*;

/** Opt-in Tomcat + GraphDB acceptance test. Never targets development by default. */
class CppRunsIT {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ValueFactory vf=SimpleValueFactory.getInstance();
    @Test void completeRestLifecycleAndFlattenedExportOnDedicatedRepository() throws Exception {
        String base=System.getProperty("lexo.test.cpp.baseUrl","");
        String repositoryUrl=System.getProperty("lexo.test.cpp.lexicalRepositoryUrl","");
        Assumptions.assumeTrue(Boolean.getBoolean("lexo.test.cpp.dedicated") && !base.isEmpty() && !repositoryUrl.isEmpty(),
                "Requires explicit opt-in, a dedicated deployment and a dedicated lexical repository");
        HTTPRepository lexical=new HTTPRepository(repositoryUrl);
        Client client=ClientBuilder.newClient().property(ClientProperties.SUPPRESS_HTTP_COMPLIANCE_VALIDATION,true)
                .property(ClientProperties.CONNECT_TIMEOUT,10000).property(ClientProperties.READ_TIMEOUT,30000);
        String token=UUID.randomUUID().toString();
        IRI a=vf.createIRI("urn:cpp-it:"+token+":a"),b=vf.createIRI("urn:cpp-it:"+token+":b");
        IRI graph=vf.createIRI(System.getProperty("lexo.test.cpp.lexicalGraph","https://lexo.ilc.cnr.it/graphs/lexical/lexica"));
        String runId=null;
        lexical.init();
        try {
            try(RepositoryConnection c=lexical.getConnection()) {
                long defaultSize=c.size((Resource)null);
                for(IRI col:Arrays.asList(a,b)) { c.add(col,RDF.TYPE,vf.createIRI("http://www.w3.org/ns/lemon/frac#Collocation"),graph); }
                ObjectNode request=JSON.createObjectNode();request.putObject("search").put("node","crime");
                request.putObject("cppOptions").put("ordered",false);
                request.putObject("corpusSnapshot").putArray("memberIRIs").add("urn:cpp-it:declared-snapshot:"+token);
                request.putObject("index").put("id","cpp-it-lucene");request.putObject("software").put("name","LexO-test");
                request.putArray("collocations").add(a.stringValue());
                String runIri;
                try(Response r=auth(client.target(base).path("cpp/runs")).header("Idempotency-Key",token).post(Entity.json(request.toString()))) {
                    assertThat(r.getStatus()).isEqualTo(201);JsonNode d=JSON.readTree(r.readEntity(String.class));
                    runId=d.path("id").asText();runIri=d.path("iri").asText();
                    assertThat(r.getLocation().toString()).endsWith("/cpp/runs/"+runId);
                }
                try(Response r=auth(client.target(base).path("cpp/runs")).header("Idempotency-Key",token).post(Entity.json(request.toString()))) {
                    assertThat(r.getStatus()).isEqualTo(200);assertThat(JSON.readTree(r.readEntity(String.class)).path("id").asText()).isEqualTo(runId);
                }
                try(Response r=auth(client.target(base).path("cpp/runs/"+runId)).get()) { assertThat(r.getStatus()).isEqualTo(200); }
                ObjectNode patch=JSON.createObjectNode();patch.putArray("addCollocations").add(b.stringValue());
                // HttpURLConnection does not support PATCH; use the existing Apache HTTP client.
                org.apache.http.client.methods.HttpPatch update=new org.apache.http.client.methods.HttpPatch(base+"/cpp/runs/"+runId);
                update.setEntity(new org.apache.http.entity.StringEntity(patch.toString(),org.apache.http.entity.ContentType.APPLICATION_JSON));
                String authorization=System.getProperty("lexo.test.cpp.authorization","");
                if(!authorization.isEmpty()) { update.setHeader("Authorization",authorization); }
                try(org.apache.http.impl.client.CloseableHttpClient http=org.apache.http.impl.client.HttpClients.createDefault();
                        org.apache.http.client.methods.CloseableHttpResponse r=http.execute(update)) {
                    assertThat(r.getStatusLine().getStatusCode()).isEqualTo(200);
                    assertThat(JSON.readTree(org.apache.http.util.EntityUtils.toString(r.getEntity())).path("collocationCount").asInt()).isEqualTo(2);
                }
                try(Response r=auth(client.target(base).path("cpp/runs").queryParam("collocationIRI",a.stringValue())).get()) {
                    assertThat(r.getStatus()).isEqualTo(200);assertThat(JSON.readTree(r.readEntity(String.class)).path("total").asInt()).isEqualTo(1);
                }
                try(Response r=auth(client.target(base).path("cpp/runs/export").queryParam("run",runIri).queryParam("includeCollocations",true)).get()) {
                    assertThat(r.getStatus()).isEqualTo(200);
                    Model m=Rio.parse(new StringReader(r.readEntity(String.class)),"urn:export:",RDFFormat.TURTLE);
                    assertThat(m.contains(a,RDF.TYPE,vf.createIRI("http://www.w3.org/ns/lemon/frac#Collocation"))).isTrue();
                    assertThat(m.contexts()).containsOnly((Resource)null);
                }
                try(Response r=auth(client.target(base).path("cpp/runs/"+runId)).delete()) { assertThat(r.getStatus()).isEqualTo(204); }
                runId=null;
                for(IRI col:Arrays.asList(a,b)) { assertThat(c.hasStatement(col,RDF.TYPE,vf.createIRI("http://www.w3.org/ns/lemon/frac#Collocation"),false,graph)).isTrue(); }
                assertThat(c.size((Resource)null)).isEqualTo(defaultSize);
            }
        } finally {
            try {
                if(runId!=null) {
                    try(Response r=auth(client.target(base).path("cpp/runs/"+runId)).delete()) { assertThat(r.getStatus()).isIn(204,404); }
                }
            } finally {
                try(RepositoryConnection c=lexical.getConnection()) { for(IRI col:Arrays.asList(a,b)) { c.remove(col,null,null,graph); } }
                finally { try { client.close(); } finally { lexical.shutDown(); } }
            }
        }
    }
    private Invocation.Builder auth(WebTarget target) {
        Invocation.Builder request=target.request();String key=System.getProperty("lexo.test.cpp.authorization","");
        return key.isEmpty()?request:request.header("Authorization",key);
    }
}
