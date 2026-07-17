package it.cnr.ilc.lexo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Prevents GraphDB 9 repository types or namespaces from returning to the templates. */
class GraphDbRepositoryTemplateTest {

    private static final String REP = "http://www.openrdf.org/config/repository#";
    private static final String SAIL = "http://www.openrdf.org/config/sail#";
    private static final String GRAPHDB = "http://www.ontotext.com/config/graphdb#";

    @Test
    @DisplayName("Both repository templates use the GraphDB 10 configuration vocabulary")
    void templatesAreValidForGraphDb10() throws Exception {
        Map<String, String> templates = new LinkedHashMap<String, String>();
        templates.put("bootstrap/repositories/lexicon-repository.ttl",
                "owl-horst-optimized");
        templates.put("bootstrap/repositories/text-repository.ttl", "empty");

        for (Map.Entry<String, String> template : templates.entrySet()) {
            String configured = BootstrapResources.readUtf8(template.getKey())
                    .replace("__REPOSITORY_ID__", "test-repository")
                    .replace("__REPOSITORY_LABEL__", "Test repository")
                    .replace("__BASE_URL__", "https://example.org/");
            Model model = Rio.parse(new ByteArrayInputStream(
                            configured.getBytes(StandardCharsets.UTF_8)),
                    "", RDFFormat.TURTLE);

            assertThat(model.contains(null, iri(REP + "repositoryType"),
                    literal("graphdb:SailRepository")))
                    .as(template.getKey()).isTrue();
            assertThat(model.contains(null, iri(SAIL + "sailType"),
                    literal("graphdb:Sail")))
                    .as(template.getKey()).isTrue();
            assertThat(model.contains(null, iri(GRAPHDB + "ruleset"),
                    literal(template.getValue())))
                    .as(template.getKey()).isTrue();
            assertThat(configured)
                    .doesNotContain("FreeSailRepository", "FreeSail", "owlim:",
                            "http://www.ontotext.com/trree/owlim#");
        }
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static org.eclipse.rdf4j.model.Literal literal(String value) {
        return SimpleValueFactory.getInstance().createLiteral(value);
    }
}
