package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jsonldjava.core.DocumentLoader;
import com.github.jsonldjava.core.JsonLdOptions;
import com.github.jsonldjava.core.JsonLdProcessor;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.service.data.attestation.output.WebAnnotationDocument;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.io.InputStream;
import java.util.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.*;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.repository.*;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttestationWebAnnotationExporterTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String NIF = "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static final String OA = "http://www.w3.org/ns/oa#";
    private static final String TEXT = "https://lexo.ilc.cnr.it/graphs/nif/documents/";
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private Repository lexical;
    private Repository texts;
    private AttestationManager manager;
    private String oldWindow;

    @BeforeEach void setup() {
        lexical = new SailRepository(new MemoryStore());
        texts = new SailRepository(new MemoryStore());
        lexical.init();
        texts.init();
        manager = new AttestationManager(lexical, texts);
        oldWindow = LexOProperties.getProperty("webAnnotation.quoteContextLength", "50");
        LexOProperties.setProperty("webAnnotation.quoteContextLength", "50");
    }

    @AfterEach void close() {
        lexical.shutDown();
        texts.shutDown();
        LexOProperties.setProperty("webAnnotation.quoteContextLength", oldWindow);
    }

    @Test void mapsCanonicalUnicodeAndPreservesRepositories() throws Exception {
        seed("a", "1", "\uFEFF A😀Be\u0301\r\n\t.", 3, 6);
        Model beforeLexical = snapshot(lexical);
        Model beforeText = snapshot(texts);
        JsonNode annotation = export(false).graph.get(0);
        assertThat(annotation.path("target").path("source").asText()).isEqualTo("https://example.org/text/a");
        JsonNode selectors = annotation.path("target").path("selector");
        assertThat(selectors.get(0).path("start").intValue()).isEqualTo(3);
        assertThat(selectors.get(0).path("end").intValue()).isEqualTo(6);
        assertThat(selectors.get(1).path("exact").asText()).isEqualTo("😀Be");
        assertThat(selectors.get(1).path("prefix").asText()).isEqualTo("\uFEFF A");
        assertThat(selectors.get(1).path("suffix").asText()).isEqualTo("\u0301\r\n\t.");
        assertThat(selectors.get(2).path("value").asText()).isEqualTo("char=3,6");
        assertThat(annotation.has("metadata")).isFalse();
        assertThat(annotation.has("lexoProvenance")).isFalse();
        assertThat(snapshot(lexical)).isEqualTo(beforeLexical);
        assertThat(snapshot(texts)).isEqualTo(beforeText);
        assertDefaultEmpty(lexical);
        assertDefaultEmpty(texts);
    }

    @Test void mapsOriginalFiftyNineCodePointExample() throws Exception {
        String quote = "Una persona importante, cioè una persona che è determinata.";
        String prefix = String.join("", Collections.nCopies(1316, "x"));
        seed("a", "1", prefix + quote + "!", 1316, 1375);
        JsonNode selectors = export(false).graph.get(0).path("target").path("selector");
        assertThat(selectors.get(1).path("exact").asText()).isEqualTo(quote);
        assertThat(selectors.get(1).path("prefix").asText()).hasSize(50);
        assertThat(selectors.get(1).path("suffix").asText()).isEqualTo("!");
    }

    @Test void preservesNativeMetadataProvenanceAndUnknownProperties() throws Exception {
        seed("a", "1", "abc", 0, 2);
        try (RepositoryConnection c = lexical.getConnection()) {
            c.add(att("1"), iri("https://example.org/future"), vf.createLiteral("0.90", XSD.DECIMAL), graph("a"));
            c.add(att("1"), iri("https://example.org/future"), iri("https://example.org/source"), graph("a"));
            c.add(att("1"), SKOS.NOTE, vf.createLiteral("nota", "it"), graph("a"));
            c.add(att("1"), SKOS.DEFINITION, vf.createLiteral("protected"), graph("a"));
        }
        JsonNode expected = JSON.valueToTree(manager.list("a", null, null, null, "50", "0").list.get(0));
        JsonNode annotation = export(true).graph.get(0);
        assertThat(annotation.path("metadata")).isEqualTo(expected.path("metadata"));
        assertThat(annotation.path("metadata").has(SKOS.NOTE.stringValue())).isTrue();
        assertThat(annotation.path("metadata").has(SKOS.DEFINITION.stringValue())).isFalse();
        assertThat(annotation.path("metadata").has(DCTERMS.CREATOR.stringValue())).isFalse();
        assertThat(annotation.path("lexoProvenance").path("creator").asText()).isEqualTo("imported");
        assertThat(annotation.path("lexoProvenance").path("lastUpdate").isNull()).isTrue();
    }

    @Test void selectsSupportedGraphsAndReturnsAllBodiesDeterministically() throws Exception {
        seed("a", "2", "abc", 0, 1);
        seed("b", "1", "abc", 0, 1);
        try (RepositoryConnection c = lexical.getConnection()) {
            c.add(iri("https://example.org/body/z"), iri(FRAC + "attestation"), att("2"), graph("a"));
            c.add(att("ignored"), RDF.TYPE, iri(FRAC + "Attestation"));
            c.add(att("legacy"), RDF.TYPE, iri(FRAC + "Attestation"), iri(LexicalNamedGraphs.lexiconGraphUri()));
        }
        WebAnnotationDocument selected = manager.exportWebAnnotations(Arrays.asList(graph("a").stringValue(), graph("a").stringValue()), false);
        assertThat(selected.graph).hasSize(1);
        assertThat(selected.graph.get(0).path("body")).hasSize(2);
        WebAnnotationDocument all = export(false);
        assertThat(all.graph).hasSize(2);
        assertThat(all.graph.get(0).path("id").asText()).isEqualTo(att("1").stringValue());
        assertThat(manager.exportWebAnnotations(Collections.singletonList(graph("missing").stringValue()), false).graph).isEmpty();
        assertThat((JsonNode) JSON.valueToTree(manager.exportWebAnnotations(Arrays.asList(graph("b").stringValue(), graph("a").stringValue()), false)))
                .isEqualTo(JSON.valueToTree(all));
    }

    @ParameterizedTest @ValueSource(strings = {"", "relative", "https://example.org/wrong", "https://example.org/a b", "https://lexo.ilc.cnr.it/graphs/nif/documents/a", "https://lexo.ilc.cnr.it/graphs/lexical/attestations/documents/a/b"})
    void rejectsInvalidGraphParameters(String context) {
        assertThatThrownBy(() -> manager.exportWebAnnotations(Collections.singletonList(context), false))
                .isInstanceOfSatisfying(WebAnnotationExportException.class, e -> assertThat(e.httpStatus).isEqualTo(400));
    }

    @Test void followsLocusContextWhenObservedInIsACorpus() throws Exception {
        seed("a", "1", "abc", 0, 1);
        replace(lexical, att("1"), iri(FRAC + "observedIn"), iri("https://example.org/corpus"), graph("a"));
        assertThat(export(false).graph).hasSize(1);
    }

    @Test void allowsObservedContextFallbackButRejectsContradictoryContexts() throws Exception {
        seed("a", "1", "abc", 0, 1);
        try (RepositoryConnection c = texts.getConnection()) {
            c.remove(locus("a", 0, 1), iri(NIF + "referenceContext"), null, textGraph("a"));
        }
        assertThat(export(false).graph).hasSize(1);
        replace(texts, locus("a", 0, 1), iri(NIF + "referenceContext"), iri("https://example.org/other#context"), textGraph("a"));
        try (RepositoryConnection c = texts.getConnection()) {
            c.add(iri("https://example.org/other#context"), RDF.TYPE, iri(NIF + "Context"), textGraph("a"));
        }
        expect("WA_INCONSISTENT_SOURCE");
    }

    @Test void rejectsUnavailableExternalCanonicalWithoutUsingOtherGraphs() throws Exception {
        seed("external-a", "1", "abc", 0, 1);
        Model before = snapshot(lexical);
        try (RepositoryConnection c = texts.getConnection()) {
            c.remove(context("external-a"), iri(NIF + "isString"), null, textGraph("external-a"));
            c.add(context("external-a"), iri(NIF + "isString"), vf.createLiteral("abc"));
            c.add(context("external-a"), iri(NIF + "isString"), vf.createLiteral("abc"), textGraph("other"));
        }
        expect("WA_CANONICAL_TEXT_UNAVAILABLE");
        assertThat(snapshot(lexical)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"body", "locus", "observedIn", "offset", "textConflict", "offsetConflict", "contextConflict", "badSource"})
    void validatesEverySelectedRecord(String defect) throws Exception {
        seed("a", "1", "abc", 0, 1);
        seed("b", "2", "abc", 0, 1); // Error after a valid record must fail the whole export.
        String code;
        if ("body".equals(defect)) {
            try (RepositoryConnection c = lexical.getConnection()) { c.remove((Resource) null, iri(FRAC + "attestation"), att("2"), graph("b")); }
            code = "WA_MISSING_BODY";
        } else if ("locus".equals(defect) || "observedIn".equals(defect)) {
            try (RepositoryConnection c = lexical.getConnection()) { c.remove(att("2"), iri(FRAC + defect), null, graph("b")); }
            code = "WA_INCOMPLETE_ATTESTATION";
        } else if ("offset".equals(defect)) {
            replace(texts, locus("b", 0, 1), iri(NIF + "endIndex"), vf.createLiteral(99), textGraph("b"));
            code = "WA_INVALID_OFFSETS";
        } else if ("badSource".equals(defect)) {
            replace(lexical, att("2"), iri(FRAC + "locus"), iri("https://example.org/wrong#char=0,1"), graph("b"));
            code = "WA_INCONSISTENT_SOURCE";
        } else {
            try (RepositoryConnection c = texts.getConnection()) {
                if ("textConflict".equals(defect)) c.add(context("b"), iri(NIF + "isString"), vf.createLiteral("other"), textGraph("b"));
                if ("offsetConflict".equals(defect)) c.add(locus("b", 0, 1), iri(NIF + "beginIndex"), vf.createLiteral(1), textGraph("b"));
                if ("contextConflict".equals(defect)) c.add(locus("b", 0, 1), iri(NIF + "referenceContext"), iri("https://example.org/other"), textGraph("b"));
            }
            code = "WA_INCONSISTENT_SOURCE";
        }
        expect(code);
    }

    @Test void acceptsRfcOnlyOffsetsAndRejectsMalformedFragments() throws Exception {
        seed("a", "1", "abc", 0, 1);
        try (RepositoryConnection c = texts.getConnection()) {
            c.remove(locus("a", 0, 1), iri(NIF + "beginIndex"), null, textGraph("a"));
            c.remove(locus("a", 0, 1), iri(NIF + "endIndex"), null, textGraph("a"));
        }
        assertThat(export(false).graph).hasSize(1);
        replace(lexical, att("1"), iri(FRAC + "locus"), iri("https://example.org/text/a#char=0,x"), graph("a"));
        expect("WA_INVALID_OFFSETS");
    }

    @Test void deduplicatesCompatibleRecordsAndRejectsHiddenMetadataConflicts() throws Exception {
        seed("a", "1", "abc", 0, 1);
        try (RepositoryConnection l = lexical.getConnection(); RepositoryConnection t = texts.getConnection()) {
            for (Statement s : snapshot(lexical)) if (graph("a").equals(s.getContext())) l.add(s.getSubject(), s.getPredicate(), s.getObject(), graph("b"));
            for (Statement s : snapshot(texts)) t.add(s.getSubject(), s.getPredicate(), s.getObject(), textGraph("b"));
            l.add(iri("https://example.org/body/other"), iri(FRAC + "attestation"), att("1"), graph("b"));
        }
        assertThat(export(false).graph).hasSize(1);
        assertThat(export(false).graph.get(0).path("body")).hasSize(2);
        try (RepositoryConnection c = lexical.getConnection()) { c.add(att("1"), SKOS.NOTE, vf.createLiteral("different"), graph("b")); }
        expect("WA_CONFLICTING_ATTESTATION");
        assertThat(manager.exportWebAnnotations(Collections.singletonList(graph("a").stringValue()), false).graph).hasSize(1);
    }

    @Test void handlesConfiguredWindowAndRejectsInvalidConfiguration() throws Exception {
        seed("a", "1", "abc", 1, 2);
        LexOProperties.setProperty("webAnnotation.quoteContextLength", "0");
        JsonNode quote = export(false).graph.get(0).path("target").path("selector").get(1);
        assertThat(quote.path("prefix").asText()).isEmpty();
        assertThat(quote.path("suffix").asText()).isEmpty();
        LexOProperties.setProperty("webAnnotation.quoteContextLength", "2147483647");
        assertThat(export(false).graph.get(0).path("target").path("selector").get(1).path("suffix").asText()).isEqualTo("c");
        LexOProperties.setProperty("webAnnotation.quoteContextLength", "-1");
        assertThatThrownBy(() -> export(false)).isInstanceOfSatisfying(WebAnnotationExportException.class,
                e -> assertThat(e.httpStatus).isEqualTo(500));
    }

    @Test void crossesBatchBoundariesAndKeepsSharedContext() throws Exception {
        seed("a", "000", "abc", 0, 1);
        try (RepositoryConnection c = lexical.getConnection()) {
            for (int i = 1; i < 260; i++) {
                IRI subject = att(String.format("%03d", i));
                c.add(subject, RDF.TYPE, iri(FRAC + "Attestation"), graph("a"));
                c.add(iri("https://example.org/body/entry"), iri(FRAC + "attestation"), subject, graph("a"));
                c.add(subject, iri(FRAC + "locus"), locus("a", 0, 1), graph("a"));
                c.add(subject, iri(FRAC + "observedIn"), context("a"), graph("a"));
            }
        }
        WebAnnotationDocument result = export(false);
        assertThat(result.graph).hasSize(260);
        assertThat(result.graph.get(259).path("id").asText()).isEqualTo(att("259").stringValue());
        assertThat(result.graph.get(259).path("target").path("selector").get(1).path("exact").asText()).isEqualTo("a");
    }

    @Test void rejectsOutOfBoundsEvenWhenRfcAndNifAgree() throws Exception {
        seed("a", "1", "abc", 0, 4);
        expect("WA_INVALID_OFFSETS");
    }

    @ParameterizedTest @ValueSource(strings = {"attestation", "locus", "observedIn", "referenceContext"})
    void rejectsBlankNodeIdentifiersBeforeBuildingSparqlValues(String position) throws Exception {
        seed("a", "1", "abc", 0, 1);
        if ("attestation".equals(position)) {
            try (RepositoryConnection c = lexical.getConnection()) {
                c.add(vf.createBNode(), RDF.TYPE, iri(FRAC + "Attestation"), graph("a"));
            }
        } else if ("referenceContext".equals(position)) {
            replace(texts, locus("a", 0, 1), iri(NIF + position), vf.createBNode(), textGraph("a"));
        } else {
            replace(lexical, att("1"), iri(FRAC + position), vf.createBNode(), graph("a"));
        }
        expect("WA_INCOMPLETE_ATTESTATION");
    }

    @Test void supportsNifOnlyOffsetsAndEmptyExports() throws Exception {
        assertThat(export(false).graph).isEmpty();
        seed("a", "1", "abc", 0, 3);
        IRI old = locus("a", 0, 3);
        IRI replacement = iri("https://example.org/text/a#span");
        replace(lexical, att("1"), iri(FRAC + "locus"), replacement, graph("a"));
        try (RepositoryConnection c = texts.getConnection()) {
            for (Statement statement : snapshot(texts).filter(old, null, null)) {
                c.add(replacement, statement.getPredicate(), statement.getObject(), textGraph("a"));
            }
            c.remove(old, null, null, textGraph("a"));
        }
        JsonNode quote = export(false).graph.get(0).path("target").path("selector").get(1);
        assertThat(quote.path("exact").asText()).isEqualTo("abc");
        assertThat(quote.path("prefix").asText()).isEmpty();
        assertThat(quote.path("suffix").asText()).isEmpty();
    }

    @Test void expandsCoreWithPinnedContextAndWritesConformanceFixture() throws Exception {
        seed("a", "1", "abc", 0, 1);
        WebAnnotationDocument document = export(false);
        JsonLdOptions options = new JsonLdOptions();
        options.setProcessingMode(JsonLdOptions.JSON_LD_1_1);
        try (InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Paths.get("src/test/resources/web-annotation-context.json"))) {
            String context = JSON.writeValueAsString(JSON.readTree(in));
            options.setDocumentLoader(new DocumentLoader().addInjectedDoc(AttestationWebAnnotationExporter.CONTEXT, context));
        }
        JsonNode expanded = JSON.valueToTree(JsonLdProcessor.expand(JSON.convertValue(document, Object.class), options));
        JsonNode annotation = expanded.get(0).has("@graph") ? expanded.get(0).path("@graph").get(0) : expanded.get(0);
        assertThat(annotation.path("@type").get(0).asText()).isEqualTo(OA + "Annotation");
        assertThat(annotation.path(OA + "hasBody").get(0).path("@id").asText()).isEqualTo("https://example.org/body/entry");
        try (RepositoryConnection c = lexical.getConnection()) {
            c.add(att("1"), SKOS.NOTE, vf.createLiteral("nota", "it"), graph("a"));
            c.add(att("1"), iri("https://example.org/future"), iri("https://example.org/source"), graph("a"));
        }
        WebAnnotationDocument withMetadata = export(true);
        java.nio.file.Path fixture = java.nio.file.Paths.get("target/web-annotation-conformance.json");
        JSON.writeValue(fixture.toFile(), withMetadata);

    }

    private WebAnnotationDocument export(boolean metadata) throws ManagerException { return manager.exportWebAnnotations(null, metadata); }
    private void expect(String code) {
        assertThatThrownBy(() -> export(false)).isInstanceOfSatisfying(WebAnnotationExportException.class, e -> {
            assertThat(e.httpStatus).isEqualTo(422);
            assertThat(e.getMessage()).startsWith(code + ":").contains("attestation=", "graph=");
        });
    }
    private void seed(String file, String id, String canonical, int start, int end) {
        try (RepositoryConnection l = lexical.getConnection(); RepositoryConnection t = texts.getConnection()) {
            l.add(att(id), RDF.TYPE, iri(FRAC + "Attestation"), graph(file));
            l.add(iri("https://example.org/body/entry"), RDF.TYPE, iri("http://www.w3.org/ns/lemon/ontolex#LexicalEntry"), iri(LexiconCrudSupport.lexicalGraphUri("it")));
            l.add(iri("https://example.org/body/entry"), iri(FRAC + "attestation"), att(id), graph(file));
            l.add(att(id), iri(FRAC + "locus"), locus(file, start, end), graph(file));
            l.add(att(id), iri(FRAC + "observedIn"), context(file), graph(file));
            l.add(att(id), DCTERMS.CREATOR, vf.createLiteral("imported"), graph(file));
            t.add(context(file), RDF.TYPE, iri(NIF + "Context"), textGraph(file));
            t.add(context(file), iri(NIF + "isString"), vf.createLiteral(canonical, "it"), textGraph(file));
            t.add(locus(file, start, end), iri(NIF + "referenceContext"), context(file), textGraph(file));
            t.add(locus(file, start, end), iri(NIF + "beginIndex"), vf.createLiteral(start), textGraph(file));
            t.add(locus(file, start, end), iri(NIF + "endIndex"), vf.createLiteral(end), textGraph(file));
        }
    }
    private void replace(Repository repository, Resource subject, IRI predicate, Value value, IRI graph) {
        try (RepositoryConnection c = repository.getConnection()) { c.remove(subject, predicate, null, graph); c.add(subject, predicate, value, graph); }
    }
    private Model snapshot(Repository repository) {
        Model result = new LinkedHashModel();
        try (RepositoryConnection c = repository.getConnection(); RepositoryResult<Statement> statements = c.getStatements(null, null, null, false)) {
            while (statements.hasNext()) result.add(statements.next());
        }
        return result;
    }
    private void assertDefaultEmpty(Repository repository) { try (RepositoryConnection c = repository.getConnection()) { assertThat(c.hasStatement(null, null, null, false, (Resource) null)).isFalse(); } }
    private IRI iri(String value) { return vf.createIRI(value); }
    private IRI att(String id) { return iri("https://example.org/attestation/" + id); }
    private IRI graph(String file) { return iri(LexicalNamedGraphs.attestationGraphUri(file)); }
    private IRI textGraph(String file) { return iri(TEXT + file); }
    private IRI context(String file) { return iri("https://example.org/text/" + file + "#context"); }
    private IRI locus(String file, int start, int end) { return iri("https://example.org/text/" + file + "#char=" + start + "," + end); }
}
