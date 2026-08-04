package it.cnr.ilc.lexo.manager.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.Test;

class MetadataPolicyTest {

    @Test
    void protectsEveryPropertyInTheGlobalNamespaces() {
        for (String namespace : Arrays.asList(
                "http://www.w3.org/ns/lemon/ontolex#",
                "http://www.w3.org/ns/lemon/frac#",
                "http://www.w3.org/ns/lemon/lime#",
                "http://www.w3.org/ns/lemon/vartrans#",
                "http://www.w3.org/ns/lemon/synsem#",
                "http://www.w3.org/2004/02/skos/core#",
                "http://www.w3.org/ns/lemon/decomp#")) {
            assertThat(MetadataPolicy.isProtected(namespace + "anyProperty"))
                    .as(namespace)
                    .isTrue();
        }
    }

    @Test
    void protectsAuditAndRdfStructuralPropertiesOnlyOutsideThoseNamespaces() {
        assertThat(MetadataPolicy.isProtected(DCTERMS.CREATOR.stringValue())).isTrue();
        assertThat(MetadataPolicy.isProtected(DCTERMS.CREATED.stringValue())).isTrue();
        assertThat(MetadataPolicy.isProtected(DCTERMS.MODIFIED.stringValue())).isTrue();
        assertThat(MetadataPolicy.isProtected(RDF.TYPE.stringValue())).isTrue();
        assertThat(MetadataPolicy.isProtected(RDF.VALUE.stringValue())).isTrue();
        assertThat(MetadataPolicy.isProtected(DCTERMS.TITLE.stringValue())).isFalse();
        assertThat(MetadataPolicy.isProtected(
                "http://www.w3.org/2000/01/rdf-schema#label")).isFalse();
    }

    @Test
    void alwaysAllowsSkosNoteWhileProtectingOtherSkosProperties() {
        assertThat(MetadataPolicy.isProtected(
                "http://www.w3.org/2004/02/skos/core#note")).isFalse();
        assertThat(MetadataPolicy.isProtected(
                "http://www.w3.org/2004/02/skos/core#definition")).isTrue();
    }
}
