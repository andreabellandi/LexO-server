package it.cnr.ilc.lexo.manager.cpp;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

public final class CppVocabulary {
    public static final String CPP = "https://ilc.cnr.it/klab/ontology/cpp#";
    public static final String SCORE = "https://ilc.cnr.it/klab/ontology/cooccurrence-measures#";
    public static final String PROV = "http://www.w3.org/ns/prov#";
    public static final String PLAN = "http://purl.org/net/p-plan#";
    public static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    public static IRI cpp(String n) { return iri(CPP + n); }
    public static IRI prov(String n) { return iri(PROV + n); }
    public static IRI plan(String n) { return iri(PLAN + n); }
    public static IRI score(String n) { return iri(SCORE + n); }
    public static IRI iri(String n) { return SimpleValueFactory.getInstance().createIRI(n); }
    private CppVocabulary() { }
}
