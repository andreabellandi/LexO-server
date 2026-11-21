package it.cnr.ilc.lexo.service;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class Dsh2027NamespaceContractTest {
    private static final String LEXINT =
            "https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#";
    private static final String PUB =
            "https://ilc.cnr.it/klab/ontology/lexint/publication#";

    @Test
    void ontologyShapesCodeFixturesAndQueriesUseTheApprovedPredicates()
            throws Exception {
        Path root = Paths.get("").toAbsolutePath().normalize();
        String profile = read(root.resolve(
                "src/main/resources/bootstrap/schema/lexint-profile.ttl"));
        String shapes = read(root.resolve(
                "src/main/resources/bootstrap/shapes/lexint-shapes.ttl"));
        String publication = read(root.resolve(
                "src/main/resources/bootstrap/schema/lexint-publication.ttl"));
        String publicationShapes = read(root.resolve(
                "src/main/resources/bootstrap/shapes/lexint-publication-shapes.ttl"));
        String cq04 = read(root.resolve("evaluation/dsh2027/queries/cq04.rq"));
        String cq05 = read(root.resolve("evaluation/dsh2027/queries/cq05.rq"));
        String fixture = read(root.resolve(
                "evaluation/dsh2027/fixtures/claim-specs.yaml"));

        assertThat(profile).contains("lexint:targetAssertion a owl:ObjectProperty")
                .doesNotContain("lexint:targetsAssertion");
        assertThat(shapes).contains("sh:path lexint:targetAssertion")
                .doesNotContain("lexint:targetsAssertion");
        assertThat(LexIntVocabulary.TARGET_ASSERTION_PROPERTY.stringValue())
                .isEqualTo(LEXINT + "targetAssertion");
        assertThat(cq05).contains("lexint:targetAssertion ?target")
                .doesNotContain("lexint:targetsAssertion");
        assertThat(fixture).contains(LEXINT + "targetAssertion")
                .doesNotContain("targetsAssertion");

        assertThat(publication).contains("pub:targetAssertion a owl:ObjectProperty")
                .doesNotContain("pub:targetsAssertion");
        assertThat(publicationShapes).contains("sh:path pub:targetAssertion")
                .doesNotContain("pub:targetsAssertion");
        assertThat(LexIntVocabulary.PUB_TARGET_ASSERTION.stringValue())
                .isEqualTo(PUB + "targetAssertion");
        assertThat(cq04).contains("pub:targetAssertion ?target")
                .doesNotContain("pub:targetsAssertion");
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
