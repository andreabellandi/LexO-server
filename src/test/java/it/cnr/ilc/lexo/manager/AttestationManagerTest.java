package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.attestation.output.Attestation;
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
        Attestation result = manager.create(observable.stringValue(), "Example",
                "😀B", "1", "3", context.stringValue(), false, "user7");

        IRI attestation = iri(result.attestation);
        IRI locus = iri("https://example.org/text/interview#char=1,3");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        assertThat(result.fileId).isEqualTo("file-a");
        assertThat(result.locus).isEqualTo(locus.stringValue());
        assertThat(result.creator).isEqualTo("user7");
        assertThat(result.attestation).startsWith("https://lexo.ilc.cnr.it#LexO_");

        try (RepositoryConnection connection = lexicalRepository.getConnection()) {
            assertThat(connection.hasStatement(attestation, RDF.TYPE,
                    iri(FRAC + "Attestation"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(observable, iri(FRAC + "attestation"),
                    attestation, false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, DCTERMS.DESCRIPTION,
                    vf.createLiteral("Example"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, DCTERMS.CREATOR,
                    vf.createLiteral("user7"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, DCTERMS.CREATED,
                    vf.createLiteral(result.creationDate), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, DCTERMS.MODIFIED,
                    vf.createLiteral(result.lastUpdate), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, iri(FRAC + "gloss"),
                    vf.createLiteral("😀B"), false, attestationGraph)).isTrue();
            assertThat(connection.hasStatement(attestation, RDF.VALUE,
                    vf.createLiteral("😀B"), false, attestationGraph)).isTrue();
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

        Attestation result = manager.create(observable.stringValue(), null,
                "gli", "4", "7", corpus.stringValue(), false, "user7");

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

        Attestation result = manager.create(observable.stringValue(), null,
                "A", "0", "1", source.stringValue(), false, "user7");

        assertThat(result.fileId).isEqualTo("file-a");
        assertThat(result.locus).isEqualTo(
                "https://example.org/text/interview#char=0,1");
    }

    @Test
    void rejectsMismatchingCanonicalValueWithoutPartialWrites() {
        assertThatThrownBy(() -> manager.create(observable.stringValue(), null,
                "wrong", "1", "3", context.stringValue(), false, "user7"))
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

        assertThatThrownBy(() -> manager.create(unsupported.stringValue(), null,
                "A", "0", "1", context.stringValue(), false, "user7"))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_OBSERVABLE");
    }

    @Test
    void createsStablePerUrlGraphsForExternalTexts() throws Exception {
        Attestation result = manager.create(observable.stringValue(), null,
                "remote", "2", "8", "https://example.org/external/text", true,
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
        assertThatThrownBy(() -> manager.create(observable.stringValue(), null,
                "remote", "0", "6", "https:external-text", true, "user7"))
                .isInstanceOf(ManagerException.class)
                .hasMessageContaining("INVALID_EXTERNAL_URL");
    }

    @Test
    void createsMultipleAttestationsAndLociAsOneBatch() throws Exception {
        List<AttestationOccurrence> occurrences = Arrays.asList(
                new AttestationOccurrence("First", "A", 0, 1),
                new AttestationOccurrence("Second", "gli", 4, 7));

        List<Attestation> results = manager.createBatch(observable.stringValue(),
                context.stringValue(), false, "user7", occurrences);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(item -> item.attestation).doesNotHaveDuplicates();
        assertThat(results).extracting(item -> item.description)
                .containsExactly("First", "Second");
        IRI attestationGraph = iri(LexicalNamedGraphs.attestationGraphUri("file-a"));
        try (RepositoryConnection lexical = lexicalRepository.getConnection();
             RepositoryConnection text = textRepository.getConnection()) {
            assertThat(count(lexical, null, RDF.TYPE, iri(FRAC + "Attestation"),
                    attestationGraph)).isEqualTo(2);
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
    void rejectsTheWholeBatchWhenOneOccurrenceIsInvalid() {
        List<AttestationOccurrence> occurrences = Arrays.asList(
                new AttestationOccurrence("Valid", "A", 0, 1),
                new AttestationOccurrence("Invalid", "wrong", 1, 3));

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
                        new AttestationOccurrence("Missing offsets", "A", null, null))))
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
        Attestation first = page.list.get(0);
        assertThat(first.observable).isEqualTo(observable.stringValue());
        assertThat(first.observableTypes).contains(ONTOLEX + "LexicalEntry");
        assertThat(first.creator).isEqualTo("user7");
        assertThat(first.description).isEqualTo("Description a");
        assertThat(first.value).isEqualTo("A");
        assertThat(first.start).isEqualTo(0);
        assertThat(first.end).isEqualTo(1);
        assertThat(first.language).isEqualTo("it");
        assertThat(first.referenceContext).isEqualTo(context.stringValue());
        assertThat(first.locusTypes).contains(NIF + "Phrase", NIF + "RFC5147String");
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
