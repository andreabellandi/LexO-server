package it.cnr.ilc.lexo.manager.wordnet;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Stable unpadded Base64URL encoding for RDF resource IRIs in REST paths. */
public final class WordnetIdCodec {

    private WordnetIdCodec() {
    }

    public static String encode(String iri) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                iri.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String id) {
        if (id == null || id.isEmpty() || id.indexOf('=') >= 0
                || !id.matches("[A-Za-z0-9_-]+")) {
            throw invalid();
        }
        try {
            String iri = new String(Base64.getUrlDecoder().decode(id),
                    StandardCharsets.UTF_8);
            URI parsed = new URI(iri);
            if (!parsed.isAbsolute() || parsed.getScheme() == null
                    || !id.equals(encode(iri))) {
                throw invalid();
            }
            return iri;
        } catch (IllegalArgumentException | URISyntaxException ex) {
            throw invalid();
        }
    }

    private static WordnetException invalid() {
        return new WordnetException(400, "INVALID_RESOURCE_ID",
                "Resource id must be the unpadded Base64URL encoding of an absolute IRI");
    }
}
