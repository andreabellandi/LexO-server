package it.cnr.ilc.lexo.manager.cpp;

import static it.cnr.ilc.lexo.manager.cpp.CppVocabulary.*;
import it.cnr.ilc.lexo.service.data.cpp.input.*;
import it.cnr.ilc.lexo.service.data.cpp.output.CppRunDetails;
import java.math.BigDecimal;
import java.util.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.*;
import org.eclipse.rdf4j.model.vocabulary.*;

/** CPP domain mapping; all statements receive a named graph in the repository. */
public final class CppRdfMapper {
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final CppResourceUris uris;
    public CppRdfMapper(CppResourceUris uris) { this.uris = uris; }

    public Model map(IRI run, CppRunCreateRequest r, String now, String key, String fingerprint) {
        Model m = new LinkedHashModel();
        m.add(run,RDF.TYPE,cpp("CollocationExtractionRun"));
        m.add(run,plan("correspondsToStep"),cpp("extractCollocations"));
        m.add(run,DCTERMS.CREATED,vf.createLiteral(now,XSD.DATETIME));
        m.add(run,DCTERMS.CREATOR,vf.createLiteral(r.author));
        if (key != null) {
            m.add(run,DCTERMS.IDENTIFIER,vf.createLiteral("cpp:idempotency:" + key));
            m.add(run,DCTERMS.IDENTIFIER,vf.createLiteral("cpp:payload-sha256:" + fingerprint));
        }
        // Lossless immutable declaration includes the exact Lucene subset selectors.
        // Associations are represented only by PROV triples, so PATCH has one source of truth.
        List<String> saved = r.collocations;
        try { r.collocations = null; m.add(run,DCTERMS.DESCRIPTION,vf.createLiteral(CppJsonCodec.write(r),iri(RDF.NAMESPACE + "JSON"))); }
        finally { r.collocations = saved; }
        IRI config = uris.child(run,"configuration");
        m.add(config,RDF.TYPE,cpp("CollocationExtractionConfiguration"));
        m.add(run,prov("used"),config);
        IRI snapshot = binding(m,run,config,"corpus",r.corpusSnapshot.memberIRIs.size() > 1
                ? "CorpusSelectionSnapshot" : "CorpusSnapshot");
        for (String member : r.corpusSnapshot.memberIRIs) { m.add(snapshot,prov("hadMember"),iri(member)); }
        IRI index = binding(m,run,config,"index","CorpusIndex");
        m.add(index,DCTERMS.IDENTIFIER,vf.createLiteral(r.index.id));
        m.add(index,prov("wasDerivedFrom"),snapshot);
        if (r.index.lastCommit != null) { m.add(index,DCTERMS.HAS_VERSION,vf.createLiteral(r.index.lastCommit)); }
        literal(m,run,config,"node","NodeValue",vf.createLiteral(r.search.node));
        literal(m,run,config,"windowLeft","WindowLeftValue",integer(r.search.leftWindow,XSD.NON_NEGATIVE_INTEGER));
        literal(m,run,config,"windowRight","WindowRightValue",integer(r.search.rightWindow,XSD.NON_NEGATIVE_INTEGER));
        literal(m,run,config,"sentenceBoundary","SentenceBoundaryValue",vf.createLiteral("SENTENCE".equals(r.search.boundary)));
        literal(m,run,config,"ordered","OrderedValue",vf.createLiteral(r.cppOptions.ordered));
        literal(m,run,config,"minimumFrequency","MinimumFrequencyValue",integer(r.search.minCooccurrenceFrequency,XSD.NON_NEGATIVE_INTEGER));
        literal(m,run,config,"direction","DirectionValue",vf.createLiteral(r.search.direction));
        literal(m,run,config,"caseSensitive","CaseSensitiveValue",vf.createLiteral(r.search.caseSensitive));
        literal(m,run,config,"sortOrder","SortOrderValue",vf.createLiteral(r.search.sortOrder));
        literal(m,run,config,"limit","LimitValue",integer(r.search.limit,XSD.POSITIVE_INTEGER));
        if (r.search.minFrequency != null) { literal(m,run,config,"minFrequency","MinFrequencyValue",integer(r.search.minFrequency,XSD.NON_NEGATIVE_INTEGER)); }
        if (r.search.minScore != null) { literal(m,run,config,"minScore","MinScoreValue",vf.createLiteral(BigDecimal.valueOf(r.search.minScore))); }
        bag(m,run,config,"measures","MeasureSetValue",r.search.metrics,true);
        bag(m,run,config,"excludeTerms","ExcludeTermsValue",r.search.excludeTerms,false);
        bag(m,run,config,"stopList","StopListValue",r.search.stoplist,false);
        IRI sort = binding(m,run,config,"sortBy","SortByValue");
        m.add(sort,score("selectsMeasure"),CppMeasureRegistry.individual(r.search.sortBy));
        IRI software = r.software.iri == null ? uris.child(run,"software") : iri(r.software.iri);
        m.add(run,prov("wasAssociatedWith"),software);
        if (r.software.iri == null) {
            m.add(software,RDF.TYPE,cpp("ExtractorSoftware"));
            m.add(software,RDFS.LABEL,vf.createLiteral(r.software.name));
            if (r.software.version != null) { m.add(software,iri("https://schema.org/softwareVersion"),vf.createLiteral(r.software.version)); }
        }
        if (r.execution != null) {
            if (r.execution.startedAtTime != null) { m.add(run,prov("startedAtTime"),vf.createLiteral(r.execution.startedAtTime,XSD.DATETIME)); }
            if (r.execution.endedAtTime != null) { m.add(run,prov("endedAtTime"),vf.createLiteral(r.execution.endedAtTime,XSD.DATETIME)); }
        }
        for (String c : r.collocations) { associate(m,run,iri(c)); }
        return m;
    }
    public static void associate(Model m, IRI run, IRI collocation) {
        m.add(run,prov("generated"),collocation);
        m.add(collocation,prov("wasGeneratedBy"),run);
        m.add(collocation,RDF.TYPE,cpp("CollocationObservation"));
        m.add(collocation,plan("correspondsToVariable"),cpp("collocationObservation"));
    }
    private IRI binding(Model m, IRI run, IRI config, String variable, String type) {
        IRI b = uris.child(run,variable);
        m.add(b,RDF.TYPE,cpp(type)); m.add(b,plan("correspondsToVariable"),cpp(variable));
        m.add(run,prov("used"),b); m.add(config,prov("hadMember"),b); return b;
    }
    private void literal(Model m, IRI run, IRI config, String variable, String type, Literal value) {
        IRI b = binding(m,run,config,variable,type);
        m.add(b,RDF.TYPE,cpp("LiteralParameterValue")); m.add(b,prov("value"),value);
    }
    private Literal integer(Number n, IRI datatype) { return vf.createLiteral(n.toString(),datatype); }
    private void bag(Model m, IRI run, IRI config, String variable, String type, List<String> values, boolean measures) {
        if (values.isEmpty()) { return; }
        IRI b = binding(m,run,config,variable,type); m.add(b,RDF.TYPE,RDF.BAG);
        for (int i=0; i<values.size(); i++) { m.add(b,iri(RDF.NAMESPACE + "_" + (i+1)),
                measures ? CppMeasureRegistry.individual(values.get(i)) : vf.createLiteral(values.get(i))); }
    }
    public CppRunDetails details(IRI run, Model model) {
        CppRunCreateRequest r;
        try { r = CppJsonCodec.read(one(model,run,DCTERMS.DESCRIPTION),CppRunCreateRequest.class); }
        catch (CppException e) {
            if (e.status == 413) { throw e; }
            throw new CppException(422,"CPP_EXPORT_INCONSISTENT","Stored CPP declaration is invalid");
        }
        CppRunDetails d = new CppRunDetails();
        d.id=uris.id(run); d.iri=run.stringValue(); d.createdAt=one(model,run,DCTERMS.CREATED);
        d.modifiedAt=one(model,run,DCTERMS.MODIFIED); d.author=one(model,run,DCTERMS.CREATOR);
        d.step=cpp("extractCollocations").stringValue(); d.node=r.search.node; d.search=r.search;
        r.software.iri=one(model,run,prov("wasAssociatedWith"));
        d.cppOptions=r.cppOptions; d.corpusSnapshot=r.corpusSnapshot; d.index=r.index; d.software=r.software; d.execution=r.execution;
        d.collocations=new ArrayList<String>();
        for (Value value : model.filter(run,prov("generated"),null).objects()) { d.collocations.add(value.stringValue()); }
        Collections.sort(d.collocations); d.collocationCount=d.collocations.size(); return d;
    }
    public static String one(Model m, Resource s, IRI p) {
        Set<Value> values=m.filter(s,p,null).objects();
        if (values.size()>1) { throw new CppException(422,"CPP_EXPORT_INCONSISTENT","Multiple values for a single-valued CPP field"); }
        return values.isEmpty()?null:values.iterator().next().stringValue();
    }
}
