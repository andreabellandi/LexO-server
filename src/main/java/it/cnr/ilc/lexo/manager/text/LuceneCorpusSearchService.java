package it.cnr.ilc.lexo.manager.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.CollocationStatisticsService.Counts;
import it.cnr.ilc.lexo.service.data.text.input.CollocateExtractionRequest;
import it.cnr.ilc.lexo.service.data.text.input.CooccurrenceRequest;
import it.cnr.ilc.lexo.service.data.text.input.CorpusSelection;
import it.cnr.ilc.lexo.service.data.text.input.FrequencyRequest;
import it.cnr.ilc.lexo.service.data.text.input.FullTextSearchRequest;
import it.cnr.ilc.lexo.service.data.text.input.KwicResizeRequest;
import it.cnr.ilc.lexo.service.data.text.output.CollocateExtractionResult;
import it.cnr.ilc.lexo.service.data.text.output.CollocateExtractionResult.Collocate;
import it.cnr.ilc.lexo.service.data.text.output.CooccurrenceResult;
import it.cnr.ilc.lexo.service.data.text.output.CooccurrenceResult.Pair;
import it.cnr.ilc.lexo.service.data.text.output.CooccurrenceResult.PositionedTerm;
import it.cnr.ilc.lexo.service.data.text.output.FrequencyResult;
import it.cnr.ilc.lexo.service.data.text.output.FullTextSearchResult;
import it.cnr.ilc.lexo.service.data.text.output.MetricValue;
import it.cnr.ilc.lexo.service.data.text.output.TextOccurrence;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;

/** Occurrence-oriented corpus analytics backed exclusively by standalone Lucene. */
public final class LuceneCorpusSearchService implements CorpusSearchService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LuceneCorpusSearchService INSTANCE =
            new LuceneCorpusSearchService(LuceneCorpusIndexService.get());

    private final LuceneCorpusIndexService index;
    private final CollocationStatisticsService statistics =
            new CollocationStatisticsService();
    private final int defaultPageSize = positive("lexo.lucene.defaultPageSize", 100);
    private final int maxPageSize = positive("lexo.lucene.maxPageSize", 1000);
    private final int defaultLeft = positive("lexo.lucene.defaultLeftContext", 5);
    private final int defaultRight = positive("lexo.lucene.defaultRightContext", 5);
    private final int maxContext = positive("lexo.lucene.maxContextSize", 500);
    private final int maxWindow = positive("lexo.lucene.maxCollocationWindow", 100);
    private final int maxQueryLength = positive("lexo.lucene.maxQueryLength", 1024);
    private final int maxWildcardExpansions = positive(
            "lexo.lucene.maxWildcardExpansions", 256);
    private final Map<String, CorpusIndexDocument> snapshotCache;

    public static LuceneCorpusSearchService get() {
        return INSTANCE;
    }

    LuceneCorpusSearchService(LuceneCorpusIndexService index) {
        this.index = index;
        final int cacheSize = positive("lexo.lucene.tokenMapCacheSize", 128);
        snapshotCache = Collections.synchronizedMap(
                new LinkedHashMap<String, CorpusIndexDocument>(cacheSize + 1,
                        0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<String, CorpusIndexDocument> eldest) {
                        return size() > cacheSize;
                    }
                });
        BooleanQuery.setMaxClauseCount(positive("lexo.lucene.maxBooleanClauses", 1024));
    }

    @Override
    public FullTextSearchResult fullText(FullTextSearchRequest request) throws IOException {
        require(request != null, "MISSING_SEARCH_REQUEST", "request body is required");
        String text = required(request.query, "MISSING_QUERY");
        require(text.length() <= maxQueryLength, "QUERY_TOO_LONG",
                "query exceeds " + maxQueryLength + " characters");
        String type = upper(request.queryType, "TERM");
        int left = bounded(request.leftContext, defaultLeft, maxContext, "leftContext");
        int right = bounded(request.rightContext, defaultRight, maxContext, "rightContext");
        int pageSize = boundedPositive(request.pageSize, defaultPageSize,
                maxPageSize, "pageSize");
        int cursor = cursor(request.cursor);
        String field = request.caseSensitive
                ? LuceneCorpusIndexService.CONTENT_SURFACE
                : LuceneCorpusIndexService.CONTENT_NORMALIZED;
        Query contentQuery = query(type, text, field, request.caseSensitive);
        Query query = selected(contentQuery, request);
        IndexSearcher searcher = index.acquire();
        try {
            TopDocs documents = searcher.search(query,
                    Math.max(1, searcher.getIndexReader().numDocs()));
            Weight occurrenceWeight = searcher.createWeight(
                    searcher.rewrite(contentQuery), ScoreMode.COMPLETE_NO_SCORES, 1.0f);
            List<TextOccurrence> all = new ArrayList<TextOccurrence>();
            for (ScoreDoc score : documents.scoreDocs) {
                CorpusIndexDocument document = snapshot(searcher.doc(score.doc));
                List<int[]> matches = matches(searcher, occurrenceWeight, score.doc,
                        field, document, request.boundary);
                for (int[] match : matches) {
                    TextOccurrence occurrence = occurrence(document, match[0], match[1],
                            left, right, request.contextUnit, request.boundary);
                    occurrence.score = Float.valueOf(score.score);
                    all.add(occurrence);
                }
            }
            FullTextSearchResult result = new FullTextSearchResult();
            result.totalOccurrences = all.size();
            int end = Math.min(all.size(), cursor + pageSize);
            if (cursor < all.size()) {
                result.occurrences.addAll(all.subList(cursor, end));
            }
            if (end < all.size()) {
                result.nextCursor = Integer.toString(end);
            }
            if (request.includeMetadata && !result.occurrences.isEmpty()) {
                TextMetadataEnricher.get().enrich(result.occurrences);
            }
            return result;
        } finally {
            index.release(searcher);
        }
    }

    @Override
    public List<TextOccurrence> resize(KwicResizeRequest request) throws IOException {
        require(request != null, "MISSING_KWIC_REQUEST", "request body is required");
        require(request.hits != null && !request.hits.isEmpty(), "MISSING_KWIC_HITS",
                "hits must not be empty");
        int left = bounded(request.leftContext, defaultLeft, maxContext, "leftContext");
        int right = bounded(request.rightContext, defaultRight, maxContext, "rightContext");
        List<TextOccurrence> result = new ArrayList<TextOccurrence>();
        for (int index = 0; index < request.hits.size(); index++) {
            KwicResizeRequest.HitReference hit = request.hits.get(index);
            require(hit != null && !blank(hit.contextIRI), "INVALID_KWIC_HIT",
                    "hit " + index + " requires contextIRI");
            CorpusIndexDocument document = byContext(hit.contextIRI);
            require(document != null, "CONTEXT_NOT_FOUND", hit.contextIRI);
            require(equals(document.contentHash, hit.contentHash), "HASH_MISMATCH",
                    "contentHash differs for " + hit.contextIRI);
            require(equals(document.segmentationHash, hit.segmentationHash),
                    "HASH_MISMATCH", "segmentationHash differs for " + hit.contextIRI);
            int start = hit.startToken == null
                    ? tokenAt(document, requiredInt(hit.beginIndex, "beginIndex"), true)
                    : hit.startToken.intValue();
            int end = hit.endToken == null
                    ? tokenAt(document, requiredInt(hit.endIndex, "endIndex"), false)
                    : hit.endToken.intValue();
            validateTokenRange(document, start, end);
            result.add(occurrence(document, start, end, left, right,
                    request.contextUnit, request.boundary));
        }
        return result;
    }

    @Override
    public CooccurrenceResult cooccurrences(CooccurrenceRequest request) throws IOException {
        require(request != null, "MISSING_COOCCURRENCE_REQUEST", "request body is required");
        String node = required(request.node, "MISSING_NODE");
        String collocate = required(request.collocate, "MISSING_COLLOCATE");
        int left = bounded(request.leftWindow, 5, maxWindow, "leftWindow");
        int right = bounded(request.rightWindow, 5, maxWindow, "rightWindow");
        int pageSize = boundedPositive(request.pageSize, defaultPageSize,
                maxPageSize, "pageSize");
        int cursor = cursor(request.cursor);
        String boundary = boundary(request.boundary);
        String direction = direction(request.direction);
        String field = request.caseSensitive
                ? LuceneCorpusIndexService.CONTENT_SURFACE
                : LuceneCorpusIndexService.CONTENT_NORMALIZED;
        String nodeTerm = singleTerm(node, request.caseSensitive, "node");
        String collocateTerm = singleTerm(collocate, request.caseSensitive,
                "collocate");
        List<Pair> pairs = new ArrayList<Pair>();
        BooleanQuery.Builder requiredTerms = new BooleanQuery.Builder();
        requiredTerms.add(new TermQuery(new Term(field, nodeTerm)),
                BooleanClause.Occur.MUST);
        if (!nodeTerm.equals(collocateTerm)) {
            requiredTerms.add(new TermQuery(new Term(field, collocateTerm)),
                    BooleanClause.Occur.MUST);
        }
        IndexSearcher searcher = index.acquire();
        try {
            TopDocs hits = searcher.search(selected(requiredTerms.build(), request),
                    Math.max(1, searcher.getIndexReader().numDocs()));
            for (ScoreDoc hit : hits.scoreDocs) {
                CorpusIndexDocument document = snapshot(searcher.doc(hit.doc));
                List<Integer> nodes = positions(searcher.getIndexReader(), hit.doc,
                        field, nodeTerm);
                List<Integer> collocates = positions(searcher.getIndexReader(), hit.doc,
                        field, collocateTerm);
                for (Integer nodePosition : nodes) {
                    CorpusIndexDocument.TokenData head =
                            document.tokens.get(nodePosition.intValue());
                    for (Integer collocatePosition : collocates) {
                        int position = collocatePosition.intValue();
                        int delta = position - head.position;
                        if (delta == 0 || delta < -left || delta > right
                                || !allowedDirection(delta, direction)) {
                            continue;
                        }
                        CorpusIndexDocument.TokenData candidate =
                                document.tokens.get(position);
                        if ("SENTENCE".equals(boundary)
                                && candidate.sentenceIndex != head.sentenceIndex) {
                            continue;
                        }
                        pairs.add(pair(document, head, candidate));
                    }
                }
            }
        } finally {
            index.release(searcher);
        }
        CooccurrenceResult result = new CooccurrenceResult();
        result.totalPairs = pairs.size();
        if (request.includeOccurrences) {
            int end = Math.min(pairs.size(), cursor + pageSize);
            if (cursor < pairs.size()) {
                result.occurrences.addAll(pairs.subList(cursor, end));
            }
            if (end < pairs.size()) {
                result.nextCursor = Integer.toString(end);
            }
        }
        return result;
    }

    @Override
    public FrequencyResult frequency(FrequencyRequest request) throws IOException {
        require(request != null, "MISSING_FREQUENCY_REQUEST", "request body is required");
        String term = required(request.term, "MISSING_TERM");
        String field = request.caseSensitive
                ? LuceneCorpusIndexService.CONTENT_SURFACE
                : LuceneCorpusIndexService.CONTENT_NORMALIZED;
        String indexedTerm = singleTerm(term, request.caseSensitive, "term");
        FrequencyResult result = new FrequencyResult();
        result.term = term;
        IndexSearcher searcher = index.acquire();
        try {
            TopDocs hits = searcher.search(selected(new MatchAllDocsQuery(), request),
                    Math.max(1, searcher.getIndexReader().numDocs()));
            for (ScoreDoc hit : hits.scoreDocs) {
                CorpusIndexDocument document = snapshot(searcher.doc(hit.doc));
                result.totalTokens += document.tokens.size();
                int frequency = positions(searcher.getIndexReader(), hit.doc,
                        field, indexedTerm).size();
                result.tokenFrequency += frequency;
                if (frequency > 0) {
                    result.documentFrequency++;
                }
            }
        } finally {
            index.release(searcher);
        }
        result.relativeFrequency = result.totalTokens == 0L ? 0.0d
                : (double) result.tokenFrequency / result.totalTokens;
        result.perMillion = result.relativeFrequency * 1000000.0d;
        return result;
    }

    @Override
    public CollocateExtractionResult extract(CollocateExtractionRequest request)
            throws IOException {
        require(request != null, "MISSING_COLLOCATION_REQUEST", "request body is required");
        String node = required(request.node, "MISSING_NODE");
        int left = bounded(request.leftWindow, 5, maxWindow, "leftWindow");
        int right = bounded(request.rightWindow, 5, maxWindow, "rightWindow");
        int minimum = boundedPositive(request.minCooccurrenceFrequency, 2,
                Integer.MAX_VALUE, "minCooccurrenceFrequency");
        int limit = boundedPositive(request.limit, 100, maxPageSize, "limit");
        require(request.minFrequency == null || request.minFrequency.longValue() >= 0L,
                "INVALID_MIN_FREQUENCY", "minFrequency must be non-negative");
        require(request.minScore == null
                || Double.isFinite(request.minScore.doubleValue()),
                "INVALID_MIN_SCORE", "minScore must be finite");
        String boundary = boundary(request.boundary);
        String direction = direction(request.direction);
        List<CorpusIndexDocument> documents = selectedDocuments(request);
        Set<String> excluded = normalizedSet(request.excludeTerms, request.caseSensitive);
        excluded.addAll(normalizedSet(request.stoplist, request.caseSensitive));
        Map<String, CandidateCounts> candidates = new LinkedHashMap<String, CandidateCounts>();
        CollocateExtractionResult result = new CollocateExtractionResult();
        result.node = node;
        String sortMetric = sortableMetric(request.sortBy);
        Set<String> nodeDocuments = new HashSet<String>();
        for (CorpusIndexDocument document : documents) {
            result.totalTokens += document.tokens.size();
            Set<String> documentTerms = new HashSet<String>();
            for (CorpusIndexDocument.TokenData token : document.tokens) {
                String canonical = canonical(token.surface, request.caseSensitive);
                documentTerms.add(canonical);
                CandidateCounts count = candidates.computeIfAbsent(canonical,
                        key -> new CandidateCounts(token.surface));
                count.fy++;
            }
            for (String term : documentTerms) {
                candidates.get(term).documentsY.add(document.fileId);
            }
            for (CorpusIndexDocument.TokenData head : document.tokens) {
                if (!termEquals(head.surface, node, request.caseSensitive)) {
                    continue;
                }
                result.nodeFrequency++;
                nodeDocuments.add(document.fileId);
                int from = Math.max(0, head.position - left);
                int to = Math.min(document.tokens.size() - 1, head.position + right);
                for (int position = from; position <= to; position++) {
                    if (position == head.position) {
                        continue;
                    }
                    CorpusIndexDocument.TokenData token = document.tokens.get(position);
                    int delta = position - head.position;
                    if (!allowedDirection(delta, direction)
                            || ("SENTENCE".equals(boundary)
                                && token.sentenceIndex != head.sentenceIndex)) {
                        continue;
                    }
                    String canonical = canonical(token.surface, request.caseSensitive);
                    CandidateCounts count = candidates.computeIfAbsent(canonical,
                            key -> new CandidateCounts(token.surface));
                    count.fxy++;
                    count.documentsXY.add(document.fileId);
                }
            }
        }
        result.pairSpace = pairSpace(documents, left, right, direction, boundary);
        for (CandidateCounts candidate : candidates.values()) {
            if (candidate.fxy < minimum
                    || (request.minFrequency != null
                        && candidate.fy < request.minFrequency.longValue())
                    || excluded.contains(canonical(candidate.term, request.caseSensitive))) {
                continue;
            }
            Collocate output = new Collocate();
            output.term = candidate.term;
            output.fx = result.nodeFrequency;
            output.fy = candidate.fy;
            output.fxy = candidate.fxy;
            output.documentFrequencyX = nodeDocuments.size();
            output.documentFrequencyY = candidate.documentsY.size();
            output.documentsWithCooccurrence = candidate.documentsXY.size();
            Counts counts = pairCounts(documents, node, candidate.term, left, right,
                    direction, boundary, request.caseSensitive);
            counts.n = result.totalTokens;
            counts.fx = output.fx;
            counts.fy = output.fy;
            counts.fxy = output.fxy;
            output.pairN = counts.pairN;
            output.pairFx = counts.pairFx;
            output.pairFy = counts.pairFy;
            output.pairFxy = counts.pairFxy;
            output.metrics.putAll(statistics.calculate(counts, request.metrics));
            if (request.minScore != null
                    && score(output, sortMetric) < request.minScore.doubleValue()) {
                continue;
            }
            result.collocates.add(output);
        }
        sort(result.collocates, sortMetric, request.sortOrder);
        if (result.collocates.size() > limit) {
            result.collocates = new ArrayList<Collocate>(
                    result.collocates.subList(0, limit));
        }
        return result;
    }

    private List<CorpusIndexDocument> selectedDocuments(CorpusSelection selection)
            throws IOException {
        IndexSearcher searcher = index.acquire();
        try {
            TopDocs hits = searcher.search(selected(new MatchAllDocsQuery(), selection),
                    Math.max(1, searcher.getIndexReader().numDocs()));
            List<CorpusIndexDocument> result = new ArrayList<CorpusIndexDocument>();
            for (ScoreDoc hit : hits.scoreDocs) {
                result.add(snapshot(searcher.doc(hit.doc)));
            }
            return result;
        } finally {
            index.release(searcher);
        }
    }

    private CorpusIndexDocument byContext(String contextIRI) throws IOException {
        IndexSearcher searcher = index.acquire();
        try {
            TopDocs hits = searcher.search(new TermQuery(new Term(
                    LuceneCorpusIndexService.CONTEXT_IRI, contextIRI)), 1);
            return hits.scoreDocs.length == 0 ? null
                    : snapshot(searcher.doc(hits.scoreDocs[0].doc));
        } finally {
            index.release(searcher);
        }
    }

    private static List<Integer> positions(IndexReader reader, int documentId,
                                           String field, String term)
            throws IOException {
        List<Integer> result = new ArrayList<Integer>();
        Terms vector = reader.getTermVector(documentId, field);
        if (vector == null) {
            return result;
        }
        TermsEnum terms = vector.iterator();
        if (!terms.seekExact(new BytesRef(term))) {
            return result;
        }
        PostingsEnum postings = terms.postings(null, PostingsEnum.POSITIONS);
        if (postings == null || postings.nextDoc() == PostingsEnum.NO_MORE_DOCS) {
            return result;
        }
        for (int index = 0; index < postings.freq(); index++) {
            result.add(Integer.valueOf(postings.nextPosition()));
        }
        return result;
    }

    private Query query(String type, String text, String field, boolean caseSensitive) {
        List<String> terms = analyzed(text, caseSensitive);
        require(!terms.isEmpty(), "EMPTY_QUERY", "query produces no searchable term");
        if ("TERM".equals(type)) {
            require(terms.size() == 1, "INVALID_TERM_QUERY",
                    "TERM requires exactly one token");
            return new TermQuery(new Term(field, terms.get(0)));
        }
        if ("PHRASE".equals(type)) {
            PhraseQuery.Builder phrase = new PhraseQuery.Builder();
            for (String term : terms) {
                phrase.add(new Term(field, term));
            }
            return phrase.build();
        }
        if ("PREFIX".equals(type)) {
            require(terms.size() == 1, "INVALID_PREFIX_QUERY",
                    "PREFIX requires exactly one token");
            PrefixQuery query = new PrefixQuery(new Term(field, terms.get(0)));
            query.setRewriteMethod(rewrite());
            return query;
        }
        if ("WILDCARD".equals(type)) {
            require(!text.startsWith("*") && !text.startsWith("?"),
                    "LEADING_WILDCARD_NOT_ALLOWED", "leading wildcard is not allowed");
            WildcardQuery query = new WildcardQuery(new Term(field,
                    caseSensitive ? text : text.toLowerCase(Locale.ROOT)));
            query.setRewriteMethod(rewrite());
            return query;
        }
        if ("FUZZY".equals(type)) {
            require(terms.size() == 1, "INVALID_FUZZY_QUERY",
                    "FUZZY requires exactly one token");
            FuzzyQuery query = new FuzzyQuery(new Term(field, terms.get(0)), 2);
            query.setRewriteMethod(rewrite());
            return query;
        }
        if ("BOOLEAN".equals(type)) {
            try (Analyzer analyzer = queryAnalyzer(caseSensitive)) {
                QueryParser parser = new QueryParser(field, analyzer);
                parser.setAllowLeadingWildcard(false);
                parser.setMultiTermRewriteMethod(rewrite());
                return parser.parse(caseSensitive ? text : text.toLowerCase(Locale.ROOT));
            } catch (ParseException error) {
                throw new IllegalArgumentException("MALFORMED_QUERY: " + error.getMessage());
            }
        }
        throw new IllegalArgumentException("INVALID_QUERY_TYPE: " + type);
    }

    private Query selected(Query content, CorpusSelection selection) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(content, BooleanClause.Occur.MUST);
        addAny(builder, LuceneCorpusIndexService.CONTEXT_IRI,
                selection == null ? null : values(selection.contextIRI,
                        selection.contextIRIs));
        addAny(builder, LuceneCorpusIndexService.CORPUS_IRI,
                selection == null ? null : values(selection.corpusIRI,
                        selection.corpusIRIs));
        addAny(builder, LuceneCorpusIndexService.GRAPH_IRI,
                selection == null ? null : values(selection.graphIRI,
                        selection.graphIRIs));
        return builder.build();
    }

    private void addAny(BooleanQuery.Builder parent, String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        BooleanQuery.Builder alternatives = new BooleanQuery.Builder();
        for (String value : values) {
            String iri = validIri(value);
            alternatives.add(new TermQuery(new Term(field, iri)),
                    BooleanClause.Occur.SHOULD);
        }
        alternatives.setMinimumNumberShouldMatch(1);
        parent.add(alternatives.build(), BooleanClause.Occur.FILTER);
    }

    private List<int[]> matches(IndexSearcher searcher, Weight weight, int documentId,
                                String field, CorpusIndexDocument document,
                                String requestedBoundary) throws IOException {
        List<int[]> result = new ArrayList<int[]>();
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        int leafIndex = ReaderUtil.subIndex(documentId, leaves);
        LeafReaderContext leaf = leaves.get(leafIndex);
        Matches matches = weight.matches(leaf, documentId - leaf.docBase);
        if (matches == null) {
            return result;
        }
        MatchesIterator iterator = matches.getMatches(field);
        if (iterator == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<String>();
        String normalizedBoundary = boundary(requestedBoundary);
        while (iterator.next()) {
            int start = iterator.startPosition();
            int end = iterator.endPosition();
            if (start < 0 || end < start || end >= document.tokens.size()) {
                throw new IOException("Lucene returned an invalid canonical position range: "
                        + start + ":" + end);
            }
            if ("SENTENCE".equals(normalizedBoundary)
                    && document.tokens.get(start).sentenceIndex
                    != document.tokens.get(end).sentenceIndex) {
                continue;
            }
            String key = start + ":" + end;
            if (seen.add(key)) {
                result.add(new int[]{start, end});
            }
        }
        return result;
    }

    private TextOccurrence occurrence(CorpusIndexDocument document, int start, int end,
                                      int left, int right, String unit,
                                      String boundary) {
        validateTokenRange(document, start, end);
        CorpusIndexDocument.TokenData first = document.tokens.get(start);
        CorpusIndexDocument.TokenData last = document.tokens.get(end);
        TextOccurrence result = new TextOccurrence();
        result.contextIRI = document.contextIRI;
        result.contentHash = document.contentHash;
        result.segmentationHash = document.segmentationHash;
        result.match = new TextOccurrence.Match();
        result.match.text = document.content.substring(first.startUtf16, last.endUtf16);
        result.match.beginIndex = first.beginIndex;
        result.match.endIndex = last.endIndex;
        result.match.startToken = start;
        result.match.endToken = end;
        result.match.sentenceIndex = first.sentenceIndex;
        int leftUtf16;
        int rightUtf16;
        String normalizedUnit = upper(unit, "TOKEN");
        String normalizedBoundary = boundary(boundary);
        CorpusIndexDocument.SentenceData sentence =
                document.sentences.get(first.sentenceIndex);
        if ("CHARACTER".equals(normalizedUnit)) {
            int leftCp = Math.max(0, first.beginIndex - left);
            int rightCp = Math.min(document.content.codePointCount(0,
                    document.content.length()), last.endIndex + right);
            if ("SENTENCE".equals(normalizedBoundary)) {
                leftCp = Math.max(leftCp, sentence.beginIndex);
                rightCp = Math.min(rightCp, sentence.endIndex);
            }
            leftUtf16 = UnicodeOffsetMapper.codePointToUtf16(document.content, leftCp);
            rightUtf16 = UnicodeOffsetMapper.codePointToUtf16(document.content, rightCp);
        } else if ("SENTENCE".equals(normalizedUnit)) {
            int firstSentence = Math.max(0, first.sentenceIndex - left);
            int lastSentence = Math.min(document.sentences.size() - 1,
                    first.sentenceIndex + right);
            leftUtf16 = document.sentences.get(firstSentence).startUtf16;
            rightUtf16 = document.sentences.get(lastSentence).endUtf16;
        } else if ("TOKEN".equals(normalizedUnit)) {
            int firstToken = Math.max(0, start - left);
            int lastToken = Math.min(document.tokens.size() - 1, end + right);
            if ("SENTENCE".equals(normalizedBoundary)) {
                while (firstToken < start && document.tokens.get(firstToken).sentenceIndex
                        != first.sentenceIndex) {
                    firstToken++;
                }
                while (lastToken > end && document.tokens.get(lastToken).sentenceIndex
                        != first.sentenceIndex) {
                    lastToken--;
                }
            }
            leftUtf16 = document.tokens.get(firstToken).startUtf16;
            rightUtf16 = document.tokens.get(lastToken).endUtf16;
        } else {
            throw new IllegalArgumentException("INVALID_CONTEXT_UNIT: " + unit);
        }
        if ("SENTENCE".equals(normalizedBoundary)) {
            leftUtf16 = Math.max(leftUtf16, sentence.startUtf16);
            rightUtf16 = Math.min(rightUtf16, sentence.endUtf16);
        }
        result.leftContext = document.content.substring(leftUtf16, first.startUtf16);
        result.rightContext = document.content.substring(last.endUtf16, rightUtf16);
        result.kwic = result.leftContext + result.match.text + result.rightContext;
        return result;
    }

    private Counts pairCounts(List<CorpusIndexDocument> documents, String x, String y,
                              int left, int right, String direction, String boundary,
                              boolean sensitive) {
        Counts result = new Counts();
        for (CorpusIndexDocument document : documents) {
            for (CorpusIndexDocument.TokenData head : document.tokens) {
                int from = Math.max(0, head.position - left);
                int to = Math.min(document.tokens.size() - 1, head.position + right);
                for (int position = from; position <= to; position++) {
                    if (position == head.position) {
                        continue;
                    }
                    CorpusIndexDocument.TokenData candidate = document.tokens.get(position);
                    int delta = position - head.position;
                    if (!allowedDirection(delta, direction)
                            || ("SENTENCE".equals(boundary)
                                && candidate.sentenceIndex != head.sentenceIndex)) {
                        continue;
                    }
                    result.pairN++;
                    boolean isX = termEquals(head.surface, x, sensitive);
                    boolean isY = termEquals(candidate.surface, y, sensitive);
                    if (isX) {
                        result.pairFx++;
                    }
                    if (isY) {
                        result.pairFy++;
                    }
                    if (isX && isY) {
                        result.pairFxy++;
                    }
                }
            }
        }
        return result;
    }

    private long pairSpace(List<CorpusIndexDocument> documents, int left, int right,
                           String direction, String boundary) {
        long result = 0L;
        for (CorpusIndexDocument document : documents) {
            for (CorpusIndexDocument.TokenData head : document.tokens) {
                int from = Math.max(0, head.position - left);
                int to = Math.min(document.tokens.size() - 1, head.position + right);
                for (int position = from; position <= to; position++) {
                    if (position == head.position) {
                        continue;
                    }
                    CorpusIndexDocument.TokenData candidate = document.tokens.get(position);
                    int delta = position - head.position;
                    if (allowedDirection(delta, direction)
                            && (!"SENTENCE".equals(boundary)
                                || candidate.sentenceIndex == head.sentenceIndex)) {
                        result++;
                    }
                }
            }
        }
        return result;
    }

    private static Pair pair(CorpusIndexDocument document,
                             CorpusIndexDocument.TokenData node,
                             CorpusIndexDocument.TokenData collocate) {
        Pair result = new Pair();
        result.contextIRI = document.contextIRI;
        result.node = positioned(node);
        result.collocate = positioned(collocate);
        result.signedDistance = collocate.position - node.position;
        result.absoluteDistance = Math.abs(result.signedDistance);
        result.gap = result.absoluteDistance - 1;
        result.direction = result.signedDistance < 0 ? "LEFT" : "RIGHT";
        return result;
    }

    private static PositionedTerm positioned(CorpusIndexDocument.TokenData token) {
        PositionedTerm result = new PositionedTerm();
        result.text = token.surface;
        result.tokenPosition = token.position;
        result.sentenceIndex = token.sentenceIndex;
        result.beginIndex = token.beginIndex;
        result.endIndex = token.endIndex;
        return result;
    }

    private static List<String> analyzed(String value, boolean sensitive) {
        List<String> result = new ArrayList<String>();
        Analyzer analyzer = queryAnalyzer(sensitive);
        try (TokenStream stream = analyzer.tokenStream("query", new StringReader(value))) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                String current = term.toString();
                result.add(sensitive ? current : current.toLowerCase(Locale.ROOT));
            }
            stream.end();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        } finally {
            analyzer.close();
        }
        return result;
    }

    private static String singleTerm(String value, boolean sensitive, String field) {
        List<String> terms = analyzed(value, sensitive);
        require(terms.size() == 1, "INVALID_" + field.toUpperCase(Locale.ROOT),
                field + " must produce exactly one canonical token");
        return terms.get(0);
    }

    private static Analyzer queryAnalyzer(final boolean sensitive) {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new StandardTokenizer();
                TokenStream stream = sensitive ? tokenizer
                        : new LowerCaseFilter(tokenizer);
                return new TokenStreamComponents(tokenizer, stream);
            }
        };
    }

    private MultiTermQuery.RewriteMethod rewrite() {
        return new MultiTermQuery.TopTermsScoringBooleanQueryRewrite(
                maxWildcardExpansions);
    }

    private CorpusIndexDocument snapshot(Document document) throws IOException {
        String key = cacheKey(document);
        CorpusIndexDocument cached = snapshotCache.get(key);
        if (cached != null) {
            return cached;
        }
        CorpusIndexDocument parsed = JSON.readValue(
                document.get(LuceneCorpusIndexService.SNAPSHOT),
                CorpusIndexDocument.class);
        snapshotCache.put(key, parsed);
        return parsed;
    }

    private static String cacheKey(Document document) {
        return String.valueOf(document.get(LuceneCorpusIndexService.FILE_ID)) + '\u0000'
                + String.valueOf(document.get(LuceneCorpusIndexService.CONTENT_HASH)) + '\u0000'
                + String.valueOf(document.get(
                        LuceneCorpusIndexService.SEGMENTATION_HASH)) + '\u0000'
                + String.valueOf(document.get(LuceneCorpusIndexService.CORPUS_IRI)) + '\u0000'
                + String.valueOf(document.get(LuceneCorpusIndexService.GRAPH_IRI));
    }

    private static void sort(List<Collocate> values, String metric, String order) {
        String selected = sortableMetric(metric);
        int direction = "ASC".equals(upper(order, "DESC")) ? 1 : -1;
        Comparator<Collocate> comparator = (left, right) -> {
            double leftScore = score(left, selected);
            double rightScore = score(right, selected);
            int compared = Double.compare(leftScore, rightScore) * direction;
            if (compared != 0) {
                return compared;
            }
            compared = Long.compare(left.fxy, right.fxy) * -1;
            return compared != 0 ? compared : left.term.compareTo(right.term);
        };
        Collections.sort(values, comparator);
    }

    private static String sortableMetric(String value) {
        String selected = upper(value, "LOG_DICE");
        if ("FREQUENCY".equals(selected)) {
            return selected;
        }
        try {
            CollocationStatisticsService.Metric.valueOf(selected);
            return selected;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("INVALID_SORT_METRIC: " + value);
        }
    }

    private static List<String> values(String singular, List<String> plural) {
        List<String> result = new ArrayList<String>();
        if (!blank(singular)) {
            result.add(singular);
        }
        if (plural != null) {
            result.addAll(plural);
        }
        return result;
    }

    private static double score(Collocate value, String metric) {
        if ("FREQUENCY".equals(metric)) {
            return value.fxy;
        }
        MetricValue score = value.metrics.get(metric);
        return score == null || !score.defined ? Double.NEGATIVE_INFINITY
                : score.value.doubleValue();
    }

    private static Set<String> normalizedSet(List<String> values, boolean sensitive) {
        Set<String> result = new HashSet<String>();
        if (values != null) {
            for (String value : values) {
                if (!blank(value)) {
                    result.add(canonical(value, sensitive));
                }
            }
        }
        return result;
    }

    private static boolean allowedDirection(int delta, String direction) {
        return "BOTH".equals(direction) || (delta < 0 && "LEFT".equals(direction))
                || (delta > 0 && "RIGHT".equals(direction));
    }

    private static String direction(String value) {
        String result = upper(value, "BOTH");
        require("LEFT".equals(result) || "RIGHT".equals(result)
                || "BOTH".equals(result), "INVALID_DIRECTION", value);
        return result;
    }

    private static String boundary(String value) {
        String result = upper(value, "DOCUMENT");
        require("DOCUMENT".equals(result) || "SENTENCE".equals(result),
                "INVALID_BOUNDARY", value);
        return result;
    }

    private static boolean termEquals(String actual, String expected, boolean sensitive) {
        return sensitive ? actual.equals(expected) : actual.equalsIgnoreCase(expected);
    }

    private static String canonical(String value, boolean sensitive) {
        return sensitive ? value : value.toLowerCase(Locale.ROOT);
    }

    private static int tokenAt(CorpusIndexDocument document, int codePoint, boolean begin) {
        for (CorpusIndexDocument.TokenData token : document.tokens) {
            if ((begin && token.beginIndex == codePoint)
                    || (!begin && token.endIndex == codePoint)) {
                return token.position;
            }
        }
        throw new IllegalArgumentException("HIT_NOT_ALIGNED: no canonical token at offset "
                + codePoint);
    }

    private static void validateTokenRange(CorpusIndexDocument document, int start, int end) {
        require(start >= 0 && end >= start && end < document.tokens.size(),
                "INVALID_TOKEN_RANGE", start + ":" + end);
    }

    private static int requiredInt(Integer value, String field) {
        require(value != null, "MISSING_KWIC_OFFSET", field + " is required");
        return value.intValue();
    }

    private static int bounded(Integer value, int fallback, int maximum, String field) {
        int result = value == null ? fallback : value.intValue();
        require(result >= 0 && result <= maximum, "INVALID_" + field.toUpperCase(Locale.ROOT),
                field + " must be between 0 and " + maximum);
        return result;
    }

    private static int boundedPositive(Integer value, int fallback, int maximum,
                                       String field) {
        int result = value == null ? fallback : value.intValue();
        require(result > 0 && result <= maximum, "INVALID_" + field.toUpperCase(Locale.ROOT),
                field + " must be between 1 and " + maximum);
        return result;
    }

    private static int cursor(String value) {
        if (blank(value)) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value);
            require(parsed >= 0, "INVALID_CURSOR", value);
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("INVALID_CURSOR: " + value);
        }
    }

    private static String validIri(String value) {
        try {
            URI iri = URI.create(required(value, "INVALID_IRI"));
            require(iri.isAbsolute(), "INVALID_IRI", value);
            return value;
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException
                    && error.getMessage() != null
                    && error.getMessage().startsWith("INVALID_IRI:")) {
                throw error;
            }
            throw new IllegalArgumentException("INVALID_IRI: " + value);
        }
    }

    private static String required(String value, String code) {
        require(!blank(value), code, "value is required");
        return value.trim();
    }

    private static String upper(String value, String fallback) {
        return blank(value) ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean equals(String left, String right) {
        return left != null && left.equals(right);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new IllegalArgumentException(code + ": " + message);
        }
    }

    private static int positive(String name, int fallback) {
        String value = LexOProperties.getProperty(name);
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static final class CandidateCounts {
        final String term;
        long fy;
        long fxy;
        final Set<String> documentsY = new HashSet<String>();
        final Set<String> documentsXY = new HashSet<String>();

        CandidateCounts(String term) {
            this.term = term;
        }
    }
}
