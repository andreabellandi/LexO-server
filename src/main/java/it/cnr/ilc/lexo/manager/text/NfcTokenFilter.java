package it.cnr.ilc.lexo.manager.text;

import java.io.IOException;
import java.text.Normalizer;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Applies the canonical Unicode form shared by corpus indexing and queries. */
final class NfcTokenFilter extends TokenFilter {

    private final CharTermAttribute term = addAttribute(CharTermAttribute.class);

    NfcTokenFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) {
            return false;
        }
        String normalized = Normalizer.normalize(term.toString(), Normalizer.Form.NFC);
        term.setEmpty().append(normalized);
        return true;
    }
}
