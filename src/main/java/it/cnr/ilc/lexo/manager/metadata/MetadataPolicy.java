package it.cnr.ilc.lexo.manager.metadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;

/** Global protected-predicate policy shared by metadata for every entity. */
public final class MetadataPolicy {

    private static final List<String> PROTECTED_NAMESPACES =
            Collections.unmodifiableList(Arrays.asList(
                    "http://www.w3.org/ns/lemon/ontolex#",
                    "http://www.w3.org/ns/lemon/frac#",
                    "http://www.w3.org/ns/lemon/lime#",
                    "http://www.w3.org/ns/lemon/vartrans#",
                    "http://www.w3.org/ns/lemon/synsem#",
                    "http://www.w3.org/2004/02/skos/core#",
                    "http://www.w3.org/ns/lemon/decomp#"));

    private static final Set<String> EXACT_PROTECTED_PROPERTIES =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    RDF.TYPE.stringValue(),
                    RDF.VALUE.stringValue(),
                    DCTERMS.CREATOR.stringValue(),
                    DCTERMS.CREATED.stringValue(),
                    DCTERMS.MODIFIED.stringValue())));

    private MetadataPolicy() {
    }

    public static boolean isProtected(String property) {
        if (property == null) {
            return true;
        }
        if (EXACT_PROTECTED_PROPERTIES.contains(property)) {
            return true;
        }
        for (String namespace : PROTECTED_NAMESPACES) {
            if (property.startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> exactProtectedProperties() {
        return EXACT_PROTECTED_PROPERTIES;
    }

    public static List<String> protectedNamespaces() {
        return PROTECTED_NAMESPACES;
    }
}
