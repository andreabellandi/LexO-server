package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.service.data.attestation.AttestationMetadataValue;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByLocusInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationDeleteByObservableInput;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationFilter;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationLocusUpdate;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationObservableUpdate;
import it.cnr.ilc.lexo.service.data.attestation.output.Attestation;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationDeletionResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationListItem;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationLocusUpdateResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationObservableUpdateResult;
import it.cnr.ilc.lexo.service.data.attestation.output.AttestationPage;
import it.cnr.ilc.lexo.service.data.attestation.input.AttestationOccurrence;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
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
    void createsUniqueAttestationIrisWhenTheClockDoesNotAdvance()
            throws Exception {
        manager = new AttestationManager(lexicalRepository, textRepository,
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        return 1700000000000L;
                    }
                });

        Attestation first = manager.create(observable.stringValue(), "A", "0",
                "1", context.stringValue(), false, "user7");
        Attestation second = manager.create(observable.stringValue(), "gli", "4",
                "7", context.stringValue(), false, "user7");

        assertThat(first.attestation).isNotEqualTo(second.attestation);
        assertThat(first.creationDate).isNotEqualTo(second.creationDate);
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(count(connection, null, RDF.TYPE,
                    iri(FRAC + "Attestation"), graph)).isEqualTo(2);
            assertThat(connection.hasStatement(iri(first.attestation), RDF.TYPE,
                    iri(FRAC + "Attestation"), false, graph)).isTrue();
            assertThat(connection.hasStatement(iri(second.attestation), RDF.TYPE,
                    iri(FRAC + "Attestation"), false, graph)).isTrue();
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
            IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
            assertThat(connection.hasStatement(iri(result.attestation),
                    iri(FRAC + "observedIn"), corpus, false,
                    graph)).isTrue();
            assertFrequency(connection, observable, context, graph, 1);
            assertNoFrequency(connection, observable, corpus, graph);
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
        assertThat(results).extracting(item -> item.frequency).containsOnly(2);
        assertThat(results).extracting(item -> item.attestation).doesNotHaveDuplicates();
        assertThat(new ObjectMapper().writeValueAsString(results))
                .doesNotContain("\"description\"");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(count(lexical, null, RDF.TYPE, iri(FRAC + "Attestation"),
                    attestationGraph)).isEqualTo(2);
            assertFrequency(lexical, observable, context, attestationGraph, 2);
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
    void incrementsAnExistingPerTextFrequencyOnCreation() throws Exception {
        Attestation first = manager.create(observable.stringValue(), "A", "0",
                "1", context.stringValue(), false, "user7");
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            Resource frequency = frequencyResource(lexical, observable, context,
                    graph);
            lexical.remove(frequency, RDF.VALUE, null, graph);
            lexical.add(frequency, RDF.VALUE, vf.createLiteral("7", XSD.INT), graph);
        }

        Attestation second = manager.create(observable.stringValue(), "gli", "4",
                "7", context.stringValue(), false, "user7");

        assertThat(first.frequency).isEqualTo(1);
        assertThat(second.frequency).isEqualTo(8);
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            assertFrequency(lexical, observable, context, graph, 8);
            assertThat(count(lexical, observable, iri(FRAC + "frequency"), null,
                    graph)).isEqualTo(1);
            assertDefaultGraphEmpty(lexical);
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
        String source = "https://example.org/vocabulary/source";
        String confidence = "https://example.org/vocabulary/confidence";
        input.observables.get(0).metadata = Collections.singletonList(
                metadataProperty(source,
                        new RdfMetadataValue("https://example.org/source/1",
                                "iri", null, null),
                        new RdfMetadataValue("fonte primaria", "literal", "it",
                                null)));
        input.observables.get(1).metadata = Collections.singletonList(
                metadataProperty(confidence,
                        new RdfMetadataValue("0.92", "literal", null,
                                XSD.DECIMAL.stringValue())));
        ObjectMapper mapper = new ObjectMapper();
        input = mapper.readValue(mapper.writeValueAsString(input),
                AttestationByLocusInput.class);

        List<Attestation> results = manager.createByLocus(context.stringValue(),
                false, "user7", input);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(item -> item.frequency).containsOnly(1);
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
            assertFrequency(lexical, observable, context, attestationGraph, 1);
            assertFrequency(lexical, form, context, attestationGraph, 1);
            assertThat(lexical.hasStatement(observable, iri(FRAC + "attestation"),
                    iri(results.get(0).attestation), false, attestationGraph)).isTrue();
            assertThat(lexical.hasStatement(form, iri(FRAC + "attestation"),
                    iri(results.get(1).attestation), false, attestationGraph)).isTrue();
            assertThat(lexical.hasStatement(iri(results.get(0).attestation),
                    iri(source), iri("https://example.org/source/1"), false,
                    attestationGraph)).isTrue();
            assertThat(lexical.hasStatement(iri(results.get(0).attestation),
                    iri(source), vf.createLiteral("fonte primaria", "it"), false,
                    attestationGraph)).isTrue();
            assertThat(lexical.hasStatement(iri(results.get(1).attestation),
                    iri(confidence), vf.createLiteral("0.92", XSD.DECIMAL), false,
                    attestationGraph)).isTrue();
            assertThat(lexical.hasStatement(iri(results.get(1).attestation),
                    iri(source), null, false, attestationGraph)).isFalse();
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
        assertThat(results.get(0).metadata).containsOnlyKeys(source);
        assertThat(results.get(0).metadata.get(source)).hasSize(2);
        assertThat(results.get(1).metadata).containsOnlyKeys(confidence);
        assertThat(results.get(1).metadata.get(confidence)).singleElement()
                .satisfies(item -> {
                    assertThat(item.type).isEqualTo("literal");
                    assertThat(item.value).isEqualTo("0.92");
                    assertThat(item.datatype).isEqualTo(XSD.DECIMAL.stringValue());
                });
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
        AttestationByLocusInput protectedMetadata =
                new AttestationByLocusInput("A", 0, 1,
                        Arrays.asList(observable.stringValue(),
                                observable.stringValue()));
        protectedMetadata.observables.get(0).metadata = Collections.singletonList(
                metadataProperty("https://example.org/vocabulary/source",
                        new RdfMetadataValue("valid", "literal", null, null)));
        protectedMetadata.observables.get(1).metadata = Collections.singletonList(
                metadataProperty(FRAC + "gloss",
                        new RdfMetadataValue("forbidden", "literal", null, null)));
        assertThatThrownBy(() -> manager.createByLocus(context.stringValue(),
                false, "user7", protectedMetadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESERVED_METADATA_PROPERTY");

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
        addFrequency("file-a", observable, context, 2);
        addFrequency("file-a", form, context, 1);
        addFrequency("file-b", observable, context, 1);

        AttestationPage page = manager.list("file-a", null, null, null, null);

        assertThat(page.totalHits).isEqualTo(3);
        assertThat(page.limit).isEqualTo(50);
        assertThat(page.offset).isZero();
        assertThat(page.list).extracting(item -> item.attestation)
                .containsExactly("https://example.org/attestation/a",
                        "https://example.org/attestation/b",
                        "https://example.org/attestation/c");
        AttestationListItem first = page.list.get(0);
        assertThat(first.observable).isEqualTo(observable.stringValue());
        assertThat(first.observableTypes).contains(ONTOLEX + "LexicalEntry");
        assertThat(first.frequency).isEqualTo(2);
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
    void listsOneObservableAcrossTextGraphsAndPaginates() throws Exception {
        IRI secondTextGraph = iri(TEXT_GRAPH_BASE + "file-b");
        try (RepositoryConnection connection = textRepository.getConnection()) {
            connection.add(context, iri(NIFS + "fileId"),
                    vf.createLiteral("file-b"), secondTextGraph);
        }
        addPersistedAttestation("file-a", "observable-a", observable,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-b", "observable-b", observable,
                "user8", "A", 0, 1);
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            IRI foreignAttestation = iri("https://example.org/attestation/foreign");
            IRI malformedFamilyGraph = iri(
                    LexicalNamedGraphs.attestationGraphBaseUri() + "file-a/extra");
            connection.add(observable, iri(FRAC + "attestation"),
                    foreignAttestation, malformedFamilyGraph);
            connection.add(foreignAttestation, RDF.TYPE,
                    iri(FRAC + "Attestation"), malformedFamilyGraph);
        }

        AttestationPage page = manager.listByObservable(observable.stringValue(),
                null, "1", "1");
        AttestationPage defaultPage = manager.listByObservable(
                observable.stringValue(), null, null, null);

        assertThat(page.totalHits).isEqualTo(2);
        assertThat(page.limit).isEqualTo(1);
        assertThat(page.offset).isEqualTo(1);
        assertThat(page.list).hasSize(1);
        assertThat(page.list.get(0).attestation)
                .isEqualTo("https://example.org/attestation/observable-b");
        assertThat(page.list.get(0).fileId).isEqualTo("file-b");
        assertThat(defaultPage.limit).isEqualTo(50);
    }

    @Test
    void appliesNestedCreatorTextMetadataAndObservableTypeFilters() throws Exception {
        IRI form = iri("https://example.org/lexicon/filter-form");
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            lexical.add(form, RDF.TYPE, iri(ONTOLEX + "Form"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        try (RepositoryConnection text = textRepository.getConnection()) {
            text.add(context, DCTERMS.TITLE,
                    vf.createLiteral("Intervista", "it"), textGraph);
        }
        addPersistedAttestation("file-a", "filter-a", observable,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "filter-b", form,
                "user8", "A", 0, 1);
        addPersistedAttestation("file-a", "filter-c", observable,
                "user9", "A", 0, 1);

        AttestationFilter creators = filterGroup("OR",
                stringFilter("creator", "user7"),
                stringFilter("creator", "user8"));
        AttestationFilter metadata = metadataFilter(DCTERMS.TITLE.stringValue(),
                metadataLiteral("Intervista", "it", null));
        AttestationFilter types = stringFilter("observableType",
                ONTOLEX + "LexicalEntry", ONTOLEX + "Form");
        AttestationFilter filter = filterGroup("AND", creators, metadata, types);

        AttestationPage page = manager.list("file-a", null, null, filter,
                null, null);

        assertThat(page.totalHits).isEqualTo(2);
        assertThat(page.list).extracting(item -> item.attestation)
                .containsExactly("https://example.org/attestation/filter-a",
                        "https://example.org/attestation/filter-b");
    }

    @Test
    void filtersLexicalEntrySubclassesAndExactRdfTextMetadata() throws Exception {
        IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
        IRI schemaGraph = iri("https://lexo.ilc.cnr.it/graphs/lexical/schema");
        IRI customType = iri("https://example.org/ontology/SpecialEntry");
        IRI customEntry = iri("https://example.org/lexicon/special-entry");
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            lexical.add(customType, iri(RDFS + "subClassOf"),
                    iri(ONTOLEX + "LexicalEntry"), schemaGraph);
            lexical.add(customEntry, RDF.TYPE, customType, lexicalGraph);
        }
        try (RepositoryConnection text = textRepository.getConnection()) {
            text.add(context, DCTERMS.TITLE,
                    vf.createLiteral("Titolo", "it"), textGraph);
        }
        addPersistedAttestation("file-a", "subclass", customEntry,
                "user7", "A", 0, 1);

        AttestationFilter matching = filterGroup("AND",
                stringFilter("observableType", ONTOLEX + "LexicalEntry"),
                metadataFilter(DCTERMS.TITLE.stringValue(),
                        metadataLiteral("Titolo", "it", null)));
        AttestationFilter wrongLanguage = metadataFilter(
                DCTERMS.TITLE.stringValue(),
                metadataLiteral("Titolo", "en", null));

        assertThat(manager.list("file-a", null, null, matching,
                null, null).list).extracting(item -> item.observable)
                .containsExactly(customEntry.stringValue());
        assertThat(manager.list("file-a", null, null, wrongLanguage,
                null, null).totalHits).isZero();
        assertThat(manager.listByObservable(customEntry.stringValue(), matching,
                null, null).totalHits).isEqualTo(1);
    }

    @Test
    void filtersAllSupportedOntolexObservableCategoriesInOr() throws Exception {
        IRI form = iri("https://example.org/lexicon/category-form");
        IRI sense = iri("https://example.org/lexicon/category-sense");
        IRI concept = iri("https://example.org/lexicon/category-concept");
        IRI lexicalGraph = iri(LexicalNamedGraphs.lexiconGraphUri());
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(form, RDF.TYPE, iri(ONTOLEX + "Form"), lexicalGraph);
            connection.add(sense, RDF.TYPE, iri(ONTOLEX + "LexicalSense"),
                    lexicalGraph);
            connection.add(concept, RDF.TYPE, iri(ONTOLEX + "LexicalConcept"),
                    lexicalGraph);
        }
        addPersistedAttestation("file-a", "category-entry", observable,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "category-form", form,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "category-sense", sense,
                "user7", "A", 0, 1);
        addPersistedAttestation("file-a", "category-concept", concept,
                "user7", "A", 0, 1);
        AttestationFilter filter = stringFilter("observableType",
                ONTOLEX + "LexicalEntry", ONTOLEX + "Form",
                ONTOLEX + "LexicalSense", ONTOLEX + "LexicalConcept");

        AttestationPage page = manager.list("file-a", null, null, filter,
                null, null);

        assertThat(page.totalHits).isEqualTo(4);
        assertThat(page.list).extracting(item -> item.observable)
                .containsExactlyInAnyOrder(observable.stringValue(),
                        form.stringValue(), sense.stringValue(), concept.stringValue());
    }

    @Test
    void validatesBooleanAttestationFilters() {
        AttestationFilter emptyGroup = new AttestationFilter();
        emptyGroup.operator = "AND";
        emptyGroup.filters = Collections.emptyList();
        AttestationFilter invalidMetadata = new AttestationFilter();
        invalidMetadata.operator = "EQ";
        invalidMetadata.field = "textMetadata";
        invalidMetadata.property = "not an IRI";
        invalidMetadata.rdfValues = Collections.singletonList(
                metadataLiteral("value", null, null));

        assertThatThrownBy(() -> manager.list("file-a", null, null,
                emptyGroup, null, null))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_FILTER");
        assertThatThrownBy(() -> manager.list("file-a", null, null,
                invalidMetadata, null, null))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_IRI");
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
    void updatesGeneratedUnsharedLocusUsingUnicodeCodePointOffsets()
            throws Exception {
        Attestation created = manager.create(observable.stringValue(), "A", "0",
                "1", context.stringValue(), false, "user7");
        AttestationLocusUpdate update = new AttestationLocusUpdate();
        update.attestation = created.attestation;
        update.start = Integer.valueOf(1);
        update.end = Integer.valueOf(3);

        AttestationLocusUpdateResult result = manager.updateLocus("file-a", update);

        IRI attestation = iri(created.attestation);
        IRI oldLocus = iri(created.locus);
        IRI newLocus = iri("https://example.org/text/interview#char=1,3");
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        assertThat(result.previousLocus).isEqualTo(oldLocus.stringValue());
        assertThat(result.locus).isEqualTo(newLocus.stringValue());
        assertThat(result.value).isEqualTo("😀B");
        assertThat(result.glossUpdated).isTrue();
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(attestation, iri(FRAC + "locus"),
                    newLocus, false, graph)).isTrue();
            assertThat(lexical.hasStatement(attestation, RDF.VALUE,
                    vf.createLiteral("😀B", "it"), false, graph)).isTrue();
            assertThat(lexical.hasStatement(attestation, iri(FRAC + "gloss"),
                    vf.createLiteral("😀B", "it"), false, graph)).isTrue();
            assertThat(text.hasStatement(oldLocus, null, null, false,
                    textGraph)).isFalse();
            assertThat(text.hasStatement(newLocus, iri(NIF + "anchorOf"),
                    vf.createLiteral("😀B", "it"), false, textGraph)).isTrue();
            assertThat(text.hasStatement(newLocus, iri(NIF + "beginIndex"),
                    vf.createLiteral("1", XSD.NON_NEGATIVE_INTEGER), false,
                    textGraph)).isTrue();
            assertThat(text.hasStatement(newLocus, iri(NIF + "endIndex"),
                    vf.createLiteral("3", XSD.NON_NEGATIVE_INTEGER), false,
                    textGraph)).isTrue();
            assertThat(text.hasStatement(newLocus,
                    iri(PROV + "wasGeneratedBy"),
                    iri("https://lexo.ilc.cnr.it#AttestationService"), false,
                    textGraph)).isTrue();
            assertDefaultGraphEmpty(lexical);
            assertDefaultGraphEmpty(text);
        }
    }

    @Test
    void updatesLocusValueButPreservesGlossWhenRequested() throws Exception {
        Attestation created = manager.create(observable.stringValue(), "A", "0",
                "1", context.stringValue(), false, "user7");
        AttestationLocusUpdate update = new AttestationLocusUpdate();
        update.attestation = created.attestation;
        update.start = Integer.valueOf(1);
        update.end = Integer.valueOf(3);
        update.updateGloss = Boolean.FALSE;

        AttestationLocusUpdateResult result = manager.updateLocus("file-a", update);

        IRI attestation = iri(created.attestation);
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        assertThat(result.glossUpdated).isFalse();
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            assertThat(lexical.hasStatement(attestation, RDF.VALUE,
                    vf.createLiteral("😀B", "it"), false, graph)).isTrue();
            assertThat(lexical.hasStatement(attestation, iri(FRAC + "gloss"),
                    vf.createLiteral("A", "it"), false, graph)).isTrue();
            assertThat(lexical.hasStatement(attestation, iri(FRAC + "gloss"),
                    vf.createLiteral("😀B", "it"), false, graph)).isFalse();
        }
    }

    @Test
    void rejectsSharedOrNonGeneratedLocusWithoutChangingAnything()
            throws Exception {
        IRI form = iri("https://example.org/lexicon/shared-form");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(form, RDF.TYPE, iri(ONTOLEX + "Form"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        List<Attestation> shared = manager.createByLocus(context.stringValue(),
                false, "user7", new AttestationByLocusInput("A", 0, 1,
                        Arrays.asList(observable.stringValue(), form.stringValue())));
        AttestationLocusUpdate update = new AttestationLocusUpdate();
        update.attestation = shared.get(0).attestation;
        update.start = Integer.valueOf(1);
        update.end = Integer.valueOf(3);

        assertThatThrownBy(() -> manager.updateLocus("file-a", update))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("LOCUS_NOT_MODIFIABLE")
                .hasMessageContaining("shared");

        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        IRI oldLocus = iri(shared.get(0).locus);
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(iri(shared.get(0).attestation),
                    iri(FRAC + "locus"), oldLocus, false, graph)).isTrue();
            assertThat(text.hasStatement(oldLocus, iri(NIF + "anchorOf"),
                    vf.createLiteral("A", "it"), false, textGraph)).isTrue();
        }

        IRI importedLocus = iri("https://example.org/text/interview#char=4,7");
        try (RepositoryConnection text = textRepository.getConnection()) {
            text.add(importedLocus, RDF.TYPE, iri(NIF + "Phrase"), textGraph);
            text.add(importedLocus, iri(NIF + "anchorOf"),
                    vf.createLiteral("gli", "it"), textGraph);
            text.add(importedLocus, iri(NIF + "beginIndex"),
                    vf.createLiteral("4", XSD.NON_NEGATIVE_INTEGER), textGraph);
            text.add(importedLocus, iri(NIF + "endIndex"),
                    vf.createLiteral("7", XSD.NON_NEGATIVE_INTEGER), textGraph);
            text.add(importedLocus, iri(NIF + "referenceContext"), context,
                    textGraph);
        }
        Attestation imported = manager.create(observable.stringValue(), "gli", "4",
                "7", context.stringValue(), false, "user7");
        update.attestation = imported.attestation;

        assertThatThrownBy(() -> manager.updateLocus("file-a", update))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("LOCUS_NOT_MODIFIABLE")
                .hasMessageContaining("not generated");
    }

    @Test
    void atomicallyUpdatesObservableForOneOrMoreAttestations() throws Exception {
        List<Attestation> created = manager.createBatch(observable.stringValue(),
                context.stringValue(), false, "user7", Arrays.asList(
                        new AttestationOccurrence("A", 0, 1),
                        new AttestationOccurrence("gli", 4, 7)));
        IRI replacement = iri("https://example.org/lexicon/replacement-form");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(replacement, RDF.TYPE, iri(ONTOLEX + "Form"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        AttestationObservableUpdate update = new AttestationObservableUpdate();
        update.observable = replacement.stringValue();
        update.attestations = Arrays.asList(created.get(0).attestation,
                created.get(1).attestation);

        AttestationObservableUpdateResult result = manager.updateObservable(
                "file-a", update);

        assertThat(result.updated).hasSize(2);
        assertThat(result.frequencies).containsEntry(observable.stringValue(), 0)
                .containsEntry(replacement.stringValue(), 2);
        assertThat(result.updated.get(0).previousObservables)
                .containsExactly(observable.stringValue());
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            for (Attestation item : created) {
                IRI attestation = iri(item.attestation);
                assertThat(lexical.hasStatement(observable,
                        iri(FRAC + "attestation"), attestation, false,
                        graph)).isFalse();
                assertThat(lexical.hasStatement(replacement,
                        iri(FRAC + "attestation"), attestation, false,
                        graph)).isTrue();
                assertThat(lexical.hasStatement(attestation, DCTERMS.MODIFIED,
                        vf.createLiteral(result.updated.get(0).lastUpdate), false,
                        graph)).isTrue();
            }
            assertNoFrequency(lexical, observable, context, graph);
            assertFrequency(lexical, replacement, context, graph, 2);
            assertDefaultGraphEmpty(lexical);
        }
    }

    @Test
    void rejectsInvalidObservableBatchBeforeChangingAnyLink() throws Exception {
        Attestation created = manager.create(observable.stringValue(), "A", "0",
                "1", context.stringValue(), false, "user7");
        IRI replacement = iri("https://example.org/lexicon/replacement-sense");
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            connection.add(replacement, RDF.TYPE, iri(ONTOLEX + "LexicalSense"),
                    iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        AttestationObservableUpdate update = new AttestationObservableUpdate();
        update.observable = replacement.stringValue();
        update.attestations = Arrays.asList(created.attestation,
                "https://example.org/attestation/missing");

        assertThatThrownBy(() -> manager.updateObservable("file-a", update))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("ATTESTATION_NOT_FOUND");

        IRI attestation = iri(created.attestation);
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            assertThat(lexical.hasStatement(observable,
                    iri(FRAC + "attestation"), attestation, false,
                    graph)).isTrue();
            assertThat(lexical.hasStatement(replacement,
                    iri(FRAC + "attestation"), attestation, false,
                    graph)).isFalse();
        }
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
        assertThat(firstResult.frequencies)
                .containsEntry(observable.stringValue(), 1);
        assertThat(firstResult.deletedLoci).containsExactly(firstLocus.stringValue());
        assertThat(firstResult.retainedLoci).isEmpty();
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(iri(created.get(0).attestation), null,
                    null, false, graph)).isFalse();
            assertThat(lexical.hasStatement(iri(created.get(1).attestation),
                    RDF.TYPE, iri(FRAC + "Attestation"), false, graph)).isTrue();
            assertFrequency(lexical, observable, context, graph, 1);
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
        assertThat(allResult.frequencies)
                .containsEntry(observable.stringValue(), 0);
        assertThat(allResult.deletedLoci).containsExactly(secondLocus.stringValue());
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, graph)).isFalse();
            assertNoFrequency(lexical, observable, context, graph);
            assertThat(text.hasStatement(secondLocus, null, null, false,
                    textGraph)).isFalse();
        }
    }

    @Test
    void removesAStaleFrequencyWhenDeletingAllByObservable() throws Exception {
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        addFrequency("file-a", observable, context, 4);
        AttestationDeleteByObservableInput deletion =
                new AttestationDeleteByObservableInput();
        deletion.observable = observable.stringValue();
        deletion.all = Boolean.TRUE;

        AttestationDeletionResult result = manager.deleteByObservable(
                "file-a", deletion);

        assertThat(result.deletedCount).isZero();
        assertThat(result.frequencies).containsEntry(observable.stringValue(), 0);
        try (RepositoryConnection lexical = lexicalRepository.getConnection()) {
            assertNoFrequency(lexical, observable, context, graph);
            assertDefaultGraphEmpty(lexical);
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
        assertThat(firstResult.frequencies)
                .containsEntry(observable.stringValue(), 0);
        assertThat(firstResult.deletedLoci).isEmpty();
        assertThat(firstResult.retainedLoci).containsExactly(locus.stringValue());
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(iri(created.get(1).attestation),
                    RDF.TYPE, iri(FRAC + "Attestation"), false, graph)).isTrue();
            assertNoFrequency(lexical, observable, context, graph);
            assertFrequency(lexical, form, context, graph, 1);
            assertThat(text.hasStatement(locus, RDF.TYPE, iri(NIF + "Phrase"),
                    false, textGraph)).isTrue();
        }

        AttestationDeleteByLocusInput all = new AttestationDeleteByLocusInput();
        all.locus = locus.stringValue();
        all.all = Boolean.TRUE;
        AttestationDeletionResult allResult = manager.deleteByLocus("file-a", all);

        assertThat(allResult.deletedCount).isEqualTo(1);
        assertThat(allResult.frequencies).containsEntry(form.stringValue(), 0);
        assertThat(allResult.deletedLoci).containsExactly(locus.stringValue());
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(lexical.hasStatement(null, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, graph)).isFalse();
            assertNoFrequency(lexical, form, context, graph);
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

    private void addFrequency(String fileId, IRI observed, IRI observedIn,
                              int value) {
        IRI graph = iri(LexicalNamedGraphs.attestationGraphUri(fileId));
        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            Resource frequency = vf.createBNode();
            connection.add(observed, iri(FRAC + "frequency"), frequency, graph);
            connection.add(frequency, RDF.TYPE, iri(FRAC + "Frequency"), graph);
            connection.add(frequency, RDF.VALUE,
                    vf.createLiteral(Integer.toString(value), XSD.INT), graph);
            connection.add(frequency, iri(FRAC + "observedIn"), observedIn, graph);
        }
    }

    private void assertFrequency(RepositoryConnection connection, IRI observed,
                                 IRI observedIn, Resource graph, int expected) {
        Resource frequency = frequencyResource(connection, observed, observedIn,
                graph);
        assertThat(connection.hasStatement(frequency, RDF.TYPE,
                iri(FRAC + "Frequency"), false, graph)).isTrue();
        assertThat(connection.hasStatement(frequency, RDF.VALUE,
                vf.createLiteral(Integer.toString(expected), XSD.INT), false,
                graph)).isTrue();
        assertThat(connection.hasStatement(frequency, iri(FRAC + "observedIn"),
                observedIn, false, graph)).isTrue();
        assertThat(connection.hasStatement(observed, iri(FRAC + "frequency"),
                frequency, false, graph)).isTrue();
    }

    private void assertNoFrequency(RepositoryConnection connection, IRI observed,
                                   IRI observedIn, Resource graph) {
        assertThat(frequencyResources(connection, observed, observedIn, graph))
                .isEmpty();
    }

    private Resource frequencyResource(RepositoryConnection connection,
                                       IRI observed, IRI observedIn,
                                       Resource graph) {
        List<Resource> resources = frequencyResources(connection, observed,
                observedIn, graph);
        assertThat(resources).hasSize(1);
        return resources.get(0);
    }

    private List<Resource> frequencyResources(RepositoryConnection connection,
                                              IRI observed, IRI observedIn,
                                              Resource graph) {
        List<Resource> result = new java.util.ArrayList<Resource>();
        try (RepositoryResult<org.eclipse.rdf4j.model.Statement> statements =
                     connection.getStatements(observed, iri(FRAC + "frequency"),
                             null, false, graph)) {
            while (statements.hasNext()) {
                org.eclipse.rdf4j.model.Value value = statements.next().getObject();
                if (value instanceof Resource
                        && connection.hasStatement((Resource) value,
                                iri(FRAC + "observedIn"), observedIn, false,
                                graph)) {
                    result.add((Resource) value);
                }
            }
        }
        return result;
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

    private AttestationMetadataValue metadataLiteral(String value, String language,
                                                      String datatype) {
        return new AttestationMetadataValue(value, "literal", language, datatype);
    }

    private RdfMetadataProperty metadataProperty(String property,
                                                 RdfMetadataValue... values) {
        RdfMetadataProperty result = new RdfMetadataProperty();
        result.property = property;
        result.values = Arrays.asList(values);
        return result;
    }

    private AttestationFilter filterGroup(String operator,
                                          AttestationFilter... filters) {
        AttestationFilter result = new AttestationFilter();
        result.operator = operator;
        result.filters = Arrays.asList(filters);
        return result;
    }

    private AttestationFilter stringFilter(String field, String... values) {
        AttestationFilter result = new AttestationFilter();
        result.operator = "IN";
        result.field = field;
        result.values = Arrays.asList(values);
        return result;
    }

    private AttestationFilter metadataFilter(String property,
                                             AttestationMetadataValue... values) {
        AttestationFilter result = new AttestationFilter();
        result.operator = "EQ";
        result.field = "textMetadata";
        result.property = property;
        result.rdfValues = Arrays.asList(values);
        return result;
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
