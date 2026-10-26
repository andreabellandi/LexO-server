package it.cnr.ilc.lexo.manager.text;

import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;

/** Shared definition of a usable lemma and its coverage. */
public final class LemmaStatistics {

    private LemmaStatistics() {
    }

    public static boolean usable(String lemma) {
        return lemma != null && !lemma.trim().isEmpty()
                && !"_".equals(lemma.trim());
    }

    public static int count(Model model, IRI wordType, IRI lemmaPredicate) {
        int result = 0;
        Set<Resource> words = model.filter(null, RDF.TYPE, wordType).subjects();
        for (Resource word : words) {
            for (Value value : model.filter(word, lemmaPredicate, null).objects()) {
                if (value instanceof Literal && usable(value.stringValue())) {
                    result++;
                    break;
                }
            }
        }
        return result;
    }

    public static double coverage(long tokenCount, long lemmaTokenCount) {
        return tokenCount == 0L ? 0.0d
                : (double) lemmaTokenCount / (double) tokenCount;
    }
}
