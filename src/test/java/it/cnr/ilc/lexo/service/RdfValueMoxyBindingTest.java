package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.service.data.attestation.input.AttestationByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationFilter;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalConceptCreationRequest;
import it.cnr.ilc.lexo.service.data.lexicon.input.LexicalEntryCreationRequest;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedHashMap;
import org.eclipse.persistence.jaxb.rs.MOXyJsonProvider;
import org.junit.jupiter.api.Test;

class RdfValueMoxyBindingTest {

    private static final String PROPERTY = "{\"property\":"
            + "\"https://example.org/vocabulary/confidence\",\"values\":[{"
            + "\"value\":\"0.92\",\"type\":\"literal\",\"datatype\":"
            + "\"http://www.w3.org/2001/XMLSchema#decimal\"}]}";

    @Test
    void readsLexicalEntryRdfValues() throws Exception {
        LexicalEntryCreationRequest request = read("{\"label\":\"casa\","
                + "\"type\":\"http://www.w3.org/ns/lemon/ontolex#Word\","
                + "\"language\":\"it\",\"senses\":[{\"metadata\":["
                + PROPERTY + "]}]}", LexicalEntryCreationRequest.class);

        assertThat(request.senses.get(0).metadata.get(0).values.get(0).type)
                .isEqualTo("literal");
    }

    @Test
    void readsLexicalConceptMetadataRdfValues() throws Exception {
        LexicalConceptCreationRequest request = read("{\"metadata\":["
                + PROPERTY + "]}", LexicalConceptCreationRequest.class);

        assertThat(request.metadata.get(0).values.get(0).type)
                .isEqualTo("literal");
    }

    @Test
    void readsCommonMetadataPatchRdfValues() throws Exception {
        MetadataPatchRequest request = read("{\"properties\":[" + PROPERTY
                + "]}", MetadataPatchRequest.class);

        assertThat(request.properties.get(0).values.get(0).type)
                .isEqualTo("literal");
    }

    @Test
    void readsAttestationCreationMetadataRdfValues() throws Exception {
        AttestationByLocusInput request = read("{\"observables\":[{"
                + "\"observable\":\"https://example.org/entry\",\"metadata\":["
                + PROPERTY + "]}]}", AttestationByLocusInput.class);

        assertThat(request.observables.get(0).metadata.get(0).values.get(0).type)
                .isEqualTo("literal");
    }

    @Test
    void readsAttestationFilterRdfValues() throws Exception {
        AttestationFilter request = read("{\"operator\":\"EQ\","
                + "\"field\":\"textMetadata\",\"rdfValues\":[{"
                + "\"value\":\"testo\",\"type\":\"literal\"}]}",
                AttestationFilter.class);

        assertThat(request.rdfValues.get(0).type).isEqualTo("literal");
    }

    @SuppressWarnings("unchecked")
    private <T> T read(String json, Class<T> type) throws Exception {
        MOXyJsonProvider provider = new MOXyJsonProvider();
        return (T) provider.readFrom((Class<Object>) (Class<?>) type, type,
                new Annotation[0], MediaType.APPLICATION_JSON_TYPE,
                new MultivaluedHashMap<String, String>(),
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
