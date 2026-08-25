package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.manager.text.model.JsonTextImport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TextJsonImportParserTest {

    private final TextJsonImportParser parser = new TextJsonImportParser();

    @Test
    @DisplayName("Fixed JSON imports preserve text metadata, corpus and RDF attestation metadata")
    void parsesTheFixedSchema() {
        JsonTextImport document = parser.parse("{"
                + "\"metadata\":{"
                + "\"id\":\"source-1\","
                + "\"author\":[\"one\",\"two\"],"
                + "\"corpus\":\"corpus-a\"},"
                + "\"text\":{\"type\":\"txt\",\"content\":\"A😀B\"},"
                + "\"attestations\":[{"
                + "\"id\":\"323\","
                + "\"observable\":\"https://example.org/sense\","
                + "\"type\":\"http://www.w3.org/ns/lemon/ontolex#LexicalSense\","
                + "\"value\":\"😀\",\"gloss\":\"emoji\","
                + "\"start_char\":1,\"end_char\":2,"
                + "\"metadata\":[{"
                + "\"property\":\"https://example.org/confidence\","
                + "\"values\":[{\"value\":\"0.9\",\"type\":\"literal\","
                + "\"datatype\":\"http://www.w3.org/2001/XMLSchema#decimal\"}]}]}]}");

        assertThat(document.metadataPresent).isTrue();
        assertThat(document.metadata.get("id")).containsExactly("source-1");
        assertThat(document.metadata.get("author")).containsExactly("one", "two");
        assertThat(document.metadata).doesNotContainKey("corpus");
        assertThat(document.corpusId).isEqualTo("corpus-a");
        assertThat(document.content).isEqualTo("A😀B");
        assertThat(document.attestations).hasSize(1);
        JsonTextImport.AttestationInput attestation = document.attestations.get(0);
        assertThat(attestation.id).isEqualTo("323");
        assertThat(attestation.start).isEqualTo(1);
        assertThat(attestation.end).isEqualTo(2);
        assertThat(attestation.metadata).hasSize(1);
        assertThat(attestation.metadata.get(0).values.get(0).datatype)
                .isEqualTo("http://www.w3.org/2001/XMLSchema#decimal");
    }

    @Test
    @DisplayName("Metadata and attestations are optional in a JSON text import")
    void acceptsTheMinimalSchema() {
        JsonTextImport document = parser.parse(
                "{\"text\":{\"type\":\"txt\",\"content\":\"Testo.\"}}");

        assertThat(document.metadataPresent).isFalse();
        assertThat(document.metadata).isEmpty();
        assertThat(document.corpusId).isNull();
        assertThat(document.attestations).isEmpty();
    }

    @Test
    @DisplayName("An empty observable remains a per-attestation semantic error")
    void leavesSemanticAttestationValidationToTheImporter() {
        assertThatCode(() -> parser.parse("{"
                + "\"text\":{\"type\":\"txt\",\"content\":\"Testo.\"},"
                + "\"attestations\":[{\"observable\":\"\","
                + "\"type\":\"http://www.w3.org/ns/lemon/ontolex#LexicalConcept\","
                + "\"value\":\"Testo\",\"gloss\":\"Testo\","
                + "\"start_char\":0,\"end_char\":5}]}"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Unknown text metadata rejects the complete bulk with a stable code")
    void rejectsUnknownTextMetadata() {
        assertThatThrownBy(() -> parser.parse("{"
                + "\"metadata\":{\"language\":\"it\"},"
                + "\"text\":{\"type\":\"txt\",\"content\":\"Testo.\"}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("BULK_UNKNOWN_TEXT_METADATA:");
    }

    @Test
    @DisplayName("Unknown structural fields and duplicate fields reject the JSON")
    void rejectsUnknownAndDuplicateFields() {
        assertThatThrownBy(() -> parser.parse("{"
                + "\"text\":{\"type\":\"txt\",\"content\":\"Testo.\",\"html\":false}}"))
                .hasMessageStartingWith("BULK_UNKNOWN_JSON_TEXT_FIELD:");
        assertThatThrownBy(() -> parser.parse("{"
                + "\"text\":{\"type\":\"txt\",\"type\":\"txt\","
                + "\"content\":\"Testo.\"}}"))
                .hasMessageStartingWith("BULK_INVALID_JSON:");
        assertThatThrownBy(() -> parser.parse("{"
                + "\"text\":{\"type\":\"txt\",\"content\":\"Testo.\"}}{}"))
                .hasMessageStartingWith("BULK_INVALID_JSON:");
    }

    @Test
    @DisplayName("Only txt is accepted as the embedded text type")
    void rejectsUnsupportedEmbeddedTextTypes() {
        assertThatThrownBy(() -> parser.parse(
                "{\"text\":{\"type\":\"markdown\",\"content\":\"# title\"}}"))
                .hasMessageStartingWith("BULK_UNSUPPORTED_JSON_TEXT_TYPE:");
    }

    @Test
    @DisplayName("Attestation shape errors reject the JSON before conversion")
    void rejectsInvalidAttestationShape() {
        assertThatThrownBy(() -> parser.parse("{"
                + "\"text\":{\"type\":\"txt\",\"content\":\"Testo.\"},"
                + "\"attestations\":[{\"observable\":\"https://example.org/entry\","
                + "\"type\":\"http://www.w3.org/ns/lemon/ontolex#LexicalEntry\","
                + "\"value\":\"Testo\",\"gloss\":\"Testo\","
                + "\"start_char\":\"0\",\"end_char\":5}]}"))
                .hasMessageStartingWith("BULK_INVALID_JSON_FIELD_TYPE:");
    }
}
