package it.cnr.ilc.lexo.manager.cpp;

import static it.cnr.ilc.lexo.manager.cpp.CppVocabulary.*;
import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.LexiconCrudSupport;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import it.cnr.ilc.lexo.service.data.cpp.input.CppRunFilter;
import it.cnr.ilc.lexo.LexOProperties;
import java.util.*;
import java.util.function.Function;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.*;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.*;

/** LEXICON-only connection lifecycle, graph policy and bounded explicit RDF reads. */
public class CppRunRepository {
    private final Repository supplied;
    public final IRI graph;
    private final CppResourceUris uris = new CppResourceUris();
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    public CppRunRepository() { this(null); }
    public CppRunRepository(Repository supplied) {
        this.supplied = supplied;
        graph = CppResourceUris.iri(LexOProperties.getProperty("GraphDb.cppNamedGraph", "https://lexo.ilc.cnr.it/graphs/lexical/cpp/runs"));
        if (graph.stringValue().equals(LexicalNamedGraphs.schemaGraphUri())
                || allowedGraph(graph) || graph.stringValue().equals(LexOProperties.getProperty("Bootstrap.metadata.namedGraph","https://lexo.ilc.cnr.it/graphs/bootstrap"))) {
            throw new IllegalStateException("CPP named graph must be separate from schema and lexical data");
        }
    }
    public <T> T access(boolean write, Function<RepositoryConnection,T> action) {
        RepositoryConnection c;
        try { c = supplied == null ? GraphDbUtil.getConnection(RepositoryTarget.LEXICON) : supplied.getConnection(); }
        catch (RepositoryException e) { throw new CppException(503,"CPP_REPOSITORY_UNAVAILABLE","Lexical repository is unavailable"); }
        try {
            if (write) { c.begin(); }
            T value = action.apply(c);
            if (write) { beforeCommit(c); c.commit(); }
            return value;
        } catch (RuntimeException e) {
            if (c.isActive()) { c.rollback(); }
            throw e;
        } finally {
            if (supplied == null) { GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON,c); }
            else { c.close(); }
        }
    }
    /** Fault-injection seam for verifying the transaction boundary. */
    protected void beforeCommit(RepositoryConnection c) { }
    public boolean exists(RepositoryConnection c, IRI run) { return c.hasStatement(run,RDF.TYPE,cpp("CollocationExtractionRun"),false,graph); }
    public void requireRun(RepositoryConnection c, IRI run) {
        if (!exists(c,run)) { throw new CppException(404,"CPP_RUN_NOT_FOUND","CPP run does not exist"); }
    }
    public Model subject(RepositoryConnection c, Resource s, IRI context) {
        Model m = new CppBoundedModel();
        try (RepositoryResult<Statement> rows = c.getStatements(s,null,null,false,context)) {
            while (rows.hasNext()) { addBounded(m,rows.next()); }
        }
        return m;
    }
    public Model runModel(RepositoryConnection c, IRI run) {
        requireRun(c,run);
        Model m = new CppBoundedModel(); Set<Resource> seen=new HashSet<Resource>();
        Deque<Resource> pending=new ArrayDeque<Resource>(); pending.add(run);
        while (!pending.isEmpty()) {
            Resource s=pending.removeFirst(); if (!seen.add(s)) { continue; }
            for (Statement st : subject(c,s,graph)) {
                addBounded(m,st);
                if (uris.owns(run,st.getObject())) { pending.add((Resource)st.getObject()); }
            }
        }
        for (Value collocation : m.filter(run,prov("generated"),null).objects()) {
            if (!(collocation instanceof IRI)) { inconsistent(); }
            IRI col=(IRI)collocation;
            try (RepositoryResult<Statement> generators=c.getStatements(col,prov("wasGeneratedBy"),null,false,graph)) {
                while(generators.hasNext()) { if(!run.equals(generators.next().getObject())) { inconsistent(); } }
            }
            if (!c.hasStatement(col,prov("wasGeneratedBy"),run,false,graph)
                    || !c.hasStatement(col,RDF.TYPE,cpp("CollocationObservation"),false,graph)
                    || !c.hasStatement(col,plan("correspondsToVariable"),cpp("collocationObservation"),false,graph)) { inconsistent(); }
            m.add(col,prov("wasGeneratedBy"),run);
            m.add(col,RDF.TYPE,cpp("CollocationObservation"));
            m.add(col,plan("correspondsToVariable"),cpp("collocationObservation"));
        }
        if (m.filter(run,prov("generated"),null).isEmpty()) { inconsistent(); }
        return m;
    }
    public List<IRI> applicationGraphs(RepositoryConnection c) {
        List<IRI> graphs = new ArrayList<IRI>();
        try (RepositoryResult<Resource> rows=c.getContextIDs()) {
            while(rows.hasNext()) { Resource g=rows.next(); if (g instanceof IRI && allowedGraph((IRI)g) && !g.equals(graph)) { graphs.add((IRI)g); } }
        }
        graphs.sort(Comparator.comparing(IRI::stringValue)); return graphs;
    }
    public static boolean allowedGraph(IRI g) {
        String value=g.stringValue();
        if (value.equals(LexicalNamedGraphs.schemaGraphUri())
                || value.equals(LexOProperties.getProperty("Bootstrap.metadata.namedGraph","https://lexo.ilc.cnr.it/graphs/bootstrap"))) { return false; }
        if (value.equals(LexicalNamedGraphs.lexiconGraphUri()) || value.equals(LexiconCrudSupport.lexicalConceptGraphUri())) { return true; }
        String prefix=LexiconCrudSupport.LEXICAL_GRAPH_BASE_URI;
        if (!value.startsWith(prefix)) { return false; }
        try { return value.equals(LexiconCrudSupport.lexicalGraphUri(Iso639LanguageValidator.get().requireValid(value.substring(prefix.length())))); }
        catch (IllegalArgumentException e) { return false; }
    }
    public void requireCollocation(RepositoryConnection c, IRI col, List<IRI> graphs, boolean export) {
        for (IRI g : graphs) { if (c.hasStatement(col,RDF.TYPE,iri(FRAC+"Collocation"),false,g)) { return; } }
        throw new CppException(export?422:404,export?"CPP_EXPORT_INCONSISTENT":"CPP_COLLOCATION_NOT_FOUND","Referenced FrAC collocation does not exist in an allowed application graph");
    }
    public void available(RepositoryConnection c, IRI col, IRI run) {
        try (RepositoryResult<Statement> rows=c.getStatements(col,prov("wasGeneratedBy"),null,false,graph)) {
            while (rows.hasNext()) {
                Value generator=rows.next().getObject();
                if (!generator.equals(run)) { throw new CppException(409,"CPP_COLLOCATION_ALREADY_LINKED","Collocation already belongs to another CPP run"); }
            }
        }
        if (!c.hasStatement(col,prov("wasGeneratedBy"),run,false,graph)
                && (c.hasStatement(col,RDF.TYPE,cpp("CollocationObservation"),false,graph)
                || c.hasStatement(col,plan("correspondsToVariable"),cpp("collocationObservation"),false,graph))) { inconsistent(); }
    }
    public void detach(RepositoryConnection c, IRI run, IRI col) {
        if (!c.hasStatement(run,prov("generated"),col,false,graph)) { return; }
        c.remove(run,prov("generated"),col,graph); c.remove(col,prov("wasGeneratedBy"),run,graph);
        if (!c.hasStatement(col,prov("wasGeneratedBy"),null,false,graph)) {
            c.remove(col,RDF.TYPE,cpp("CollocationObservation"),graph);
            c.remove(col,plan("correspondsToVariable"),cpp("collocationObservation"),graph);
        }
    }
    public List<IRI> find(RepositoryConnection c, CppRunFilter f, boolean paged) {
        TupleQuery q=query(c,"SELECT DISTINCT ?run ?created",f," ORDER BY DESC(?created) ASC(?run)"+(paged?" LIMIT "+f.limit+" OFFSET "+f.offset:""));
        List<IRI> result=new ArrayList<IRI>();
        try (TupleQueryResult rows=q.evaluate()) {
            while(rows.hasNext()) {
                Value v=rows.next().getValue("run");
                if (!(v instanceof IRI)) { inconsistent(); }
                result.add((IRI)v);
                if (!paged && result.size()>CppResourceUris.limit("CPP.export.maxRuns",1000)) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","Too many runs to export"); }
            }
        }
        return result;
    }
    public long count(RepositoryConnection c, CppRunFilter f) {
        try (TupleQueryResult rows=query(c,"SELECT (COUNT(DISTINCT ?run) AS ?total)",f,"").evaluate()) {
            return rows.hasNext()?((Literal)rows.next().getValue("total")).longValue():0;
        }
    }
    private TupleQuery query(RepositoryConnection c, String select, CppRunFilter f, String suffix) {
        StringBuilder body=new StringBuilder(" ?run a <"+CPP+"CollocationExtractionRun>; <"+DCTERMS.CREATED+"> ?created . ");
        Map<String,Value> bindings=new HashMap<String,Value>(); bindings.put("graph",graph);
        if(f.author!=null) { body.append("?run <"+DCTERMS.CREATOR+"> ?author . "); bindings.put("author",vf.createLiteral(f.author)); }
        if(f.collocationIRI!=null) { body.append("?run <"+PROV+"generated> ?col . "); bindings.put("col",CppResourceUris.iri(f.collocationIRI)); }
        if(f.node!=null) { body.append("?run <"+PROV+"used> ?nv . ?nv <"+PLAN+"correspondsToVariable> <"+CPP+"node>; <"+PROV+"value> ?node . "); bindings.put("node",vf.createLiteral(f.node)); }
        if(f.corpusIRI!=null) { body.append("?run <"+PROV+"used> ?cv . ?cv <"+PLAN+"correspondsToVariable> <"+CPP+"corpus>; <"+PROV+"hadMember> ?corpus . "); bindings.put("corpus",CppResourceUris.iri(f.corpusIRI)); }
        if(f.measure!=null) {
            body.append("?run <"+PROV+"used> ?mv . ?mv <"+PLAN+"correspondsToVariable> <"+CPP+"measures>; ?membership ?measure . FILTER(STRSTARTS(STR(?membership), \""+RDF.NAMESPACE+"_\")) ");
            bindings.put("measure",CppMeasureRegistry.filter(f.measure));
        }
        if(f.from!=null) { body.append("FILTER(?created >= ?from) "); bindings.put("from",vf.createLiteral(f.from,XSD.DATETIME)); }
        if(f.to!=null) { body.append("FILTER(?created <= ?to) "); bindings.put("to",vf.createLiteral(f.to,XSD.DATETIME)); }
        TupleQuery q=c.prepareTupleQuery(QueryLanguage.SPARQL,select+" WHERE { GRAPH ?graph {"+body+"} }"+suffix);
        q.setIncludeInferred(false); for(Map.Entry<String,Value> b:bindings.entrySet()) { q.setBinding(b.getKey(),b.getValue()); } return q;
    }
    public void exportCollocation(RepositoryConnection c, IRI col, List<IRI> graphs, Model target) {
        requireCollocation(c,col,graphs,true);
        Deque<Resource> pending=new ArrayDeque<Resource>();
        Set<Resource> visited=new HashSet<Resource>();
        Set<Resource> scoreResults=new HashSet<Resource>(); pending.add(col);
        // scoreFor is the explicit inverse of hasScoreResult; never fabricate the inverse triple.
        for(IRI g:graphs) {
            try(RepositoryResult<Statement> rows=c.getStatements(null,score("scoreFor"),col,false,g)) {
                while(rows.hasNext()) {
                    Resource result=rows.next().getSubject();
                    requireInternalType(c,result,score("ScoreResult"),graphs);
                    scoreResults.add(result); pending.add(result);
                }
            }
        }
        while(!pending.isEmpty()) {
            Resource s=pending.removeFirst(); if(!visited.add(s)) { continue; }
            for(IRI g:graphs) {
                for(Statement st:subject(c,s,g)) {
                    addBounded(target,st);
                    Value o=st.getObject(); IRI p=st.getPredicate();
                    if(scoreResults.contains(s) && p.equals(score("scoreFor")) && !col.equals(o)) { inconsistent(); }
                    boolean resultEdge=s.equals(col) && p.equals(score("hasScoreResult"));
                    boolean countsEdge=scoreResults.contains(s) && p.equals(score("hasCountData"));
                    if(resultEdge || countsEdge) {
                        if(!(o instanceof Resource)) { inconsistent(); }
                        requireInternalType(c,(Resource)o,resultEdge?score("ScoreResult"):score("CooccurrenceCountData"),graphs);
                        if(resultEdge) { scoreResults.add((Resource)o); }
                        pending.add((Resource)o);
                    } else if(o instanceof BNode && !externalReference(p)) { pending.add((BNode)o); }
                }
            }
        }
    }
    private void requireInternalType(RepositoryConnection c, Resource resource, IRI type, List<IRI> graphs) {
        for(IRI g:graphs) { if(c.hasStatement(resource,RDF.TYPE,type,false,g)) { return; } }
        inconsistent();
    }
    private boolean externalReference(IRI p) {
        return p.equals(iri(FRAC+"head")) || p.equals(iri(FRAC+"observedIn")) || p.equals(RDFS.MEMBER)
                || p.stringValue().startsWith(RDF.NAMESPACE+"_") || p.equals(score("scoreFor")) || p.equals(score("usesMeasure"));
    }
    public static Model boundedModel() { return new CppBoundedModel(); }
    public static void addBounded(Model m, Statement st) {
        // Flatten contexts deliberately: Turtle is an RDF extract, not a named-graph backup.
        m.add(st.getSubject(),st.getPredicate(),st.getObject());
        if(m.size()>CppResourceUris.limit("CPP.export.maxTriples",50000)) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","Too many RDF triples"); }
    }
    private static void inconsistent() { throw new CppException(422,"CPP_EXPORT_INCONSISTENT","CPP RDF relationships are inconsistent"); }
}
