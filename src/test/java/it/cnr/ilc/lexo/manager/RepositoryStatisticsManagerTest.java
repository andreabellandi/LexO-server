package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.Corpus;
import it.cnr.ilc.lexo.service.data.administration.output.RepositoryStatistics.RdfValue;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RepositoryStatisticsManagerTest {

    private static final String LIME = "http://www.w3.org/ns/lemon/lime#";
    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String LEXICOG = "http://www.w3.org/ns/lemon/lexicog#";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private Repository lexicalRepository;
    private Repository textRepository;

    @BeforeEach
    void setUp() {
        lexicalRepository = new SailRepository(new MemoryStore());
        textRepository = new SailRepository(new MemoryStore());
        lexicalRepository.init();
        textRepository.init();
    }

    @AfterEach
    void tearDown() {
        lexicalRepository.shutDown();
        textRepository.shutDown();
    }

    @Test
    @DisplayName("Repository report aggregates lexical resources, corpora and RDF metadata")
    void buildsCompleteRepositoryReport() {
        populateLexicalRepository();
        populateTextRepository();

        RepositoryStatistics result = new RepositoryStatisticsManager().getStatistics(
                lexicalRepository, "LexOLexica", textRepository, "LexOTexts");

        assertThat(result.lexicalRepository.name).isEqualTo("LexOLexica");
        assertThat(result.lexicalRepository.totalStatements)
                .isEqualTo(result.lexicalRepository.explicitStatements);
        assertThat(result.lexicalRepository.inferredStatements).isZero();
        assertThat(result.lexicalRepository.expansionRatio).isEqualTo(1.0d);
        assertThat(result.lexicalRepository.lexiconCount).isEqualTo(1);
        assertThat(result.lexicalRepository.lexicons.get(0).descriptions)
                .extracting(value -> value.value)
                .containsExactly("Lessico di prova");
        assertThat(result.lexicalRepository.lexicons.get(0).languages)
                .extracting(value -> value.value)
                .containsExactly("it");
        assertThat(result.lexicalRepository.lexicalEntryCount).isEqualTo(2);
        assertThat(result.lexicalRepository.lexicalSenseCount).isEqualTo(1);
        assertThat(result.lexicalRepository.dictionaryCount).isEqualTo(1);
        assertThat(result.lexicalRepository.dictionaryEntryCount).isEqualTo(1);
        assertThat(result.lexicalRepository.attestationCount).isEqualTo(1);

        assertThat(result.textRepository.name).isEqualTo("LexOTexts");
        assertThat(result.textRepository.corpusCount).isEqualTo(1);
        assertThat(result.textRepository.textCount).isEqualTo(2);
        Corpus corpus = result.textRepository.corpora.get(0);
        assertThat(corpus.textCount).isEqualTo(1);
        assertThat(corpus.metadata.get("title"))
                .extracting(value -> value.value)
                .containsExactly("Corpus storico");
        List<RdfValue> creators = corpus.metadata.get("creator");
        assertThat(creators).hasSize(1);
        assertThat(creators.get(0).kind).isEqualTo("IRI");
        assertThat(creators.get(0).value).isEqualTo("https://example.org/people/rossi");
        assertThat(corpus.texts).extracting(text -> text.iri)
                .containsExactly("https://example.org/text/one#context");
        assertThat(corpus.texts.get(0).metadata.get("language"))
                .extracting(value -> value.value)
                .containsExactly("it");
        assertThat(result.textRepository.unassignedTextCount).isEqualTo(1);
        assertThat(result.textRepository.unassignedTexts.get(0).iri)
                .isEqualTo("https://example.org/text/two#context");
    }

    @Test
    @DisplayName("An empty repository has no expansion ratio because it has no explicit statements")
    void handlesEmptyRepositories() {
        RepositoryStatistics result = new RepositoryStatisticsManager().getStatistics(
                lexicalRepository, "LexOLexica", textRepository, "LexOTexts");

        assertThat(result.lexicalRepository.totalStatements).isZero();
        assertThat(result.lexicalRepository.expansionRatio).isNull();
        assertThat(result.textRepository.corpusCount).isZero();
        assertThat(result.textRepository.textCount).isZero();
    }

    private void populateLexicalRepository() {
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
            IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri());
            IRI unrelatedGraph = iri("https://example.org/graphs/unrelated");
            IRI lexicon = iri("https://example.org/lexicon");
            connection.add(lexicon, RDF.TYPE, iri(LIME + "Lexicon"), lexicalGraph);
            connection.add(lexicon, DCTERMS.DESCRIPTION,
                    vf.createLiteral("Lessico di prova", "it"), lexicalGraph);
            connection.add(lexicon, iri(LIME + "language"), vf.createLiteral("it"),
                    lexicalGraph);
            connection.add(iri("https://example.org/entry/one"), RDF.TYPE,
                    iri(ONTOLEX + "LexicalEntry"), lexicalGraph);
            connection.add(iri("https://example.org/entry/two"), RDF.TYPE,
                    iri(ONTOLEX + "LexicalEntry"), lexicalGraph);
            connection.add(iri("https://example.org/sense/one"), RDF.TYPE,
                    iri(ONTOLEX + "LexicalSense"), lexicalGraph);

            IRI dictionary = iri("https://example.org/dictionary");
            connection.add(dictionary, RDF.TYPE, iri(LEXICOG + "LexicographicResource"),
                    lexicalGraph);
            connection.add(dictionary, DCTERMS.DESCRIPTION,
                    vf.createLiteral("Dizionario di prova", "it"), lexicalGraph);
            connection.add(dictionary, DCTERMS.LANGUAGE, vf.createLiteral("it"),
                    lexicalGraph);
            connection.add(iri("https://example.org/dictionary/entry"), RDF.TYPE,
                    iri(LEXICOG + "Entry"), lexicalGraph);
            connection.add(iri("https://example.org/attestation"), RDF.TYPE,
                    iri(FRAC + "Attestation"), attestationGraph);

            // These resources must remain invisible to lexical statistics.
            connection.add(iri("https://example.org/default-entry"), RDF.TYPE,
                    iri(ONTOLEX + "LexicalEntry"));
            connection.add(iri("https://example.org/wrong-graph-attestation"), RDF.TYPE,
                    iri(FRAC + "Attestation"), lexicalGraph);
            connection.add(iri("https://example.org/default-attestation"), RDF.TYPE,
                    iri(FRAC + "Attestation"));
            connection.add(iri("https://example.org/unrelated-entry"), RDF.TYPE,
                    iri(ONTOLEX + "LexicalEntry"), unrelatedGraph);
            connection.add(iri("https://example.org/unrelated-attestation"), RDF.TYPE,
                    iri(FRAC + "Attestation"), unrelatedGraph);
            connection.add(lexicon, DCTERMS.DESCRIPTION,
                    vf.createLiteral("Descrizione da ignorare", "it"), unrelatedGraph);
        }
    }

    private void populateTextRepository() {
        try (RepositoryConnection connection = textRepository.getConnection()) {
            IRI corpus = iri("https://example.org/corpus/one");
            IRI textOne = iri("https://example.org/text/one#context");
            IRI textTwo = iri("https://example.org/text/two#context");
            connection.add(corpus, RDF.TYPE, iri(NIF + "ContextCollection"));
            connection.add(corpus, DCTERMS.TITLE, vf.createLiteral("Corpus storico", "it"));
            connection.add(corpus, DCTERMS.CREATOR,
                    iri("https://example.org/people/rossi"));
            connection.add(corpus, DCTERMS.HAS_PART, textOne);

            connection.add(textOne, RDF.TYPE, iri(NIF + "Context"));
            connection.add(textOne, DCTERMS.TITLE, vf.createLiteral("Primo testo", "it"));
            connection.add(textOne, DCTERMS.LANGUAGE, vf.createLiteral("it"));
            connection.add(textOne, DCTERMS.IS_PART_OF, corpus);

            connection.add(textTwo, RDF.TYPE, iri(NIF + "Context"));
            connection.add(textTwo, DCTERMS.TITLE, vf.createLiteral("Testo isolato", "it"));
        }
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }
}
