package it.cnr.ilc.lexo.manager;

import static it.cnr.ilc.lexo.manager.cpp.CppVocabulary.*;
import it.cnr.ilc.lexo.manager.cpp.*;
import it.cnr.ilc.lexo.service.data.cpp.input.*;
import it.cnr.ilc.lexo.service.data.cpp.output.*;
import java.time.Instant;
import java.util.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.*;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.repository.*;
import org.slf4j.LoggerFactory;

/** Registers declared extraction provenance; never executes Lucene or changes FrAC. */
public class CppRunManager implements Manager {
    // Shared across ManagerFactory instances as well as manually constructed managers.
    // Deploy CPP with one application writer; this is not a distributed lock.
    private static final Object WRITE_LOCK=new Object();
    private final CppRunRepository repository;
    private final CppResourceUris uris=new CppResourceUris();
    private final CppRdfMapper mapper=new CppRdfMapper(uris);
    private final ValueFactory vf=SimpleValueFactory.getInstance();
    public CppRunManager() { this(new CppRunRepository()); }
    public CppRunManager(CppRunRepository repository) { this.repository=repository; }

    public Creation create(CppRunCreateRequest request, String author, String key) {
        CppRunCreateRequest r=CppJsonCodec.normalize(request,author);
        if(key!=null && !key.matches("[A-Za-z0-9._~:-]{1,128}")) { throw CppException.invalid("Invalid Idempotency-Key"); }
        String fingerprint=CppJsonCodec.fingerprint(r);
        synchronized(WRITE_LOCK) {
            return repository.access(true,c->{
                if(key!=null) {
                    try(RepositoryResult<Statement> rows=c.getStatements(null,DCTERMS.IDENTIFIER,vf.createLiteral("cpp:idempotency:"+key),false,repository.graph)) {
                        while(rows.hasNext()) {
                            Resource old=rows.next().getSubject();
                            if(old instanceof IRI && repository.exists(c,(IRI)old)
                                    && c.hasStatement(old,DCTERMS.CREATOR,vf.createLiteral(r.author),false,repository.graph)) {
                                if(!c.hasStatement(old,DCTERMS.IDENTIFIER,vf.createLiteral("cpp:payload-sha256:"+fingerprint),false,repository.graph)) {
                                    throw new CppException(409,"CPP_IDEMPOTENCY_CONFLICT","Idempotency key was used for a different payload");
                                }
                                return new Creation(mapper.details((IRI)old,repository.runModel(c,(IRI)old)),true);
                            }
                        }
                    }
                }
                IRI run;
                do { run=uris.run(UUID.randomUUID().toString()); }
                while(c.hasStatement(run,null,null,false,repository.graph));
                List<IRI> graphs=repository.applicationGraphs(c);
                for(String col:r.collocations) {
                    repository.requireCollocation(c,iri(col),graphs,false); repository.available(c,iri(col),run);
                }
                if(r.software.iri!=null) { verifySoftware(c,r.software,graphs); }
                Model model=mapper.map(run,r,Instant.now().toString(),key,fingerprint);
                c.add(model,repository.graph);
                return new Creation(mapper.details(run,model),false);
            });
        }
    }
    private void verifySoftware(RepositoryConnection c, CppSoftwareInput software, List<IRI> graphs) {
        List<IRI> allowed=new ArrayList<IRI>(graphs); allowed.add(repository.graph);
        for(IRI g:allowed) {
            if(c.hasStatement(iri(software.iri),RDF.TYPE,cpp("ExtractorSoftware"),false,g)
                    && c.hasStatement(iri(software.iri),RDFS.LABEL,vf.createLiteral(software.name),false,g)
                    && (software.version==null || c.hasStatement(iri(software.iri),iri("https://schema.org/softwareVersion"),vf.createLiteral(software.version),false,g))) { return; }
        }
        throw new CppException(422,"CPP_INVALID_SEMANTICS","Existing software description could not be verified");
    }
    public CppRunDetails get(String id) {
        IRI run=uris.run(id);
        return repository.access(false,c->mapper.details(run,repository.runModel(c,run)));
    }
    public CppRunPage list(CppRunFilter f) {
        if(f==null) { f=new CppRunFilter(); }
        if(f.limit<1 || f.limit>CppResourceUris.limit("CPP.list.maxLimit",500) || f.offset<0) { throw CppException.invalid("Invalid pagination"); }
        if(f.node!=null && f.node.length()>CppResourceUris.limit("CPP.write.maxStringLength",8192)) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","Node filter too long"); }
        f.from=CppJsonCodec.time(f.from); f.to=CppJsonCodec.time(f.to);
        if(f.from!=null && f.to!=null && Instant.parse(f.from).isAfter(Instant.parse(f.to))) { throw CppException.invalid("from must precede to"); }
        final CppRunFilter filter=f;
        return repository.access(false,c->{
            CppRunPage page=new CppRunPage(); page.limit=filter.limit; page.offset=filter.offset;
            page.total=repository.count(c,filter);
            for(IRI run:repository.find(c,filter,true)) {
                CppRunDetails d=mapper.details(run,repository.runModel(c,run));
                CppRunSummary s=new CppRunSummary(); s.id=d.id; s.iri=d.iri; s.node=d.node;
                s.createdAt=d.createdAt; s.collocationCount=d.collocationCount; page.items.add(s);
            }
            return page;
        });
    }
    public CppRunDetails patchAssociations(String id, CppRunPatchRequest r, String actor) {
        IRI run=uris.run(id);
        if(r==null) { throw CppException.invalid("Patch is required"); }
        List<String> add=CppJsonCodec.iris(r.addCollocations,"CPP.write.maxCollocations",100,false);
        List<String> remove=CppJsonCodec.iris(r.removeCollocations,"CPP.write.maxCollocations",100,false);
        Set<String> intersection=new HashSet<String>(add); intersection.retainAll(remove);
        if(add.isEmpty() && remove.isEmpty() || !intersection.isEmpty()) { throw CppException.invalid("A non-empty, non-overlapping association patch is required"); }
        synchronized(WRITE_LOCK) {
            CppRunDetails result=repository.access(true,c->{
                Model old=repository.runModel(c,run);
                Set<String> remaining=new TreeSet<String>(); for(Value v:old.filter(run,prov("generated"),null).objects()) { remaining.add(v.stringValue()); }
                remaining.removeAll(remove); remaining.addAll(add);
                if(remaining.isEmpty()) { throw new CppException(409,"CPP_LAST_COLLOCATION","Delete the run to remove its last collocation"); }
                if(remaining.size()>CppResourceUris.limit("CPP.write.maxCollocations",100)) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","Too many collocations"); }
                List<IRI> graphs=repository.applicationGraphs(c);
                for(String col:add) { repository.requireCollocation(c,iri(col),graphs,false); repository.available(c,iri(col),run); }
                for(String col:remove) { repository.detach(c,run,iri(col)); }
                Model added=new LinkedHashModel(); for(String col:add) { CppRdfMapper.associate(added,run,iri(col)); }
                c.add(added,repository.graph);
                c.remove(run,DCTERMS.MODIFIED,null,repository.graph);
                c.add(run,DCTERMS.MODIFIED,vf.createLiteral(Instant.now().toString(),XSD.DATETIME),repository.graph);
                return mapper.details(run,repository.runModel(c,run));
            });
            LoggerFactory.getLogger(getClass()).info("CPP associations corrected run={} count={}",id,result.collocationCount);
            return result;
        }
    }
    public void delete(String id) {
        IRI run=uris.run(id);
        synchronized(WRITE_LOCK) {
            repository.access(true,c->{
                Model model=repository.runModel(c,run);
                for(Value col:model.filter(run,prov("generated"),null).objects()) { repository.detach(c,run,(IRI)col); }
                for(Resource subject:model.subjects()) {
                    if(subject.equals(run) || uris.owns(run,subject)) { c.remove(subject,null,null,repository.graph); }
                }
                return null;
            });
        }
    }
    public CppTurtleExport exportTurtle(List<String> requested, boolean includeCollocations) {
        Model model=repository.access(false,c->{
            Set<IRI> runs=new TreeSet<IRI>(Comparator.comparing(IRI::stringValue));
            if(requested==null || requested.isEmpty()) { runs.addAll(repository.find(c,new CppRunFilter(),false)); }
            else {
                if(requested.size()>CppResourceUris.limit("CPP.export.maxRuns",1000)) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","Too many selected runs"); }
                for(String text:requested) { IRI run=CppResourceUris.iri(text); uris.id(run); repository.requireRun(c,run); runs.add(run); }
            }
            Model result=CppRunRepository.boundedModel(); Set<IRI> collocations=new LinkedHashSet<IRI>();
            for(IRI run:runs) {
                Model one=repository.runModel(c,run);
                for(Statement st:one) { CppRunRepository.addBounded(result,st); }
                for(Value col:one.filter(run,prov("generated"),null).objects()) { collocations.add((IRI)col); }
            }
            if(includeCollocations) {
                List<IRI> graphs=repository.applicationGraphs(c);
                for(IRI col:collocations) { repository.exportCollocation(c,col,graphs,result); }
            }
            return result;
        });
        return CppTurtleExport.prepare(model);
    }
    public static final class Creation {
        public final CppRunDetails run;
        public final boolean replayed;
        public Creation(CppRunDetails run,boolean replayed) { this.run=run; this.replayed=replayed; }
    }
}
