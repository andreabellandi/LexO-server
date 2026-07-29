package it.cnr.ilc.lexo.sparql;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalConceptItem;
import it.cnr.ilc.lexo.service.helper.LexicalConceptItemHelper;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LexicalConceptAttestationsTest {

    private static final String EX = "https://example.org/";

    @Test
    void rootConceptsCountOnlyDistinctAttestationsInAttestationGraphs() throws Exception {
        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            ValueFactory vf = connection.getValueFactory();
            IRI concept = vf.createIRI(EX, "concept");
            IRI emptyConcept = vf.createIRI(EX, "empty-concept");
            IRI search = vf.createIRI(EX, "search");
            IRI lucQuery = vf.createIRI(SparqlPrefix.LUC.getUri(), "query");
            IRI lucOffset = vf.createIRI(SparqlPrefix.LUC.getUri(), "offset");
            IRI lucLimit = vf.createIRI(SparqlPrefix.LUC.getUri(), "limit");
            IRI lucEntities = vf.createIRI(SparqlPrefix.LUC.getUri(), "entities");
            IRI narrower = vf.createIRI(SparqlPrefix.SKOS.getUri(), "narrower");
            IRI prefLabel = vf.createIRI(SparqlPrefix.SKOS.getUri(), "prefLabel");
            IRI fracAttestation = vf.createIRI(SparqlPrefix.FRAC.getUri(), "attestation");

            connection.add(search, RDF.TYPE,
                    vf.createIRI(SparqlPrefix.INST.getUri(), "lexicalConceptIndex"));
            connection.add(search, lucQuery, vf.createLiteral("prefLabel:*"));
            connection.add(search, lucOffset, vf.createLiteral("0"));
            connection.add(search, lucLimit, vf.createLiteral("50000"));
            connection.add(search, lucEntities, concept);
            connection.add(search, lucEntities, emptyConcept);
            connection.add(concept, prefLabel, vf.createLiteral("Concept", "it"));
            connection.add(emptyConcept, prefLabel, vf.createLiteral("Empty", "it"));
            connection.add(vf.createIRI(EX, "child-a"), narrower, concept);
            connection.add(vf.createIRI(EX, "child-b"), narrower, concept);

            IRI graphA = vf.createIRI(LexicalNamedGraphs.attestationGraphUri("file-a"));
            IRI graphB = vf.createIRI(LexicalNamedGraphs.attestationGraphUri("file-b"));
            connection.add(concept, fracAttestation, vf.createIRI(EX, "attestation-a"), graphA);
            connection.add(concept, fracAttestation, vf.createIRI(EX, "attestation-b"), graphB);

            // These statements are outside the attestation graph family and must not count.
            connection.add(concept, fracAttestation, vf.createIRI(EX, "default-attestation"));
            connection.add(concept, fracAttestation, vf.createIRI(EX, "unrelated-attestation"),
                    vf.createIRI(EX, "unrelated-graph"));

            String query = SparqlSelectData.DATA_LEXICAL_CONCEPTS_ROOT
                    .replace("_LABELPROPERTY_", SparqlPrefix.SKOS.getUri() + "prefLabel")
                    .replace("_DEFAULTLANGUAGE_", "it")
                    .replace("_LABEL_INDEX_", "prefLabel")
                    .replace("_ATTESTATION_GRAPH_BASE_",
                            LexicalNamedGraphs.attestationGraphBaseUri());

            LexicalConceptItemHelper helper = new LexicalConceptItemHelper();
            List<LexicalConceptItem> items;
            try (TupleQueryResult result = connection.prepareTupleQuery(query).evaluate()) {
                items = helper.newDataList(result);
            }

            assertThat(items).hasSize(2);
            LexicalConceptItem populated = items.stream()
                    .filter(item -> concept.stringValue().equals(item.getLexicalConcept()))
                    .findFirst().get();
            LexicalConceptItem empty = items.stream()
                    .filter(item -> emptyConcept.stringValue().equals(item.getLexicalConcept()))
                    .findFirst().get();
            assertThat(populated.getChildren()).isEqualTo(2);
            assertThat(populated.getAttestations()).isEqualTo(2);
            assertThat(empty.getAttestations()).isZero();
            assertThat(new ObjectMapper().readTree(helper.toJson(populated))
                    .path("attestations").asInt()).isEqualTo(2);
        } finally {
            repository.shutDown();
        }
    }

    @Test
    void everyLexicalConceptItemQueryAcceptsTheAttestationGraphBase() {
        String[] queries = {
            SparqlSelectData.DATA_LEXICAL_CONCEPTS_CHILDREN
                    .replace("_LEXICALCONCEPT_", EX + "parent")
                    .replace("_LABELPROPERTY_", SparqlPrefix.SKOS.getUri() + "prefLabel")
                    .replace("_DEFAULTLANGUAGE_", "it"),
            SparqlSelectData.DATA_LEXICAL_CONCEPTS_ROOT
                    .replace("_LABELPROPERTY_", SparqlPrefix.SKOS.getUri() + "prefLabel")
                    .replace("_DEFAULTLANGUAGE_", "it")
                    .replace("_LABEL_INDEX_", "prefLabel"),
            SparqlSelectData.DATA_TOP_LEXICAL_CONCEPT_OF_A_CONCEPT_SET
                    .replace("_LEXICALCONCEPT_", EX + "scheme")
                    .replace("_LABELPROPERTY_", SparqlPrefix.SKOS.getUri() + "prefLabel")
                    .replace("_DEFAULTLANGUAGE_", "it")
                    .replace("_LABEL_INDEX_", "prefLabel"),
            SparqlSelectData.DATA_LEXICAL_CONCEPTS_FILTER
                    .replace("[FILTER]", "prefLabel:test")
                    .replace("_LABELPROPERTY_", SparqlPrefix.SKOS.getUri() + "prefLabel")
                    .replace("[LIMIT]", "10")
                    .replace("[OFFSET]", "0")
        };

        SailRepository repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            for (String template : queries) {
                String query = template.replace("_ATTESTATION_GRAPH_BASE_",
                        LexicalNamedGraphs.attestationGraphBaseUri());
                assertThat(query).contains("?attestations", "GRAPH ?attestationGraph")
                        .doesNotContain("_ATTESTATION_GRAPH_BASE_");
                connection.prepareTupleQuery(query);
            }
        } finally {
            repository.shutDown();
        }
    }
}
