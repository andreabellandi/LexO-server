package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.service.data.attestation.AttestationMetadataValue;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByObservableInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationMetadataBatch;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationMetadataProperty;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationMetadataUpdate;
import it.cnr.ilc.lexo.service.data.attestation.output.Attestation;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationDeletionResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationListItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationMetadataPatchResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationPage;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationOccurrence;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AttestationManagerTest {

    private static final String ONTOLEX = "http://www.w3.org/ns/lemon/ontolex#";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static final String DCMITYPE = "http://purl.org/dc/dcmitype/";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String NIFS =
            "https://lexo.ilc.cnr.it/vocabulary/nif-structure#";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String TEXT_GRAPH_BASE =
            "https://lexo.ilc.cnr.it/graphs/nif/documents/";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private Repository lexicalRepository;
    private Repository textRepository;
    private AttestationManager manager;
    private IRI observable;
    private IRI context;
    private IRI textGraph;

    @BeforeEach
    void setUp() {
        lexicalRepository = new SailRepository(new MemoryStore());
        textRepository = new SailRepository(new MemoryStore());
        lexicalRepository.init();
        textRepository.init();
        manager = new AttestationManager(lexicalRepository, textRepository);
        observable = iri("https://example.org/lexicon/entry");
        context = iri("https://example.org/text/interview#context");
        textGraph = iri(TEXT_GRAPH_BASE + "file-a");

        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(observable, RDF.TYPE, iri(ONTOLEX + "LexicalEntry"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(context, RDF.TYPE, iri(DCMITYPE + "Text"), textGraph);
            connection.add(context, RDF.TYPE, iri(NIF + "Context"), textGraph);
            connection.add(context, DCTERMS.LANGUAGE,
                    vf.createLiteral("it"), textGraph);
            connection.add(context, iri(NIF + "isString"),
                    vf.createLiteral("A😀B gli stessi diritti", "it"), textGraph);
            connection.add(context, iri(NIFS + "fileId"),
                    vf.createLiteral("file-a"), textGraph);
        }
    }

    @AfterEach
    void tearDown() {
        lexicalRepository.shutDown();
        textRepository.shutDown();
    }

    @Test
    void createsAttestationAndUnicodeLocusInTheirNamedGraphs() throws Exception {
        Attestation result = manager.create(observable.stringValue(), "😀B",
                "1", "3", context.stringValue(), false, "user7");

        IRI attestation = iri(result.attestation);
        IRI locus = iri("https://example.org/text/interview#char=1,3");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        assertThat(result.fileId).isEqualTo("file-a");
        assertThat(result.locus).isEqualTo(locus.stringValue());
        assertThat(result.creator).isEqualTo("user7");
        assertThat(result.observableLabel).isEqualTo("no label");
        assertThat(result.attestation).startsWith("https://lexo.ilc.cnr.it#LexO_");

        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(connection.hasStatement(attestation, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(observable, iri(FRAC + "attestation"),
                    attestation, false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, DCTERMS.DESCRIPTION,
                    null, false, attestationGraph)).isFalse();
            assertThat(connection.hasStatement(attestation, DCTERMS.CREATOR,
                    vf.createLiteral("user7"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, DCTERMS.CREATED,
                    vf.createLiteral(result.creationDate), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, DCTERMS.MODIFIED,
                    vf.createLiteral(result.lastUpdate), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, iri(FRAC + "gloss"),
                    vf.createLiteral("😀B", "it"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, RDF.VALUE,
                    vf.createLiteral("😀B", "it"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, iri(FRAC + "locus"),
                    locus, false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, iri(FRAC + "observedIn"),
                    context, false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, null, null, false,
                    iri(LexicalNamedGraphs.lexiconGraphUri()))).isFalse();
            assertDefaultGraphEmpty(connection);
        }
        try (RepositoryConnection connection = textRepository.getConnection()) {
            assertThat(connection.hasStatement(locus, RDF.TYPE,
                    iri(NIF + "Phrase"), false, textGraph)).isTrue();
            assertThat(connection.hasStatement(locus, iri(NIF + "anchorOf"),
                    vf.createLiteral("😀B", "it"), false, textGraph)).isTrue();
            assertThat(connection.hasStatement(locus, iri(NIF + "referenceContext"),
                    context, false, textGraph)).isTrue();
            assertThat(connection.hasStatement(locus, iri(NIF + "beginIndex"),
                    vf.createLiteral("1", XSD.NON_NEGATIVE_INTEGER), false,
                    textGraph)).isTrue();
            assertThat(connection.hasStatement(locus, iri(NIF + "endIndex"),
                    vf.createLiteral("3", XSD.NON_NEGATIVE_INTEGER), false,
                    textGraph)).isTrue();
            assertThat(connection.hasStatement(locus, iri(PROV + "wasGeneratedBy"),
                    iri("https://lexo.ilc.cnr.it#AttestationService"), false,
                    textGraph)).isTrue();
            assertDefaultGraphEmpty(connection);
        }
    }

    @Test
    void resolvesAContextMemberFromACollectionGraph() throws Exception {
        IRI corpus = iri("https://example.org/corpus/interviews");
        IRI corpusGraph = iri("https://lexo.ilc.cnr.it/graphs/nif/corpora/corpus-a");
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(corpus, RDF.TYPE, iri(DCMITYPE + "Collection"), corpusGraph);
            connection.add(corpus, DCTERMS.HAS_PART, context, corpusGraph);
        }

        Attestation result = manager.create(observable.stringValue(), "gli",
                "4", "7", corpus.stringValue(), false, "user7");

        assertThat(result.fileId).isEqualTo("file-a");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(connection.hasStatement(iri(result.attestation),
                    iri(FRAC + "observedIn"), corpus, false,
                    iri(LexicalNamedGraphs.attestationGraphUri("file-a")))).isTrue();
        }
    }

    @Test
    void resolvesTheContextDerivedFromATextSource() throws Exception {
        IRI source = iri("https://example.org/text/interview/source");
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(source, RDF.TYPE, iri(DCMITYPE + "Text"), textGraph);
            connection.add(context, iri(PROV + "wasDerivedFrom"), source, textGraph);
        }

        Attestation result = manager.create(observable.stringValue(), "A",
                "0", "1", source.stringValue(), false, "user7");

        assertThat(result.fileId).isEqualTo("file-a");
        assertThat(result.locus).isEqualTo(
                "https://example.org/text/interview#char=0,1");
    }

    @Test
    void rejectsMismatchingCanonicalValueWithoutPartialWrites() {
        assertThatThrownBy(() -> manager.create(observable.stringValue(), "wrong",
                "1", "3", context.stringValue(), false, "user7"))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("VALUE_MISMATCH");

        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE, iri(FRAC + "Attestation"),
                    false, iri(LexicalNamedGraphs.attestationGraphUri("file-a")))).isFalse();
            assertThat(text.hasStatement(iri("https://example.org/text/interview#char=1,3"),
                    null, null, false, textGraph)).isFalse();
        }
    }

    @Test
    void rejectsObservableOutsideTheSupportedOntolexTypes() {
        IRI unsupported = iri("https://example.org/lexicon/unsupported");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(unsupported, RDF.TYPE, iri(ONTOLEX + "Morph"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }

        assertThatThrownBy(() -> manager.create(unsupported.stringValue(), "A",
                "0", "1", context.stringValue(), false, "user7"))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_OBSERVABLE");
    }

    @Test
    void createsStablePerUrlGraphsForExternalTexts() throws Exception {
        Attestation result = manager.create(observable.stringValue(), "remote",
                "2", "8", "https://example.org/external/text", true,
                "user7");

        assertThat(result.fileId).startsWith("external-");
        IRI locus = iri("https://example.org/external/text#char=2,8");
        IRI externalTextGraph = iri(TEXT_GRAPH_BASE + result.fileId);
        try (RepositoryConnection text = textRepository.getConnection();
             RepositoryConnection lexical = lexicalRepository.getConnection()) {
            assertThat(text.hasStatement(locus, RDF.TYPE, iri(NIF + "Phrase"),
                    false, externalTextGraph)).isTrue();
            assertThat(lexical.hasStatement(iri(result.attestation), RDF.TYPE,
                    iri(FRAC + "Attestation"), false,
                    iri(LexicalNamedGraphs.attestationGraphUri(result.fileId)))).isTrue();
            assertDefaultGraphEmpty(text);
            assertDefaultGraphEmpty(lexical);
        }
    }

    @Test
    void rejectsExternalUrlsWithoutAHost() {
        assertThatThrownBy(() -> manager.create(observable.stringValue(), "remote",
                "0", "6", "https:external-text", true, "user7"))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_EXTERNAL_URL");
    }

    @Test
    void createsMultipleAttestationsAndLociAsOneBatch() throws Exception {
        List<AttestationOccurrence> occurrences = Arrays.asList(
                new AttestationOccurrence("A", 0, 1),
                new AttestationOccurrence("gli", 4, 7));

        List<Attestation> results = manager.createBatch(observable.stringValue(),
                context.stringValue(), false, "user7", occurrences);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(item -> item.attestation).doesNotHaveDuplicates();
        assertThat(new ObjectMapper().writeValueAsString(results))
                .doesNotContain("\"description\"");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(count(lexical, null, RDF.TYPE, iri(FRAC + "Attestation"),
                    attestationGraph)).isEqualTo(2);
            for (Attestation result : results) {
                IRI attestation = iri(result.attestation);
                assertThat(lexical.hasStatement(attestation,
                        iri(FRAC + "gloss"), vf.createLiteral(result.value, "it"),
                        false, attestationGraph)).isTrue();
                assertThat(lexical.hasStatement(attestation, RDF.VALUE,
                        vf.createLiteral(result.value, "it"), false,
                        attestationGraph)).isTrue();
            }
            assertThat(text.hasStatement(
                    iri("https://example.org/text/interview#char=0,1"),
                    RDF.TYPE, iri(NIF + "Phrase"), false, textGraph)).isTrue();
            assertThat(text.hasStatement(
                    iri("https://example.org/text/interview#char=4,7"),
                    RDF.TYPE, iri(NIF + "Phrase"), false, textGraph)).isTrue();
            assertDefaultGraphEmpty(lexical);
            assertDefaultGraphEmpty(text);
        }
    }

    @Test
    void createsOneAttestationPerObservableAtTheSameLocus() throws Exception {
        IRI form = iri("https://example.org/lexicon/form-at-locus");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(form, RDF.TYPE, iri(ONTOLEX + "Form"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
            connection.add(form, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("A", "it"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        AttestationByLocusInput input = new AttestationByLocusInput("A", 0, 1,
                Arrays.asList(observable.stringValue(), form.stringValue()));

        List<Attestation> results = manager.createByLocus(context.stringValue(),
                false, "user7", input);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(item -> item.observable)
                .containsExactly(observable.stringValue(), form.stringValue());
        assertThat(results).extracting(item -> item.locus)
                .containsOnly("https://example.org/text/interview#char=0,1");
        assertThat(new ObjectMapper().writeValueAsString(results))
                .doesNotContain("\"description\"");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        IRI locus = iri("https://example.org/text/interview#char=0,1");
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(count(lexical, null, RDF.TYPE, iri(FRAC + "Attestation"),
                    attestationGraph)).isEqualTo(2);
            assertThat(lexical.hasStatement(observable, iri(FRAC + "attestation"),
                    iri(results.get(0).attestation), false, attestationGraph)).isTrue();
            assertThat(lexical.hasStatement(form, iri(FRAC + "attestation"),
                    iri(results.get(1).attestation), false, attestationGraph)).isTrue();
            for (Attestation result : results) {
                assertThat(lexical.hasStatement(iri(result.attestation),
                        iri(FRAC + "gloss"), vf.createLiteral("A", "it"), false,
                        attestationGraph)).isTrue();
                assertThat(lexical.hasStatement(iri(result.attestation), RDF.VALUE,
                        vf.createLiteral("A", "it"), false,
                        attestationGraph)).isTrue();
            }
            assertThat(lexical.hasStatement(null, DCTERMS.DESCRIPTION, null,
                    false, attestationGraph)).isFalse();
            assertThat(count(text, locus, iri(NIF + "anchorOf"), null,
                    textGraph)).isEqualTo(1);
            assertThat(text.hasStatement(locus, iri(NIF + "anchorOf"),
                    vf.createLiteral("A", "it"), false, textGraph)).isTrue();
            assertDefaultGraphEmpty(lexical);
            assertDefaultGraphEmpty(text);
        }
    }

    @Test
    void reusesCompatibleWordLocusWithoutChangingItsNifTypes() throws Exception {
        IRI locus = iri("https://example.org/text/interview#char=0,1");
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(locus, RDF.TYPE, iri(NIF + "OffsetBasedString"),
                    textGraph);
            connection.add(locus, RDF.TYPE, iri(NIF + "Word"), textGraph);
            connection.add(locus, iri(NIF + "anchorOf"),
                    vf.createLiteral("A", "it"), textGraph);
            connection.add(locus, iri(NIF + "beginIndex"),
                    vf.createLiteral("0", XSD.NON_NEGATIVE_INTEGER), textGraph);
            connection.add(locus, iri(NIF + "endIndex"),
                    vf.createLiteral("1", XSD.NON_NEGATIVE_INTEGER), textGraph);
            connection.add(locus, iri(NIF + "referenceContext"), context,
                    textGraph);
        }
        AttestationByLocusInput input = new AttestationByLocusInput("A", 0, 1,
                Collections.singletonList(observable.stringValue()));

        List<Attestation> results = manager.createByLocus(context.stringValue(),
                false, "user7", input);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).locusTypes).containsExactly(
                NIF + "OffsetBasedString", NIF + "Word");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(count(lexical, null, RDF.TYPE, iri(FRAC + "Attestation"),
                    attestationGraph)).isEqualTo(1);
            assertThat(text.hasStatement(locus, RDF.TYPE, iri(NIF + "Phrase"),
                    false, textGraph)).isFalse();
            assertThat(text.hasStatement(locus, RDF.TYPE,
                    iri(NIF + "RFC5147String"), false, textGraph)).isFalse();
            assertThat(text.hasStatement(locus, iri(PROV + "wasGeneratedBy"),
                    null, false, textGraph)).isFalse();
            assertThat(count(text, locus, iri(NIF + "anchorOf"), null,
                    textGraph)).isEqualTo(1);
            assertDefaultGraphEmpty(lexical);
            assertDefaultGraphEmpty(text);
        }
    }

    @Test
    void rejectsExistingLocusWhenItsIdentityDataDiffer() {
        IRI locus = iri("https://example.org/text/interview#char=0,1");
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(locus, RDF.TYPE, iri(NIF + "Word"), textGraph);
            connection.add(locus, iri(NIF + "anchorOf"),
                    vf.createLiteral("B", "it"), textGraph);
            connection.add(locus, iri(NIF + "beginIndex"),
                    vf.createLiteral("0", XSD.NON_NEGATIVE_INTEGER), textGraph);
            connection.add(locus, iri(NIF + "endIndex"),
                    vf.createLiteral("1", XSD.NON_NEGATIVE_INTEGER), textGraph);
            connection.add(locus, iri(NIF + "referenceContext"), context,
                    textGraph);
        }
        AttestationByLocusInput input = new AttestationByLocusInput("A", 0, 1,
                Collections.singletonList(observable.stringValue()));

        assertThatThrownBy(() -> manager.createByLocus(context.stringValue(),
                false, "user7", input))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("LOCUS_CONFLICT");

        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, attestationGraph)).isFalse();
        }
    }

    @Test
    void rejectsByLocusBatchBeforeWritingWhenOneObservableIsInvalid() {
        AttestationByLocusInput input = new AttestationByLocusInput("A", 0, 1,
                Arrays.asList(observable.stringValue(),
                        "https://example.org/lexicon/missing"));

        assertThatThrownBy(() -> manager.createByLocus(context.stringValue(),
                false, "user7", input))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_OBSERVABLE");
        assertThatThrownBy(() -> manager.createByLocus(context.stringValue(),
                false, "user7", new AttestationByLocusInput("A", 0, 1,
                        Collections.<String>emptyList())))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("MISSING_OBSERVABLES");
        assertThatThrownBy(() -> manager.createByLocus(context.stringValue(),
                false, "user7", new AttestationByLocusInput("A", null, 1,
                        Collections.singletonList(observable.stringValue()))))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("MISSING_PARAMETER");

        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, attestationGraph)).isFalse();
            assertThat(text.hasStatement(
                    iri("https://example.org/text/interview#char=0,1"),
                    null, null, false, textGraph)).isFalse();
        }
    }

    @Test
    void createsPlainAttestedLiteralsWhenTextLanguageMetadataIsMissing()
            throws Exception {
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.remove(context, DCTERMS.LANGUAGE, null, textGraph);
        }

        Attestation result = manager.create(observable.stringValue(), "A",
                "0", "1", context.stringValue(), false, "user7");

        IRI attestation = iri(result.attestation);
        IRI locus = iri("https://example.org/text/interview#char=0,1");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(attestation, iri(FRAC + "gloss"),
                    vf.createLiteral("A"), false, attestationGraph)).isTrue();
            assertThat(lexical.hasStatement(attestation, RDF.VALUE,
                    vf.createLiteral("A"), false, attestationGraph)).isTrue();
            assertThat(text.hasStatement(locus, iri(NIF + "anchorOf"),
                    vf.createLiteral("A"), false, textGraph)).isTrue();
        }
    }

    @Test
    void rejectsTheWholeBatchWhenOneOccurrenceIsInvalid() {
        List<AttestationOccurrence> occurrences = Arrays.asList(
                new AttestationOccurrence("A", 0, 1),
                new AttestationOccurrence("wrong", 1, 3));

        assertThatThrownBy(() -> manager.createBatch(observable.stringValue(),
                context.stringValue(), false, "user7", occurrences))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("VALUE_MISMATCH");
        assertThatThrownBy(() -> manager.createBatch(observable.stringValue(),
                context.stringValue(), false, "user7", Collections.emptyList()))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("MISSING_OCCURRENCES");
        assertThatThrownBy(() -> manager.createBatch(observable.stringValue(),
                context.stringValue(), false, "user7", Collections.singletonList(
                        new AttestationOccurrence("A", null, null))))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("MISSING_PARAMETER");

        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, attestationGraph)).isFalse();
            assertThat(text.hasStatement(
                    iri("https://example.org/text/interview#char=0,1"),
                    null, null, false, textGraph)).isFalse();
        }
    }

    @Test
    void listsOneTextAttestationsWithMetadataAndNifLoci() throws Exception {
        IRI form = iri("https://example.org/lexicon/form");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(form, RDF.TYPE, iri(ONTOLEX + "Form"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        addPersistedAttestation("file-a", "a", observable, "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "b", form, "user8", "😀B", 1, 3);
        addPersistedAttestation("file-a", "c", observable, null, "gli", 4, 7);
        addPersistedAttestation("file-b", "other", observable, "user7", "x", 0, 1);

        AttestationPage page = manager.list("file-a", null, null, null, null);

        assertThat(page.totalHits).isEqualTo(3);
        assertThat(page.limit).isEqualTo(200);
        assertThat(page.offset).isZero();
        assertThat(page.list).extracting(item -> item.attestation)
                .containsExactly("https://example.org/attestation/a",
                        "https://example.org/attestation/b",
                        "https://example.org/attestation/c");
        AttestationListItem first = page.list.get(0);
        assertThat(first.observable).isEqualTo(observable.stringValue());
        assertThat(first.observableTypes).contains(ONTOLEX + "LexicalEntry");
        assertThat(first.creator).isEqualTo("user7");
        assertThat(first.value).isEqualTo("A");
        assertThat(first.start).isEqualTo(0);
        assertThat(first.end).isEqualTo(1);
        assertThat(first.language).isEqualTo("it");
        assertThat(first.referenceContext).isEqualTo(context.stringValue());
        assertThat(first.locusTypes).contains(NIF + "Phrase", NIF + "RFC5147String");
        assertThat(new ObjectMapper().writeValueAsString(page))
                .doesNotContain("\"description\"");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(connection.hasStatement(
                    iri("https://example.org/attestation/a"), DCTERMS.DESCRIPTION,
                    vf.createLiteral("Description a"), false,
                    iri(LexicalNamedGraphs.attestationGraphUri("file-a")))).isTrue();
        }
    }

    @Test
    void atomicallyReplacesTypedMetadataAndReturnsItInAttestationLists()
            throws Exception {
        String confidence = "https://example.org/vocabulary/confidence";
        String reviewLabel = "https://example.org/vocabulary/reviewLabel";
        String source = "http://purl.org/dc/terms/source";
        IRI attestationA = iri("https://example.org/attestation/a");
        IRI attestationB = iri("https://example.org/attestation/b");
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        addPersistedAttestation("file-a", "a", observable, "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "b", observable, "user8", "A", 0, 1);
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(attestationA, iri(confidence),
                    vf.createLiteral("0.10", XSD.DECIMAL), graph);
        }

        AttestationMetadataBatch batch = metadataBatch(
                metadataUpdate(attestationA.stringValue(),
                        metadataProperty(confidence,
                                metadataLiteral("0.92", null,
                                        XSD.DECIMAL.stringValue())),
                        metadataProperty(reviewLabel,
                                metadataLiteral("approvata", "it", null),
                                metadataLiteral("approved", "en", null)),
                        metadataProperty(source,
                                metadataIri("https://example.org/source/one"))),
                metadataUpdate(attestationB.stringValue(),
                        metadataProperty(reviewLabel,
                                metadataLiteral("rejected", "en", null))));

        AttestationMetadataPatchResult result = manager.patchMetadata("file-a", batch);

        assertThat(result.fileId).isEqualTo("file-a");
        assertThat(result.updated).hasSize(2);
        assertThat(result.updated.get(0).properties)
                .containsExactly(confidence, reviewLabel, source);
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(connection.hasStatement(attestationA, iri(confidence),
                    vf.createLiteral("0.10", XSD.DECIMAL), false, graph)).isFalse();
            assertThat(connection.hasStatement(attestationA, iri(confidence),
                    vf.createLiteral("0.92", XSD.DECIMAL), false, graph)).isTrue();
            assertThat(connection.hasStatement(attestationA, iri(reviewLabel),
                    vf.createLiteral("approvata", "it"), false, graph)).isTrue();
            assertThat(connection.hasStatement(attestationA, iri(reviewLabel),
                    vf.createLiteral("approved", "en"), false, graph)).isTrue();
            assertThat(connection.hasStatement(attestationA, iri(source),
                    iri("https://example.org/source/one"), false, graph)).isTrue();
            assertThat(connection.hasStatement(attestationB, iri(reviewLabel),
                    vf.createLiteral("rejected", "en"), false, graph)).isTrue();
            assertThat(connection.hasStatement(attestationA, DCTERMS.MODIFIED,
                    vf.createLiteral(result.updated.get(0).lastUpdate), false,
                    graph)).isTrue();
            assertDefaultGraphEmpty(connection);
        }

        AttestationPage page = manager.list("file-a", null, null, null, null);
        AttestationListItem listed = page.list.get(0);
        assertThat(listed.metadata.keySet())
                .containsExactly(source, confidence, reviewLabel);
        assertThat(listed.metadata.get(confidence).get(0).type).isEqualTo("literal");
        assertThat(listed.metadata.get(confidence).get(0).datatype)
                .isEqualTo(XSD.DECIMAL.stringValue());
        assertThat(listed.metadata.get(reviewLabel))
                .extracting(value -> value.language).containsExactly("it", "en");
        assertThat(listed.metadata.get(source).get(0).type).isEqualTo("iri");
        assertThat(listed.metadata.get(source).get(0).value)
                .isEqualTo("https://example.org/source/one");

        manager.patchMetadata("file-a", metadataBatch(
                metadataUpdate(attestationA.stringValue(),
                        metadataProperty(confidence))));
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(connection.hasStatement(attestationA, iri(confidence),
                    null, false, graph)).isFalse();
        }
    }

    @Test
    void rejectsWholeMetadataBatchWhenAnAttestationBelongsToAnotherGraph() {
        String property = "https://example.org/vocabulary/status";
        IRI attestationA = iri("https://example.org/attestation/a");
        addPersistedAttestation("file-a", "a", observable, "user7", "A", 0, 1);
        addPersistedAttestation("file-b", "b", observable, "user7", "A", 0, 1);
        AttestationMetadataBatch batch = metadataBatch(
                metadataUpdate(attestationA.stringValue(),
                        metadataProperty(property,
                                metadataLiteral("approved", null, null))),
                metadataUpdate("https://example.org/attestation/b",
                        metadataProperty(property,
                                metadataLiteral("rejected", null, null))));

        assertThatThrownBy(() -> manager.patchMetadata("file-a", batch))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("ATTESTATION_NOT_FOUND");

        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(connection.hasStatement(attestationA, iri(property), null,
                    false, iri(LexicalNamedGraphs.attestationGraphUri("file-a"))))
                    .isFalse();
        }
    }

    @Test
    void rejectsReservedAndInvalidMetadataValues() {
        IRI attestation = iri("https://example.org/attestation/a");
        addPersistedAttestation("file-a", "a", observable, "user7", "A", 0, 1);

        assertThatThrownBy(() -> manager.patchMetadata("file-a", metadataBatch(
                metadataUpdate(attestation.stringValue(),
                        metadataProperty(FRAC + "locus",
                                metadataIri("https://example.org/other-locus"))))))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("RESERVED_METADATA_PROPERTY");

        AttestationMetadataValue invalid = metadataLiteral("value", "it",
                XSD.STRING.stringValue());
        assertThatThrownBy(() -> manager.patchMetadata("file-a", metadataBatch(
                metadataUpdate(attestation.stringValue(),
                        metadataProperty("https://example.org/vocabulary/status",
                                invalid)))))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_METADATA_VALUE");

        AttestationMetadataProperty missingValues =
                new AttestationMetadataProperty();
        missingValues.property = "https://example.org/vocabulary/status";
        assertThatThrownBy(() -> manager.patchMetadata("file-a", metadataBatch(
                metadataUpdate(attestation.stringValue(), missingValues))))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("MISSING_METADATA_VALUES");
    }

    @Test
    void filtersAttestationsByObservableTypeAndAuthorAndPaginates() throws Exception {
        IRI form = iri("https://example.org/lexicon/form");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(form, RDF.TYPE, iri(ONTOLEX + "Form"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        addPersistedAttestation("file-a", "a", observable, "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "b", form, "user8", "😀B", 1, 3);
        addPersistedAttestation("file-a", "c", observable, null, "gli", 4, 7);

        AttestationPage filtered = manager.list("file-a",
                ONTOLEX + "LexicalEntry", "user7", null, null);
        AttestationPage page = manager.list("file-a", null, null, "1", "1");
        AttestationPage withoutAuthorFilter = manager.list("file-a",
                ONTOLEX + "LexicalEntry", null, null, null);

        assertThat(filtered.totalHits).isEqualTo(1);
        assertThat(filtered.list).extracting(item -> item.attestation)
                .containsExactly("https://example.org/attestation/a");
        assertThat(page.totalHits).isEqualTo(3);
        assertThat(page.list).extracting(item -> item.attestation)
                .containsExactly("https://example.org/attestation/b");
        assertThat(withoutAuthorFilter.list).extracting(item -> item.attestation)
                .containsExactly("https://example.org/attestation/a",
                        "https://example.org/attestation/c");
        assertThat(withoutAuthorFilter.list.get(1).creator).isNull();
    }

    @Test
    void resolvesLexicalEntryLabelsIncludingSubclassAndFallbacks() throws Exception {
        IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
        IRI schemaGraph = iri("https://lexo.ilc.cnr.it/graphs/lexical/schema");
        IRI labelledEntry = iri("https://example.org/lexicon/labelled-entry");
        IRI subclassEntry = iri("https://example.org/lexicon/subclass-entry");
        IRI unlabelledEntry = iri("https://example.org/lexicon/unlabelled-entry");
        IRI customEntryType = iri("https://example.org/ontology/CustomEntry");
        IRI labelledForm = iri("https://example.org/lexicon/labelled-entry-form");
        IRI subclassForm = iri("https://example.org/lexicon/subclass-entry-form");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(labelledEntry, RDF.TYPE, iri(ONTOLEX + "LexicalEntry"),
                    lexicalGraph);
            connection.add(labelledEntry, iri(RDFS + "label"),
                    vf.createLiteral("Preferred entry label", "it"), lexicalGraph);
            connection.add(labelledEntry, iri(ONTOLEX + "canonicalForm"),
                    labelledForm, lexicalGraph);
            connection.add(labelledForm, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("ignored canonical form"), lexicalGraph);

            connection.add(customEntryType, iri(RDFS + "subClassOf"),
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
            connection.add(subclassEntry, RDF.TYPE, customEntryType, lexicalGraph);
            connection.add(subclassEntry, iri(ONTOLEX + "canonicalForm"),
                    subclassForm, lexicalGraph);
            connection.add(subclassForm, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("Subclass canonical form", "fr"), lexicalGraph);

            connection.add(unlabelledEntry, RDF.TYPE,
                    iri(ONTOLEX + "LexicalEntry"), lexicalGraph);
        }
        addPersistedAttestation("file-a", "entry-label", labelledEntry,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "entry-subclass", subclassEntry,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "entry-none", unlabelledEntry,
                "user7", "A", 0, 1);

        AttestationPage page = manager.list("file-a", null, null, null, null);

        assertThat(labelFor(page, labelledEntry)).isEqualTo("Preferred entry label@it");
        assertThat(labelFor(page, subclassEntry)).isEqualTo("Subclass canonical form@fr");
        assertThat(labelFor(page, unlabelledEntry)).isEqualTo("no label");
    }

    @Test
    void resolvesFormLabelsInWrittenRepThenRdfsLabelOrder() throws Exception {
        IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
        IRI representedForm = iri("https://example.org/lexicon/represented-form");
        IRI labelledForm = iri("https://example.org/lexicon/rdfs-labelled-form");
        IRI unlabelledForm = iri("https://example.org/lexicon/unlabelled-form");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(representedForm, RDF.TYPE, iri(ONTOLEX + "Form"),
                    lexicalGraph);
            connection.add(representedForm, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("Preferred written representation", "de"),
                    lexicalGraph);
            connection.add(representedForm, iri(RDFS + "label"),
                    vf.createLiteral("ignored form label"), lexicalGraph);
            connection.add(labelledForm, RDF.TYPE, iri(ONTOLEX + "Form"),
                    lexicalGraph);
            connection.add(labelledForm, iri(RDFS + "label"),
                    vf.createLiteral("Form RDFS label", "es"), lexicalGraph);
            connection.add(unlabelledForm, RDF.TYPE, iri(ONTOLEX + "Form"),
                    lexicalGraph);
        }
        addPersistedAttestation("file-a", "form-written", representedForm,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "form-label", labelledForm,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "form-none", unlabelledForm,
                "user7", "A", 0, 1);

        AttestationPage page = manager.list("file-a", null, null, null, null);

        assertThat(labelFor(page, representedForm))
                .isEqualTo("Preferred written representation@de");
        assertThat(labelFor(page, labelledForm)).isEqualTo("Form RDFS label@es");
        assertThat(labelFor(page, unlabelledForm)).isEqualTo("no label");
    }

    @Test
    void resolvesLexicalSenseLabelsFromEntryAndDefinition() throws Exception {
        IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
        IRI labelledEntry = iri("https://example.org/lexicon/sense-entry-label");
        IRI canonicalEntry = iri("https://example.org/lexicon/sense-entry-canonical");
        IRI canonicalForm = iri("https://example.org/lexicon/sense-entry-form");
        IRI labelledSense = iri("https://example.org/lexicon/labelled-sense");
        IRI canonicalSense = iri("https://example.org/lexicon/canonical-sense");
        IRI definitionOnlySense = iri("https://example.org/lexicon/definition-only-sense");
        IRI undefinedSense = iri("https://example.org/lexicon/undefined-sense");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(labelledEntry, iri(RDFS + "label"),
                    vf.createLiteral("Entry label", "it"), lexicalGraph);
            connection.add(canonicalEntry, iri(ONTOLEX + "canonicalForm"),
                    canonicalForm, lexicalGraph);
            connection.add(canonicalForm, iri(ONTOLEX + "writtenRep"),
                    vf.createLiteral("Canonical entry", "fr"), lexicalGraph);
            addSense(connection, lexicalGraph, labelledSense, labelledEntry,
                    "First definition", "en");
            addSense(connection, lexicalGraph, canonicalSense, canonicalEntry,
                    "Second definition", "it");
            addSense(connection, lexicalGraph, definitionOnlySense, null,
                    "Definition without entry label", "de");
            addSense(connection, lexicalGraph, undefinedSense, labelledEntry,
                    null, null);
        }
        addPersistedAttestation("file-a", "sense-label", labelledSense,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "sense-canonical", canonicalSense,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "sense-definition", definitionOnlySense,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "sense-none", undefinedSense,
                "user7", "A", 0, 1);

        AttestationPage page = manager.list("file-a", null, null, null, null);

        assertThat(labelFor(page, labelledSense))
                .isEqualTo("Entry label@it - First definition@en");
        assertThat(labelFor(page, canonicalSense))
                .isEqualTo("Canonical entry@fr - Second definition@it");
        assertThat(labelFor(page, definitionOnlySense))
                .isEqualTo("Definition without entry label@de");
        assertThat(labelFor(page, undefinedSense)).isEqualTo("no label");
    }

    @Test
    void resolvesLexicalConceptLabelsInPrefLabelThenRdfsLabelOrder() throws Exception {
        IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
        IRI preferredConcept = iri("https://example.org/lexicon/preferred-concept");
        IRI labelledConcept = iri("https://example.org/lexicon/labelled-concept");
        IRI unlabelledConcept = iri("https://example.org/lexicon/unlabelled-concept");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(preferredConcept, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), lexicalGraph);
            connection.add(preferredConcept, iri(SKOS + "prefLabel"),
                    vf.createLiteral("Preferred concept label", "it"), lexicalGraph);
            connection.add(preferredConcept, iri(RDFS + "label"),
                    vf.createLiteral("ignored concept label"), lexicalGraph);
            connection.add(labelledConcept, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), lexicalGraph);
            connection.add(labelledConcept, iri(RDFS + "label"),
                    vf.createLiteral("Concept RDFS label", "fr"), lexicalGraph);
            connection.add(unlabelledConcept, RDF.TYPE,
                    iri(ONTOLEX + "LexicalConcept"), lexicalGraph);
        }
        addPersistedAttestation("file-a", "concept-pref", preferredConcept,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "concept-label", labelledConcept,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "concept-none", unlabelledConcept,
                "user7", "A", 0, 1);

        AttestationPage page = manager.list("file-a", null, null, null, null);

        assertThat(labelFor(page, preferredConcept))
                .isEqualTo("Preferred concept label@it");
        assertThat(labelFor(page, labelledConcept)).isEqualTo("Concept RDFS label@fr");
        assertThat(labelFor(page, unlabelledConcept)).isEqualTo("no label");
    }

    @Test
    void validatesAttestationListFiltersAndPagination() {
        assertThatThrownBy(() -> manager.list("file-a", "not an IRI",
                null, null, null))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_IRI");
        assertThatThrownBy(() -> manager.list("file-a", null, null,
                "many", null))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_INTEGER");
        assertThatThrownBy(() -> manager.list("file-a", null, null,
                null, "-1"))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_PAGINATION");
        assertThatThrownBy(() -> manager.list("invalid/file", null, null,
                null, null))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_FILE_ID");
    }

    @Test
    void deletesSelectedThenAllAttestationsByObservableAndGeneratedLoci()
            throws Exception {
        List<Attestation> created = manager.createBatch(observable.stringValue(),
                context.stringValue(), false, "user7", Arrays.asList(
                        new AttestationOccurrence("A", 0, 1),
                        new AttestationOccurrence("gli", 4, 7)));
        IRI firstLocus = iri(created.get(0).locus);
        IRI secondLocus = iri(created.get(1).locus);
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));

        AttestationDeleteByObservableInput selected =
                new AttestationDeleteByObservableInput();
        selected.observable = observable.stringValue();
        selected.attestations = Collections.singletonList(
                created.get(0).attestation);
        AttestationDeletionResult firstResult = manager.deleteByObservable(
                "file-a", selected);

        assertThat(firstResult.deletedCount).isEqualTo(1);
        assertThat(firstResult.deletedLoci).containsExactly(firstLocus.stringValue());
        assertThat(firstResult.retainedLoci).isEmpty();
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(iri(created.get(0).attestation), null,
                    null, false, graph)).isFalse();
            assertThat(lexical.hasStatement(iri(created.get(1).attestation),
                    RDF.TYPE, iri(FRAC + "Attestation"), false, graph)).isTrue();
            assertThat(text.hasStatement(firstLocus, null, null, false,
                    textGraph)).isFalse();
            assertThat(text.hasStatement(secondLocus, RDF.TYPE,
                    iri(NIF + "Phrase"), false, textGraph)).isTrue();
            assertDefaultGraphEmpty(lexical);
            assertDefaultGraphEmpty(text);
        }

        AttestationDeleteByObservableInput all =
                new AttestationDeleteByObservableInput();
        all.observable = observable.stringValue();
        all.all = Boolean.TRUE;
        AttestationDeletionResult allResult = manager.deleteByObservable(
                "file-a", all);

        assertThat(allResult.deletedCount).isEqualTo(1);
        assertThat(allResult.deletedLoci).containsExactly(secondLocus.stringValue());
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, graph)).isFalse();
            assertThat(text.hasStatement(secondLocus, null, null, false,
                    textGraph)).isFalse();
        }
    }

    @Test
    void deletesSelectedThenAllAttestationsByLocusAndRemovesItWhenOrphaned()
            throws Exception {
        IRI form = iri("https://example.org/lexicon/form-delete");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(form, RDF.TYPE, iri(ONTOLEX + "Form"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        List<Attestation> created = manager.createByLocus(context.stringValue(),
                false, "user7", new AttestationByLocusInput("A", 0, 1,
                        Arrays.asList(observable.stringValue(), form.stringValue())));
        IRI locus = iri(created.get(0).locus);
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));

        AttestationDeleteByLocusInput selected =
                new AttestationDeleteByLocusInput();
        selected.locus = locus.stringValue();
        selected.attestations = Collections.singletonList(
                created.get(0).attestation);
        AttestationDeletionResult firstResult = manager.deleteByLocus(
                "file-a", selected);

        assertThat(firstResult.deletedCount).isEqualTo(1);
        assertThat(firstResult.deletedLoci).isEmpty();
        assertThat(firstResult.retainedLoci).containsExactly(locus.stringValue());
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(iri(created.get(1).attestation),
                    RDF.TYPE, iri(FRAC + "Attestation"), false, graph)).isTrue();
            assertThat(text.hasStatement(locus, RDF.TYPE, iri(NIF + "Phrase"),
                    false, textGraph)).isTrue();
        }

        AttestationDeleteByLocusInput all = new AttestationDeleteByLocusInput();
        all.locus = locus.stringValue();
        all.all = Boolean.TRUE;
        AttestationDeletionResult allResult = manager.deleteByLocus("file-a", all);

        assertThat(allResult.deletedCount).isEqualTo(1);
        assertThat(allResult.deletedLoci).containsExactly(locus.stringValue());
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, graph)).isFalse();
            assertThat(text.hasStatement(locus, null, null, false,
                    textGraph)).isFalse();
        }
    }

    @Test
    void keepsOrphanLocusWhenItWasReusedWithoutGenerationMarker()
            throws Exception {
        IRI locus = iri("https://example.org/text/interview#char=0,1");
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(locus, RDF.TYPE, iri(NIF + "Phrase"), textGraph);
            connection.add(locus, RDF.TYPE, iri(NIF + "RFC5147String"), textGraph);
            connection.add(locus, iri(NIF + "anchorOf"),
                    vf.createLiteral("A", "it"), textGraph);
            connection.add(locus, iri(NIF + "beginIndex"),
                    vf.createLiteral("0", XSD.NON_NEGATIVE_INTEGER), textGraph);
            connection.add(locus, iri(NIF + "endIndex"),
                    vf.createLiteral("1", XSD.NON_NEGATIVE_INTEGER), textGraph);
            connection.add(locus, iri(NIF + "referenceContext"), context,
                    textGraph);
        }
        Attestation created = manager.create(observable.stringValue(), "A", "0",
                "1", context.stringValue(), false, "user7");
        AttestationDeleteByLocusInput deletion = new AttestationDeleteByLocusInput();
        deletion.locus = locus.stringValue();
        deletion.all = Boolean.TRUE;

        AttestationDeletionResult result = manager.deleteByLocus("file-a", deletion);

        assertThat(result.deletedCount).isEqualTo(1);
        assertThat(result.deleted.get(0).attestation).isEqualTo(created.attestation);
        assertThat(result.deletedLoci).isEmpty();
        assertThat(result.retainedLoci).containsExactly(locus.stringValue());
        try (RepositoryConnection text = textRepository.getConnection()) {
            assertThat(text.hasStatement(locus, RDF.TYPE, iri(NIF + "Phrase"),
                    false, textGraph)).isTrue();
            assertThat(text.hasStatement(locus, iri(PROV + "wasGeneratedBy"),
                    null, false, textGraph)).isFalse();
        }
    }

    @Test
    void rejectsInvalidDeletionBatchBeforeRemovingAnyAttestation() {
        addPersistedAttestation("file-a", "delete-a", observable, "user7",
                "A", 0, 1);
        IRI form = iri("https://example.org/lexicon/delete-form");
        addPersistedAttestation("file-a", "delete-b", form, "user7",
                "A", 0, 1);
        AttestationDeleteByObservableInput deletion =
                new AttestationDeleteByObservableInput();
        deletion.observable = observable.stringValue();
        deletion.attestations = Arrays.asList(
                "https://example.org/attestation/delete-a",
                "https://example.org/attestation/delete-b");

        assertThatThrownBy(() -> manager.deleteByObservable("file-a", deletion))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("ATTESTATION_OBSERVABLE_MISMATCH");

        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            assertThat(count(lexical, null, RDF.TYPE,
                    iri(FRAC + "Attestation"), graph)).isEqualTo(2);
        }
    }

    private void addPersistedAttestation(String fileId, String suffix,
                                         IRI observed, String author, String value,
                                         int start, int end) {
        IRI attestation = iri("https://example.org/attestation/" + suffix);
        IRI locus = iri("https://example.org/text/" + fileId + "#char="
                + start + "," + end);
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri(fileId));
        IRI locusGraph = iri(TEXT_GRAPH_BASE + fileId);
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(attestation, RDF.TYPE, iri(FRAC + "Attestation"),
                    attestationGraph);
            connection.add(observed, iri(FRAC + "attestation"), attestation,
                    attestationGraph);
            connection.add(attestation, DCTERMS.DESCRIPTION,
                    vf.createLiteral("Description " + suffix), attestationGraph);
            connection.add(attestation, DCTERMS.CREATED,
                    vf.createLiteral("2026-07-27T10:00:00.000+02:00"), attestationGraph);
            connection.add(attestation, DCTERMS.MODIFIED,
                    vf.createLiteral("2026-07-27T10:00:00.000+02:00"), attestationGraph);
            if (author != null) {
                connection.add(attestation, DCTERMS.CREATOR,
                        vf.createLiteral(author), attestationGraph);
            }
            connection.add(attestation, RDF.VALUE, vf.createLiteral(value),
                    attestationGraph);
            connection.add(attestation, iri(FRAC + "locus"), locus,
                    attestationGraph);
            connection.add(attestation, iri(FRAC + "observedIn"), context,
                    attestationGraph);
        }
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(locus, RDF.TYPE, iri(NIF + "Phrase"), locusGraph);
            connection.add(locus, RDF.TYPE, iri(NIF + "RFC5147String"), locusGraph);
            connection.add(locus, iri(NIF + "anchorOf"),
                    vf.createLiteral(value, "it"), locusGraph);
            connection.add(locus, iri(NIF + "beginIndex"),
                    vf.createLiteral(Integer.toString(start), XSD.NON_NEGATIVE_INTEGER),
                    locusGraph);
            connection.add(locus, iri(NIF + "endIndex"),
                    vf.createLiteral(Integer.toString(end), XSD.NON_NEGATIVE_INTEGER),
                    locusGraph);
            connection.add(locus, iri(NIF + "referenceContext"), context, locusGraph);
        }
    }

    private void addSense(RepositoryConnection connection, Resource lexicalGraph,
                          IRI sense, IRI entry, String definition, String language) {
        connection.add(sense, RDF.TYPE, iri(ONTOLEX + "LexicalSense"), lexicalGraph);
        if (entry != null) {
            connection.add(sense, iri(ONTOLEX + "isSenseOf"), entry, lexicalGraph);
        }
        if (definition != null) {
            connection.add(sense, iri(SKOS + "definition"),
                    language == null ? vf.createLiteral(definition)
                            : vf.createLiteral(definition, language), lexicalGraph);
        }
    }

    private AttestationMetadataBatch metadataBatch(
            AttestationMetadataUpdate... updates) {
        AttestationMetadataBatch result = new AttestationMetadataBatch();
        result.updates = Arrays.asList(updates);
        return result;
    }

    private AttestationMetadataUpdate metadataUpdate(String attestation,
                                                      AttestationMetadataProperty... properties) {
        AttestationMetadataUpdate result = new AttestationMetadataUpdate();
        result.attestation = attestation;
        result.properties = Arrays.asList(properties);
        return result;
    }

    private AttestationMetadataProperty metadataProperty(String property,
                                                          AttestationMetadataValue... values) {
        AttestationMetadataProperty result = new AttestationMetadataProperty();
        result.property = property;
        result.values = Arrays.asList(values);
        return result;
    }

    private AttestationMetadataValue metadataLiteral(String value, String language,
                                                      String datatype) {
        return new AttestationMetadataValue(value, "literal", language, datatype);
    }

    private AttestationMetadataValue metadataIri(String value) {
        return new AttestationMetadataValue(value, "iri", null, null);
    }

    private String labelFor(AttestationPage page, IRI observed) {
        for (AttestationListItem item : page.list) {
            if (observed.stringValue().equals(item.observable)) {
                return item.observableLabel;
            }
        }
        return null;
    }

    private void assertDefaultGraphEmpty(RepositoryConnection connection) {
        try (RepositoryResult<org.eclipse.rdf4j.model.Statement> statements =
                     connection.getStatements(null, null, null, false, (Resource) null)) {
            assertThat(statements.hasNext()).isFalse();
        }
    }

    private int count(RepositoryConnection connection, Resource subject,
                      IRI predicate, org.eclipse.rdf4j.model.Value object,
                      Resource graph) {
        int count = 0;
        try (RepositoryResult<org.eclipse.rdf4j.model.Statement> statements =
                     connection.getStatements(subject, predicate, object, false, graph)) {
            while (statements.hasNext()) {
                statements.next();
                count++;
            }
        }
        return count;
    }

    private IRI iri(String value) {
        return vf.createIRI(value);
    }
}
