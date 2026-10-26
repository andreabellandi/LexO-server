package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.service.data.text.input.CooccurrenceRequest;
import it.cnr.ilc.lexo.service.data.text.input.FrequencyRequest;
import it.cnr.ilc.lexo.service.data.text.input.FullTextSearchRequest;
import it.cnr.ilc.lexo.service.data.text.input.KwicResizeRequest;
import it.cnr.ilc.lexo.service.data.text.output.CooccurrenceResult;
import it.cnr.ilc.lexo.service.data.text.output.FullTextSearchResult;
import it.cnr.ilc.lexo.service.data.text.output.FullTextQueryValidationResult;
import it.cnr.ilc.lexo.service.data.text.output.TextOccurrence;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneCorpusSearchServiceTest {

    @TempDir
    Path temporary;

    private LuceneCorpusIndexService index;
    private LuceneCorpusSearchService search;

    @BeforeEach
    void openIndex() {
        index = new LuceneCorpusIndexService(temporary.resolve("index"));
        search = new LuceneCorpusSearchService(index);
    }

    @AfterEach
    void closeIndex() {
        index.close();
    }

    @Test
    @DisplayName("Full-text returns occurrences with case, phrase boundary and stateless KWIC")
    void searchesOccurrencesAndResizesKwicWithoutRerunningTheQuery() throws Exception {
        index.index(document("case", "Casa casa CASA.", "corpus-a"));
        index.index(document("sentences", "humanitas est. Virtus magna est.", "corpus-a"));

        FullTextSearchRequest exact = request("Casa", "TERM");
        exact.caseSensitive = true;
        FullTextSearchResult exactResult = search.fullText(exact);
        assertThat(exactResult.totalOccurrences).isEqualTo(1L);
        assertThat(exactResult.occurrences.get(0).match.text).isEqualTo("Casa");

        FullTextSearchRequest folded = request("casa", "TERM");
        FullTextSearchResult foldedResult = search.fullText(folded);
        assertThat(foldedResult.totalOccurrences).isEqualTo(3L);
        assertThat(foldedResult.occurrences).extracting(hit -> hit.match.text)
                .containsExactly("Casa", "casa", "CASA");

        FullTextSearchRequest crossing = request("est Virtus", "PHRASE");
        crossing.boundary = "DOCUMENT";
        assertThat(search.fullText(crossing).totalOccurrences).isEqualTo(1L);
        crossing.boundary = "SENTENCE";
        assertThat(search.fullText(crossing).totalOccurrences).isZero();

        TextOccurrence original = foldedResult.occurrences.get(1);
        KwicResizeRequest resize = new KwicResizeRequest();
        resize.leftContext = Integer.valueOf(1);
        resize.rightContext = Integer.valueOf(1);
        KwicResizeRequest.HitReference reference = new KwicResizeRequest.HitReference();
        reference.contextIRI = original.contextIRI;
        reference.startToken = Integer.valueOf(original.match.startToken);
        reference.endToken = Integer.valueOf(original.match.endToken);
        reference.contentHash = original.contentHash;
        reference.segmentationHash = original.segmentationHash;
        resize.hits.add(reference);
        assertThat(search.resize(resize)).singleElement().satisfies(hit -> {
            assertThat(hit.match.text).isEqualTo("casa");
            assertThat(hit.leftContext).contains("Casa");
            assertThat(hit.rightContext).contains("CASA");
        });
    }

    @Test
    @DisplayName("Co-occurrence keeps overlapping positional pairs and direction")
    void computesEveryEligiblePairWithSignedDistance() throws Exception {
        index.index(document("pairs", "x y y x y", "corpus-b"));

        CooccurrenceRequest both = new CooccurrenceRequest();
        both.node = "x";
        both.collocate = "y";
        both.leftWindow = Integer.valueOf(2);
        both.rightWindow = Integer.valueOf(2);
        both.direction = "BOTH";
        both.boundary = "DOCUMENT";
        CooccurrenceResult result = search.cooccurrences(both);
        assertThat(result.totalPairs).isEqualTo(5L);
        assertThat(result.occurrences).extracting(pair -> pair.signedDistance)
                .containsExactly(1, 2, -2, -1, 1);
        assertThat(result.occurrences).extracting(pair -> pair.gap)
                .containsExactly(0, 1, 1, 0, 0);

        both.direction = "RIGHT";
        CooccurrenceResult right = search.cooccurrences(both);
        assertThat(right.totalPairs).isEqualTo(3L);
        assertThat(right.occurrences)
                .allMatch(pair -> "RIGHT".equals(pair.direction));
    }

    @Test
    @DisplayName("Lucene Matches extracts every prefix, wildcard, fuzzy and Boolean occurrence")
    void extractsMultiTermAndBooleanOccurrencesFromLucenePositions() throws Exception {
        index.index(document("queries", "humanitas humane virtus virtue", "corpus-a"));

        assertThat(search.fullText(request("HUM", "PREFIX")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("humanitas", "humane");
        assertThat(search.fullText(request("VIRT*", "WILDCARD")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("virtus", "virtue");
        assertThat(search.fullText(request("VIRTUS", "FUZZY")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("virtus", "virtue");
        index.index(document("only-humanitas", "humanitas sola", "corpus-a"));
        assertThat(search.fullText(request("humanitas AND virtus", "BOOLEAN"))
                .occurrences).extracting(hit -> hit.match.text)
                .containsExactly("humanitas", "virtus");
    }

    @Test
    @DisplayName("Case-insensitive Lucene syntax preserves operators and normalizes only terms")
    void preservesRawBooleanSyntaxAndNormalizesEveryMultiTermQuery() throws Exception {
        CorpusIndexDocument main = document("syntax-main",
                "Abraam giudeo. Melchisedech giudeo. rock and roll. Abramo Àbramo.",
                "corpus-a");
        CorpusIndexDocument orWord = document("syntax-or", "Or comune.", "corpus-a");
        CorpusIndexDocument onlyAbraam = document("syntax-only",
                "Abraam solitario.", "corpus-a");
        CorpusIndexDocument lower = document("syntax-lower",
                "abraam giudeo.", "corpus-a");
        index.index(main);
        index.index(orWord);
        index.index(onlyAbraam);
        index.index(lower);

        FullTextSearchResult disjunction = search.fullText(request(
                "Abraam OR inesistente", "BOOLEAN"));
        assertThat(disjunction.occurrences).extracting(hit -> hit.match.text)
                .containsExactlyInAnyOrder("Abraam", "Abraam", "abraam")
                .doesNotContain("Or");

        FullTextSearchResult conjunction = search.fullText(request(
                "Abraam AND giudeo", "BOOLEAN"));
        assertThat(conjunction.occurrences).extracting(hit -> hit.contextIRI)
                .contains(main.contextIRI, lower.contextIRI)
                .doesNotContain(onlyAbraam.contextIRI, orWord.contextIRI);

        assertThat(search.fullText(request("\"rock and roll\"", "BOOLEAN"))
                .occurrences).singleElement().satisfies(hit -> {
            assertThat(hit.contextIRI).isEqualTo(main.contextIRI);
            assertThat(hit.match.text).isEqualTo("rock and roll");
        });

        assertThat(search.fullText(request("Abraam NOT giudeo", "BOOLEAN"))
                .occurrences).extracting(hit -> hit.contextIRI)
                .containsOnly(onlyAbraam.contextIRI);
        assertThat(search.fullText(request(
                "(Abraam OR Melchisedech) AND giudeo", "BOOLEAN"))
                .occurrences).extracting(hit -> hit.contextIRI)
                .contains(main.contextIRI, lower.contextIRI)
                .doesNotContain(onlyAbraam.contextIRI, orWord.contextIRI);

        assertThat(search.fullText(request("Abr*", "BOOLEAN")).occurrences)
                .extracting(hit -> hit.match.text)
                .contains("Abraam", "Abramo", "abraam");
        assertThat(search.fullText(request("Abr?am", "BOOLEAN")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactlyInAnyOrder("Abraam", "Abraam", "abraam");
        assertThat(search.fullText(request("Abram~1", "BOOLEAN")).occurrences)
                .extracting(hit -> hit.match.text)
                .contains("Abraam", "Abramo", "abraam");
        assertThat(search.fullText(request("A\u0300BR*", "BOOLEAN")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("Àbramo");

        FullTextSearchRequest exactUpper = request("Abraam", "TERM");
        exactUpper.caseSensitive = true;
        assertThat(search.fullText(exactUpper).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactlyInAnyOrder("Abraam", "Abraam")
                .doesNotContain("abraam");
        exactUpper.query = "abraam";
        assertThat(search.fullText(exactUpper).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("abraam");

        FullTextSearchRequest exactWildcard = request("Abr*", "BOOLEAN");
        exactWildcard.caseSensitive = true;
        assertThat(search.fullText(exactWildcard).occurrences)
                .extracting(hit -> hit.match.text)
                .contains("Abraam", "Abramo")
                .doesNotContain("abraam");

        assertThat(search.fullText(request("or", "BOOLEAN")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("Or");
    }

    @Test
    @DisplayName("Malformed Boolean diagnostics retain the original expression")
    void reportsMalformedOriginalQueryWithoutLowercasingIt() {
        String raw = "Abraam AND (giudeo";
        FullTextSearchRequest request = request(raw, "BOOLEAN");

        FullTextQueryValidationResult validation = search.validateFullText(request);
        assertThat(validation.valid).isFalse();
        assertThat(validation.code).isEqualTo("MALFORMED_QUERY");
        assertThat(validation.message).contains(raw);
        assertThatThrownBy(() -> search.fullText(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("MALFORMED_QUERY:")
                .hasMessageContaining(raw);
    }

    @Test
    @DisplayName("Lemma search returns inflected surface forms with canonical offsets and every query syntax")
    void searchesLemmaPositionsWithoutChangingSurfaceOccurrences() throws Exception {
        CorpusIndexDocument inflected = lemmaDocument("lemmas",
                "Corro nei mercati del lavoro.", "corpus-a",
                "Correre", "in", "mercato", "di", "lavoro");
        CorpusIndexDocument distractor = lemmaDocument("distractor",
                "Corro velocemente.", "corpus-a",
                "Correre", "velocemente");
        index.index(inflected);
        index.index(distractor);

        FullTextSearchRequest term = lemmaRequest("correre", "TERM");
        term.contextIRIs.add(inflected.contextIRI);
        FullTextSearchResult termResult = search.fullText(term);
        assertThat(termResult.occurrences).singleElement().satisfies(hit -> {
            assertThat(hit.match.text).isEqualTo("Corro");
            assertThat(hit.match.beginIndex).isZero();
            assertThat(hit.match.endIndex).isEqualTo(5);
            assertThat(hit.rightContext).contains("nei mercati");
        });
        assertThat(termResult.lemmaCoverage.searchedContextIRIs)
                .containsExactly(inflected.contextIRI);
        assertThat(termResult.lemmaCoverage.lemmaTokenCount).isEqualTo(5L);

        FullTextSearchRequest token = request("correre", "TERM");
        token.contextIRIs.add(inflected.contextIRI);
        assertThat(search.fullText(token).totalOccurrences).isZero();

        FullTextSearchRequest phrase = lemmaRequest("mercato di lavoro", "PHRASE");
        phrase.contextIRIs.add(inflected.contextIRI);
        assertThat(search.fullText(phrase).occurrences).singleElement()
                .extracting(hit -> hit.match.text)
                .isEqualTo("mercati del lavoro");

        FullTextSearchRequest wildcard = lemmaRequest("merc*", "WILDCARD");
        wildcard.contextIRIs.add(inflected.contextIRI);
        assertThat(search.fullText(wildcard).occurrences).singleElement()
                .extracting(hit -> hit.match.text).isEqualTo("mercati");

        FullTextSearchRequest fuzzy = lemmaRequest("corere", "FUZZY");
        fuzzy.contextIRIs.add(inflected.contextIRI);
        assertThat(search.fullText(fuzzy).occurrences).singleElement()
                .extracting(hit -> hit.match.text).isEqualTo("Corro");

        FullTextSearchRequest bool = lemmaRequest(
                "correre AND (\"mercato di lavoro\" OR lavor~)", "BOOLEAN");
        assertThat(search.fullText(bool).occurrences).extracting(hit -> hit.contextIRI)
                .containsOnly(inflected.contextIRI);

        FullTextSearchRequest exactCase = lemmaRequest("Correre", "TERM");
        exactCase.caseSensitive = true;
        assertThat(search.fullText(exactCase).totalOccurrences).isEqualTo(2L);
        exactCase.query = "correre";
        assertThat(search.fullText(exactCase).totalOccurrences).isZero();
    }

    @Test
    @DisplayName("Lemma coverage rejects or explicitly reports texts without usable lemmas")
    void neverReturnsSilentPartialLemmaResults() throws Exception {
        CorpusIndexDocument annotated = lemmaDocument("annotated", "Case belle.",
                "corpus-a", "casa", "bello");
        CorpusIndexDocument plain = document("plain", "Altre case.", "corpus-a");
        index.index(annotated);
        index.index(plain);

        FullTextSearchRequest strict = lemmaRequest("casa", "TERM");
        assertThatThrownBy(() -> search.fullText(strict))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INCOMPLETE_LEMMA_COVERAGE:");

        strict.requireCompleteLemmaCoverage = Boolean.FALSE;
        FullTextSearchResult partial = search.fullText(strict);
        assertThat(partial.occurrences).singleElement()
                .extracting(hit -> hit.match.text).isEqualTo("Case");
        assertThat(partial.lemmaCoverage.requestedContextCount).isEqualTo(2);
        assertThat(partial.lemmaCoverage.searchedContextIRIs)
                .containsExactly(annotated.contextIRI);
        assertThat(partial.lemmaCoverage.excludedContextIRIs)
                .containsExactly(plain.contextIRI);

        FullTextSearchRequest unavailable = lemmaRequest("casa", "TERM");
        unavailable.contextIRIs.add(plain.contextIRI);
        assertThatThrownBy(() -> search.fullText(unavailable))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("LEMMA_SEARCH_UNAVAILABLE:");
    }

    @Test
    @DisplayName("Validation uses the search parser and limits without executing a query")
    void validatesWithTheExactFullTextParser() throws Exception {
        FullTextSearchRequest valid = lemmaRequest(
                "\"mercato del lavoro\" OR lavor*", "BOOLEAN");
        FullTextQueryValidationResult accepted = search.validateFullText(valid);
        assertThat(accepted.valid).isTrue();
        assertThat(accepted.normalizedQuery).isEqualTo(valid.query);
        assertThat(index.fileIds()).isEmpty();

        FullTextSearchRequest invalid = lemmaRequest("(mercato AND", "BOOLEAN");
        FullTextQueryValidationResult rejected = search.validateFullText(invalid);
        assertThat(rejected.valid).isFalse();
        assertThat(rejected.code).isEqualTo("MALFORMED_QUERY");
        assertThat(rejected.message).isNotBlank();
        assertThat(rejected.position).isPositive();
        assertThatThrownBy(() -> search.fullText(invalid))
                .hasMessageStartingWith("MALFORMED_QUERY:");
    }

    @Test
    @DisplayName("Frequency uses Lucene term vectors over the selected corpus subset")
    void computesFrequencyFromLuceneTermVectorsAndCorpusFilters() throws Exception {
        index.index(document("frequency-a", "Casa casa altro", "corpus-a"));
        index.index(document("frequency-b", "casa diverso", "corpus-b"));

        FrequencyRequest request = new FrequencyRequest();
        request.term = "casa";
        request.corpusIRIs.add("https://example.org/corpora/corpus-a");

        assertThat(search.frequency(request)).satisfies(result -> {
            assertThat(result.tokenFrequency).isEqualTo(2L);
            assertThat(result.documentFrequency).isEqualTo(1L);
            assertThat(result.totalTokens).isEqualTo(3L);
            assertThat(result.relativeFrequency).isEqualTo(2.0d / 3.0d);
        });
    }

    @Test
    @DisplayName("Safe rebuild preserves snapshots, hashes and search results")
    void rebuildsThroughAValidatedTemporaryIndex() throws Exception {
        CorpusIndexDocument first = lemmaDocument("one", "uno due uno", "corpus-a",
                "uno", "due", "uno");
        CorpusIndexDocument second = document("two", "due tre", "corpus-b");
        index.index(first);
        index.index(second);
        long before = search.fullText(request("uno", "TERM")).totalOccurrences;

        index.safeRebuild(Arrays.asList(first, second));

        assertThat(index.fileIds()).containsExactly("one", "two");
        assertThat(index.get("one").contentHash).isEqualTo(first.contentHash);
        assertThat(index.get("one").segmentationHash)
                .isEqualTo(first.segmentationHash);
        assertThat(index.get("one").indexSchemaVersion).isEqualTo("3");
        assertThat(search.fullText(request("uno", "TERM")).totalOccurrences)
                .isEqualTo(before);
        FullTextSearchRequest lemma = lemmaRequest("uno", "TERM");
        lemma.contextIRIs.add(first.contextIRI);
        assertThat(search.fullText(lemma).totalOccurrences).isEqualTo(2L);
    }

    private static FullTextSearchRequest request(String query, String type) {
        FullTextSearchRequest result = new FullTextSearchRequest();
        result.query = query;
        result.queryType = type;
        result.leftContext = Integer.valueOf(2);
        result.rightContext = Integer.valueOf(2);
        return result;
    }

    private static FullTextSearchRequest lemmaRequest(String query, String type) {
        FullTextSearchRequest result = request(query, type);
        result.searchField = "LEMMA";
        return result;
    }

    private static CorpusIndexDocument document(String id, String content,
                                                 String corpus) throws Exception {
        ParsedTextDocument parsed = new ControlledCommonMarkParser()
                .parsePlainText(content);
        parsed.metadata.put("language", "it");
        return CorpusIndexDocument.from(id,
                "https://example.org/texts/" + id + "#context",
                "https://example.org/corpora/" + corpus,
                "https://example.org/graphs/" + id, parsed);
    }

    private static CorpusIndexDocument lemmaDocument(String id, String content,
                                                      String corpus,
                                                      String... lemmas)
            throws Exception {
        ParsedTextDocument parsed = new ControlledCommonMarkParser()
                .parsePlainText(content);
        assertThat(parsed.tokens).hasSize(lemmas.length);
        for (int index = 0; index < lemmas.length; index++) {
            parsed.tokens.get(index).lemma = lemmas[index];
        }
        SegmentationFingerprint.complete(parsed);
        parsed.metadata.put("language", "it");
        return CorpusIndexDocument.from(id,
                "https://example.org/texts/" + id + "#context",
                "https://example.org/corpora/" + corpus,
                "https://example.org/graphs/" + id, parsed);
    }
}
