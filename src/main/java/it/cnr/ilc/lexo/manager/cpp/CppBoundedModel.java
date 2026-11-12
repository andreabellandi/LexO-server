package it.cnr.ilc.lexo.manager.cpp;

import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;

/** Limits the in-memory extract before serialization, including large literal values. */
final class CppBoundedModel extends LinkedHashModel {
    private long characters;
    private final long maximum=CppResourceUris.limit("CPP.export.maxBytes",16777216);
    @Override public boolean add(Resource s, IRI p, Value o, Resource... contexts) {
        if(contains(s,p,o,contexts)) { return false; }
        long added=(long)s.stringValue().length()+p.stringValue().length()+o.stringValue().length();
        if(characters+added>maximum || size()>=CppResourceUris.limit("CPP.export.maxTriples",50000)) {
            throw new CppException(413,"CPP_LIMIT_EXCEEDED","RDF extract exceeds configured memory budget");
        }
        boolean changed=super.add(s,p,o,contexts);
        if(changed) { characters+=added; }
        return changed;
    }
}
