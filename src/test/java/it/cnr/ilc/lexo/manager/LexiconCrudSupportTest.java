package it.cnr.ilc.lexo.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

class LexiconCrudSupportTest {

    @Test
    void createsResourceUriWithConfiguredPartsAndNormalizedTimestamp() {
        Timestamp timestamp = Timestamp.valueOf("2026-07-31 14:05:06.789");
        String uri = LexiconCrudSupport.newResourceUri(
                timestamp,
                "https://example.org/lexicon#",
                "LexO_",
                "yyyy MM dd HH:mm:ss.SSS");

        assertThat(uri).isEqualTo(
                "https://example.org/lexicon#LexO_2026073114*05*06*789");
        assertThat(LexiconCrudSupport.formatTimestamp(timestamp,
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"))
                .matches("2026-07-31T14:05:06\\.789[+-][0-9]{2}:[0-9]{2}");
    }

    @Test
    void normalizesMissingAndBlankAuthors() {
        assertThat(LexiconCrudSupport.author(null)).isEqualTo("anonymous");
        assertThat(LexiconCrudSupport.author("")).isEqualTo("anonymous");
        assertThat(LexiconCrudSupport.author("   ")).isEqualTo("anonymous");
        assertThat(LexiconCrudSupport.author("editor")).isEqualTo("editor");
    }

    @Test
    void createsOneNormalizedLexicalNamedGraphPerLanguage() {
        assertThat(LexiconCrudSupport.lexicalGraphUri("IT"))
                .isEqualTo("https://lexo.ilc.cnr.it/graphs/lexical/lexica/it");
        assertThat(LexiconCrudSupport.lexicalGraphUri("en"))
                .isEqualTo("https://lexo.ilc.cnr.it/graphs/lexical/lexica/en");
        assertThat(LexiconCrudSupport.lexicalGraphUri("ita"))
                .isEqualTo("https://lexo.ilc.cnr.it/graphs/lexical/lexica/ita");
    }

    @Test
    void rejectsInvalidLanguagesForLexicalNamedGraphs() {
        assertThatThrownBy(() -> LexiconCrudSupport.lexicalGraphUri(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("MISSING_LANGUAGE:");
        assertThatThrownBy(() -> LexiconCrudSupport.lexicalGraphUri("zz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_LANGUAGE:");
        assertThatThrownBy(() -> LexiconCrudSupport.lexicalGraphUri("Italian"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("INVALID_LANGUAGE:");
    }

    @Test
    void exposesTheRequiredLexicalPrefixesAndExpandsCompactIris() {
        assertThat(LexiconCrudSupport.lexicalPrefixes())
                .containsEntry("decomp", "http://www.w3.org/ns/lemon/decomp#")
                .containsEntry("vartrans", "http://www.w3.org/ns/lemon/vartrans#")
                .containsEntry("ontolex", "http://www.w3.org/ns/lemon/ontolex#")
                .containsEntry("synsem", "http://www.w3.org/ns/lemon/synsem#")
                .containsEntry("lexinfo", "http://www.lexinfo.net/ontology/3.0/lexinfo#")
                .containsEntry("lime", "http://www.w3.org/ns/lemon/lime#")
                .containsEntry("lexicog", "http://www.w3.org/ns/lemon/lexicog#");
        assertThat(LexiconCrudSupport.expandLexicalIri("ontolex:LexicalEntry"))
                .isEqualTo("http://www.w3.org/ns/lemon/ontolex#LexicalEntry");
        assertThat(LexiconCrudSupport.expandLexicalIri("lexinfo:noun"))
                .isEqualTo("http://www.lexinfo.net/ontology/3.0/lexinfo#noun");
    }
}
