package it.cnr.ilc.lexo.manager.cpp;

import it.cnr.ilc.lexo.LexOProperties;
import java.net.URI;
import java.util.UUID;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

public final class CppResourceUris {
    private final String base;
    public CppResourceUris() {
        base = iri(LexOProperties.getProperty("CPP.resourceNamespace", "https://lexo.ilc.cnr.it/id/cpp/")).stringValue();
        if (!base.endsWith("/") || base.startsWith(CppVocabulary.CPP)) {
            throw new IllegalStateException("CPP.resourceNamespace must be an application namespace ending in /");
        }
    }
    public IRI run(String id) { return iri(base + "run/" + canonicalId(id)); }
    public IRI child(IRI run, String kind) { return iri(run.stringValue() + "/" + kind + "/" + UUID.randomUUID()); }
    public String id(IRI run) {
        if (!run.stringValue().startsWith(base + "run/")) {
            throw new CppException(400, "CPP_INVALID_IRI", "Run IRI is outside the application namespace");
        }
        return canonicalId(run.stringValue().substring((base + "run/").length()));
    }
    public boolean owns(IRI run, org.eclipse.rdf4j.model.Value child) {
        return child instanceof IRI && child.stringValue().startsWith(run.stringValue() + "/");
    }
    public static String canonicalId(String id) {
        try {
            if (id == null || !UUID.fromString(id).toString().equals(id)) { throw new IllegalArgumentException(); }
            return id;
        } catch (IllegalArgumentException e) { throw CppException.invalid("A canonical lowercase UUID is required"); }
    }
    public static IRI iri(String text) {
        try {
            if (text == null || !URI.create(text).isAbsolute() || text.matches(".*[\\s<>\\\"{}|^`\\\\].*")) {
                throw new IllegalArgumentException();
            }
            return SimpleValueFactory.getInstance().createIRI(text);
        } catch (IllegalArgumentException e) {
            throw new CppException(400, "CPP_INVALID_IRI", "An absolute RDF IRI is required");
        }
    }
    public static int limit(String key, int fallback) {
        int value = Integer.parseInt(LexOProperties.getProperty(key, String.valueOf(fallback)));
        if (value < 1) { throw new IllegalStateException("CPP limits must be positive"); }
        return value;
    }
}
