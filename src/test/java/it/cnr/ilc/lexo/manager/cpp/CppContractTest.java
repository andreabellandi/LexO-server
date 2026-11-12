package it.cnr.ilc.lexo.manager.cpp;

import static org.assertj.core.api.Assertions.*;
import it.cnr.ilc.lexo.manager.text.CollocationStatisticsService.Metric;
import it.cnr.ilc.lexo.service.data.cpp.input.*;
import java.io.*;
import java.util.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.Test;

class CppContractTest {
    private void invalid(String raw,String code) {
        assertThatThrownBy(()->CppJsonCodec.read(raw,CppRunCreateRequest.class))
                .isInstanceOfSatisfying(CppException.class,e->assertThat(e.code).isEqualTo(code));
    }
    @Test void rejectsUnknownFieldsAndScalarCoercionsAtEveryLevel() {
        invalid("{\"search\":{\"posPattern\":[]}}","CPP_UNKNOWN_FIELD");
        invalid("{\"cppOptions\":{\"ordered\":\"false\"}}","CPP_INVALID_PARAMETER");
        invalid("{\"cppOptions\":{\"ordered\":null}}","CPP_INVALID_PARAMETER");
        invalid("{\"search\":{\"caseSensitive\":null}}","CPP_INVALID_PARAMETER");
        invalid("{\"search\":{\"leftWindow\":1.5}}","CPP_INVALID_PARAMETER");
        invalid("{\"search\":{\"node\":123}}","CPP_INVALID_PARAMETER");
        invalid("{\"collocations\":[null]}","CPP_INVALID_PARAMETER");
        invalid("{\"search\":{\"limit\":2147483648}}","CPP_INVALID_PARAMETER");
        invalid("{\"search\":{},\"search\":{}}","CPP_BAD_JSON");
        invalid("{} {}","CPP_BAD_JSON"); invalid("[]","CPP_INVALID_PARAMETER");
        invalid("{\"unrecognized\":true}","CPP_UNKNOWN_FIELD");
        assertThatThrownBy(()->CppJsonCodec.read("{\"search\":{}}",CppRunPatchRequest.class))
                .isInstanceOfSatisfying(CppException.class,e->assertThat(e.code).isEqualTo("CPP_UNKNOWN_FIELD"));
    }
    @Test void boundsStreamingJsonAndRejectsMalformedUtf8AndExcessiveDepth() throws Exception {
        String old=it.cnr.ilc.lexo.LexOProperties.getProperty("CPP.write.maxBytes");
        final boolean[] closed={false};
        try {
            it.cnr.ilc.lexo.LexOProperties.setProperty("CPP.write.maxBytes","16");
            InputStream stream=new ByteArrayInputStream(new byte[32]) {
                @Override public void close() { closed[0]=true; }
            };
            assertThatThrownBy(()->CppJsonCodec.read(stream,CppRunCreateRequest.class))
                    .isInstanceOfSatisfying(CppException.class,e->assertThat(e.code).isEqualTo("CPP_LIMIT_EXCEEDED"));
            assertThat(closed[0]).isTrue();
        } finally { it.cnr.ilc.lexo.LexOProperties.setProperty("CPP.write.maxBytes",old); }
        assertThatThrownBy(()->CppJsonCodec.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),CppRunCreateRequest.class))
                .isInstanceOfSatisfying(CppException.class,e->assertThat(e.code).isEqualTo("CPP_BAD_JSON"));
        invalid("[[[[[[[[[{}]]]]]]]]]","CPP_INVALID_PARAMETER");
    }

    @Test void allEighteenMappingsExistInTheUnmodifiedOntology() throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/bootstrap/schema/collocation-score.ttl")) {
            Model schema=Rio.parse(in,"urn:schema:",RDFFormat.TURTLE);
            Set<IRI> individuals=new HashSet<IRI>();
            for(Metric m:Metric.values()) {
                IRI individual=CppMeasureRegistry.individual(m.name()); individuals.add(individual);
                assertThat(schema.contains(individual,RDF.TYPE,OWL.NAMEDINDIVIDUAL)).as(m.name()).isTrue();
                assertThat(schema.contains(CppMeasureRegistry.property(m),RDF.TYPE,OWL.DATATYPEPROPERTY)).as(m.name()).isTrue();
            }
            assertThat(individuals).hasSize(18);
        }
    }
    @Test void ontologyBytesMatchTheSuppliedPackage() throws Exception {
        String[][] expected={
            {"cpp.ttl","35646f1c65a7d442a8e27f8d5c570ab34ae38fbe2402fe5f7b0cfdcae8966a36"},
            {"collocation-score.ttl","4670761671d7657815be2c623645363670343e241bc8d982a34476beef18c20e"}
        };
        for(String[] entry:expected) {
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(InputStream in=getClass().getResourceAsStream("/bootstrap/schema/"+entry[0])) {
                byte[] buffer=new byte[4096]; int n;
                while((n=in.read(buffer))!=-1) { digest.update(buffer,0,n); }
            }
            StringBuilder actual=new StringBuilder(); for(byte b:digest.digest()) { actual.append(String.format("%02x", b & 255)); }
            assertThat(actual.toString()).isEqualTo(entry[1]);
        }
    }

    @Test void validatesCanonicalUuidsAbsoluteIrisAndTimeZones() {
        for(String value:Arrays.asList("relative","https://example.org/> } INSERT DATA {","file path")) {
            assertThatThrownBy(()->CppResourceUris.iri(value)).isInstanceOf(CppException.class);
        }
        for(String id:Arrays.asList("1-1-1-1-1",UUID.randomUUID().toString().toUpperCase(Locale.ROOT),"export")) {
            assertThatThrownBy(()->new CppResourceUris().run(id)).isInstanceOf(CppException.class);
        }
        assertThat(CppJsonCodec.time("2026-10-15T14:00:00+02:00")).isEqualTo("2026-10-15T12:00:00Z");
        assertThatThrownBy(()->CppJsonCodec.time("2026-10-15T12:00:00")).isInstanceOf(CppException.class);
    }
}
