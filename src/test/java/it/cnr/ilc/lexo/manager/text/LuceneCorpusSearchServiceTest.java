package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.service.data.text.input.CooccurrenceRequest;
import it.cnr.ilc.lexo.service.data.text.input.FrequencyRequest;
import it.cnr.ilc.lexo.service.data.text.input.FullTextSearchRequest;
import it.cnr.ilc.lexo.service.data.text.input.KwicResizeRequest;
import it.cnr.ilc.lexo.service.data.text.output.CooccurrenceResult;
import it.cnr.ilc.lexo.service.data.text.output.FullTextSearchResult;
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

        assertThat(search.fullText(request("hum", "PREFIX")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("humanitas", "humane");
        assertThat(search.fullText(request("virt*", "WILDCARD")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("virtus", "virtue");
        assertThat(search.fullText(request("virtus", "FUZZY")).occurrences)
                .extracting(hit -> hit.match.text)
                .containsExactly("virtus", "virtue");
        assertThat(search.fullText(request("humanitas AND virtus", "BOOLEAN"))
                .occurrences).extracting(hit -> hit.match.text)
                .containsExactly("humanitas", "virtus");
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
        CorpusIndexDocument first = document("one", "uno due uno", "corpus-a");
        CorpusIndexDocument second = document("two", "due tre", "corpus-b");
        index.index(first);
        index.index(second);
        long before = search.fullText(request("uno", "TERM")).totalOccurrences;

        index.safeRebuild(Arrays.asList(first, second));

        assertThat(index.fileIds()).containsExactly("one", "two");
        assertThat(index.get("one").contentHash).isEqualTo(first.contentHash);
        assertThat(index.get("one").segmentationHash)
                .isEqualTo(first.segmentationHash);
        assertThat(search.fullText(request("uno", "TERM")).totalOccurrences)
                .isEqualTo(before);
    }

    private static FullTextSearchRequest request(String query, String type) {
        FullTextSearchRequest result = new FullTextSearchRequest();
        result.query = query;
        result.queryType = type;
        result.leftContext = Integer.valueOf(2);
        result.rightContext = Integer.valueOf(2);
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
}
