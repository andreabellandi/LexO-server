package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.manager.text.model.ParsedTextDocument;
import it.cnr.ilc.lexo.manager.text.model.SegmentationSource;
import it.cnr.ilc.lexo.manager.text.model.Token;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalSegmentationServiceTest {

    private static final String BASE = "https://example.org/texts/";
    private static final String NIF =
            "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private final ControlledCommonMarkParser parser = new ControlledCommonMarkParser();

    @Test
    @DisplayName("StandardTokenizer defines raw token surfaces, positions and NIF words")
    void tokenizesRequiredRawCasesIntoTheSharedCanonicalSequence() throws Exception {
        Map<String, List<String>> cases = new LinkedHashMap<String, List<String>>();
        cases.put("uno due ciao quattro", Arrays.asList("uno", "due", "ciao", "quattro"));
        cases.put("città perché è così", Arrays.asList("città", "perché", "è", "così"));
        cases.put("Casa casa CASA", Arrays.asList("Casa", "casa", "CASA"));
        cases.put("l'amico dell'uomo", Arrays.asList("l'amico", "dell'uomo"));
        cases.put("porta-glielo e-mail", Arrays.asList("porta", "glielo", "e", "mail"));
        cases.put("casa, bella; grande!", Arrays.asList("casa", "bella", "grande"));

        int sequence = 0;
        for (Map.Entry<String, List<String>> sample : cases.entrySet()) {
            ParsedTextDocument document = parser.parsePlainText(sample.getKey());
            assertThat(document.segmentationSource)
                    .as(sample.getKey()).isEqualTo(SegmentationSource.LUCENE_STANDARD);
            assertThat(surfaces(document)).as(sample.getKey())
                    .containsExactlyElementsOf(sample.getValue());

            CorpusIndexDocument indexed = CorpusIndexDocument.from(
                    "raw-" + sequence, BASE + "raw-" + sequence + "#context",
                    null, BASE + "graphs/raw-" + sequence, document);
            assertThat(indexed.tokens).extracting(token -> token.position)
                    .containsExactly(sequence(document.tokens.size()));
            assertThat(indexed.tokens).extracting(token -> token.surface)
                    .containsExactlyElementsOf(sample.getValue());

            Model nif = new NifModelWriter(BASE,
                    "https://example.org/structure#").build(
                            "raw-" + sequence, "sample.txt", document);
            IRI word = SimpleValueFactory.getInstance().createIRI(NIF + "Word");
            IRI anchor = SimpleValueFactory.getInstance().createIRI(NIF + "anchorOf");
            List<String> nifWords = nif.filter(null, RDF.TYPE, word).subjects().stream()
                    .sorted((left, right) -> begin(nif, left) - begin(nif, right))
                    .map(subject -> nif.filter(subject, anchor, null).objects().iterator()
                            .next().stringValue())
                    .collect(Collectors.toList());
            assertThat(nifWords).containsExactlyElementsOf(sample.getValue());
            sequence++;
        }
    }

    @Test
    @DisplayName("BOM, CRLF, NFC and emoji offsets are identical in NIF and Lucene")
    void sharesCanonicalUnicodeTextAndCoordinateSystems() throws Exception {
        ParsedTextDocument document = parser.parsePlainText(
                "\uFEFFuno 😀 a\u0300\r\nmondo");
        String canonical = "uno 😀 à\nmondo";
        assertThat(document.cleanText).isEqualTo(canonical);

        CorpusIndexDocument indexed = CorpusIndexDocument.from("unicode",
                BASE + "unicode#context", null, BASE + "graphs/unicode", document);
        assertThat(indexed.content).isEqualTo(canonical);
        CorpusIndexDocument.TokenData mondo = indexed.tokens.get(3);
        assertThat(mondo.startUtf16).isEqualTo(canonical.indexOf("mondo"));
        assertThat(mondo.beginIndex).isEqualTo(8);
        assertThat(mondo.startUtf16).isEqualTo(9);
        assertThat(UnicodeOffsetMapper.substringByCodePoint(canonical,
                mondo.beginIndex, mondo.endIndex)).isEqualTo("mondo");

        Model nif = new NifModelWriter(BASE,
                "https://example.org/structure#").build(
                        "unicode", "unicode.txt", document);
        IRI context = SimpleValueFactory.getInstance().createIRI(
                BASE + "unicode#context");
        assertThat(nif.filter(context,
                SimpleValueFactory.getInstance().createIRI(NIF + "isString"), null)
                .objects()).extracting(Object::toString)
                .anyMatch(value -> value.contains(canonical));
    }

    private static List<String> surfaces(ParsedTextDocument document) {
        return document.tokens.stream().map(token -> token.text)
                .collect(Collectors.toList());
    }

    private static Integer[] sequence(int size) {
        Integer[] result = new Integer[size];
        for (int index = 0; index < size; index++) {
            result[index] = Integer.valueOf(index);
        }
        return result;
    }

    private static int begin(Model model, Resource subject) {
        IRI predicate = SimpleValueFactory.getInstance().createIRI(NIF + "beginIndex");
        return ((Literal) model.filter(subject, predicate, null).objects()
                .iterator().next()).intValue();
    }
}
