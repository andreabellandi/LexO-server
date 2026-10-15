package it.cnr.ilc.lexo.manager.cpp;

import static it.cnr.ilc.lexo.manager.cpp.CppVocabulary.*;
import static org.assertj.core.api.Assertions.*;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.CppRunManager;
import it.cnr.ilc.lexo.service.data.cpp.input.*;
import it.cnr.ilc.lexo.service.data.cpp.output.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.*;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.*;
import org.eclipse.rdf4j.repository.*;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.rio.*;
import org.junit.jupiter.api.*;

class CppRunManagerTest {
    private SailRepository memory;
    private CppRunRepository repository;
    private CppRunManager manager;
    private final ValueFactory vf=SimpleValueFactory.getInstance();
    private final IRI lexical=iri("https://lexo.ilc.cnr.it/graphs/lexical/lexica");
    private final String c1="urn:collocation:one",c2="urn:collocation:two",c3="urn:collocation:three";
    private Model original;
    @BeforeEach void setup() {
        memory=new SailRepository(new MemoryStore()); memory.init();
        repository=new CppRunRepository(memory); manager=new CppRunManager(repository);
        try(RepositoryConnection c=memory.getConnection()) {
            for(String col:Arrays.asList(c1,c2,c3)) {
                c.add(iri(col),RDF.TYPE,iri(FRAC+"Collocation"),lexical);
                c.add(iri(col),score("logDice"),vf.createLiteral("7.50",XSD.DECIMAL),lexical);
                c.add(iri(col),RDFS.LABEL,vf.createLiteral("Crìme 😀","it"),lexical);
                c.add(iri(col),iri(FRAC+"head"),iri("urn:lexical:entry"),lexical);
            }
            c.add(iri("urn:lexical:entry"),RDFS.LABEL,vf.createLiteral("Do not expand"),lexical);
            original=snapshot(c,lexical);
        }
    }
    @AfterEach void close() { memory.shutDown(); }
    private CppRunCreateRequest request(String... cols) {
        String raw="{\"search\":{\"node\":\"crime\"},\"cppOptions\":{\"ordered\":false},"
                +"\"corpusSnapshot\":{\"memberIRIs\":[\"urn:corpus:news\"]},"
                +"\"index\":{\"id\":\"lexo-lucene\"},\"software\":{\"name\":\"LexO\",\"version\":\"1\"},\"collocations\":[]}";
        CppRunCreateRequest r=CppJsonCodec.read(raw,CppRunCreateRequest.class);
        r.collocations=Arrays.asList(cols); return r;
    }
    private Model snapshot(RepositoryConnection c, IRI g) {
        Model m=new LinkedHashModel();
        try(RepositoryResult<Statement> rows=c.getStatements(null,null,null,false,g)) {
            while(rows.hasNext()) { Statement st=rows.next(); m.add(st.getSubject(),st.getPredicate(),st.getObject()); }
        }
        return m;
    }
    private void unchanged() {
        try(RepositoryConnection c=memory.getConnection()) {
            assertThat(snapshot(c,lexical)).isEqualTo(original);
            assertThat(c.size((Resource)null)).isZero();
        }
    }
    private Value binding(Model m, IRI run, String variable) {
        for(Value b:m.filter(run,prov("used"),null).objects()) {
            if(m.contains((Resource)b,plan("correspondsToVariable"),cpp(variable))) { return b; }
        }
        throw new AssertionError("Missing binding: "+variable);
    }
    private Model exported(List<String> runs,boolean full) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(CppTurtleExport file=manager.exportTurtle(runs,full)) { file.writeTo(out); }
        return Rio.parse(new ByteArrayInputStream(out.toByteArray()),"urn:export:",RDFFormat.TURTLE);
    }
    private void error(String code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CppException.class,e->assertThat(e.code).isEqualTo(code));
    }
    @Test void createsCompleteTypedBindingsWithIndependentFrequencyAndOrdering() {
        CppRunCreateRequest r=request(c1,c2);
        r.search.boundary="SENTENCE"; r.search.direction="LEFT"; r.cppOptions.ordered=true;
        r.search.minCooccurrenceFrequency=3; r.search.minFrequency=2L; r.search.minScore=7.5;
        r.search.stoplist=Arrays.asList("the","a"); r.search.excludeTerms=Arrays.asList("alleged");
        CppRunDetails d=manager.create(r,"  ",null).run;
        assertThat(d.author).isEqualTo("anonymous"); assertThat(d.collocationCount).isEqualTo(2);
        assertThat(d.execution).isNull(); assertThat(d.search.leftWindow).isEqualTo(5);
        try(RepositoryConnection c=memory.getConnection()) {
            IRI run=iri(d.iri); Model m=repository.runModel(c,run);
            assertThat(m.filter((Resource)binding(m,run,"node"),prov("value"),null).objects())
                    .containsExactly(vf.createLiteral("crime",XSD.STRING));
            assertThat(m.contains((Resource)binding(m,run,"sentenceBoundary"),prov("value"),vf.createLiteral(true))).isTrue();
            assertThat(m.contains((Resource)binding(m,run,"ordered"),prov("value"),vf.createLiteral(true))).isTrue();
            assertThat(m.contains((Resource)binding(m,run,"minimumFrequency"),prov("value"),vf.createLiteral("3",XSD.NON_NEGATIVE_INTEGER))).isTrue();
            assertThat(m.contains((Resource)binding(m,run,"minFrequency"),prov("value"),vf.createLiteral("2",XSD.NON_NEGATIVE_INTEGER))).isTrue();
            assertThat(m.contains((Resource)binding(m,run,"minScore"),prov("value"),vf.createLiteral("7.5",XSD.DECIMAL))).isTrue();
            assertThat(m.filter((Resource)binding(m,run,"measures"),null,null).objects())
                    .containsAll(Arrays.asList(CppMeasureRegistry.individual("PMI"),CppMeasureRegistry.individual("RAW_FREQUENCY"),CppMeasureRegistry.individual("Z_SCORE")));
            assertThat(d.search.metrics).hasSize(18);
            assertThat(m.contains(null,cpp("refersToNode"),null)).isFalse();
            assertThat(m.contains(null,plan("correspondsToVariable"),cpp("posPattern"))).isFalse();
            assertThat(m.contains((Resource)binding(m,run,"stopList"),RDF.TYPE,RDF.BAG)).isTrue();
            assertThat(m.contains((Resource)binding(m,run,"excludeTerms"),iri(RDF.NAMESPACE+"_1"),vf.createLiteral("alleged"))).isTrue();
            assertThat(m.contains(iri(c1),prov("wasGeneratedBy"),run)).isTrue();
            assertThat(m.contains(run,prov("generated"),iri(c2))).isTrue();
            assertThat(c.hasStatement(null,null,null,false,repository.graph)).isTrue();
        }
        unchanged();
    }
    @Test void handlesFrequencyAliasOptionalFiltersAndAllMetrics() {
        CppRunCreateRequest r=request(c1); r.search.metrics=Arrays.asList("ALL"); r.search.sortBy="FREQUENCY";
        CppRunDetails d=manager.create(r,"author",null).run;
        try(RepositoryConnection c=memory.getConnection()) {
            Model m=repository.runModel(c,iri(d.iri));
            assertThat(m.contains((Resource)binding(m,iri(d.iri),"sortBy"),score("selectsMeasure"),score("CooccurrenceFrequency"))).isTrue();
            assertThat(m.contains((Resource)binding(m,iri(d.iri),"sentenceBoundary"),prov("value"),vf.createLiteral(false))).isTrue();
            assertThat(m.contains(null,plan("correspondsToVariable"),cpp("minFrequency"))).isFalse();
            assertThat(m.contains(null,plan("correspondsToVariable"),cpp("minScore"))).isFalse();
            assertThat(m.contains(null,plan("correspondsToVariable"),cpp("stopList"))).isFalse();
        }
        unchanged();
    }
    @Test void invalidReferencesAndAlreadyLinkedCollocationsWriteNothing() {
        error("CPP_COLLOCATION_NOT_FOUND",()->manager.create(request(c1,"urn:missing"),"a",null));
        try(RepositoryConnection c=memory.getConnection()) { assertThat(c.size(repository.graph)).isZero(); }
        CppRunDetails first=manager.create(request(c1),"a",null).run;
        Model before;
        try(RepositoryConnection c=memory.getConnection()) { before=snapshot(c,repository.graph); }
        error("CPP_COLLOCATION_ALREADY_LINKED",()->manager.create(request(c1,c2),"b",null));
        try(RepositoryConnection c=memory.getConnection()) { assertThat(snapshot(c,repository.graph)).isEqualTo(before); }
        assertThat(manager.get(first.id).collocations).containsExactly(c1); unchanged();
    }
    @Test void patchIsAtomicIdempotentAndPreservesMethodology() {
        CppRunDetails d=manager.create(request(c1,c2),"a",null).run;
        CppRunPatchRequest p=new CppRunPatchRequest(); p.addCollocations=Arrays.asList(c3); p.removeCollocations=Arrays.asList(c2);
        CppRunDetails updated=manager.patchAssociations(d.id,p,"editor");
        assertThat(updated.collocations).containsExactly(c1,c3);
        assertThat(updated.modifiedAt).isNotNull(); assertThat(updated.author).isEqualTo("a");
        assertThat(CppJsonCodec.write(updated.search)).isEqualTo(CppJsonCodec.write(d.search));
        assertThat(manager.patchAssociations(d.id,p,"editor").collocations).containsExactly(c1,c3);
        p.addCollocations=Collections.emptyList(); p.removeCollocations=Arrays.asList(c1,c3);
        error("CPP_LAST_COLLOCATION",()->manager.patchAssociations(d.id,p,"editor"));
        p.addCollocations=Arrays.asList(c1); p.removeCollocations=Arrays.asList(c1);
        error("CPP_INVALID_PARAMETER",()->manager.patchAssociations(d.id,p,"editor"));
        assertThat(manager.get(d.id).collocations).containsExactly(c1,c3); unchanged();
    }
    @Test void deleteRemovesOnlyOwnedProvenanceAndPreservesOtherRuns() {
        CppRunDetails first=manager.create(request(c1,c2),"a",null).run;
        CppRunDetails other=manager.create(request(c3),"b",null).run;
        Model before;
        try(RepositoryConnection c=memory.getConnection()) { before=repository.runModel(c,iri(other.iri)); }
        manager.delete(first.id); error("CPP_RUN_NOT_FOUND",()->manager.get(first.id));
        try(RepositoryConnection c=memory.getConnection()) {
            assertThat(repository.runModel(c,iri(other.iri))).isEqualTo(before);
            assertThat(c.hasStatement(iri(c1),null,null,false,repository.graph)).isFalse();
            assertThat(c.hasStatement(null,prov("wasGeneratedBy"),iri(first.iri),false,repository.graph)).isFalse();
        }
        manager.delete(other.id);
        try(RepositoryConnection c=memory.getConnection()) { assertThat(c.size(repository.graph)).isZero(); }
        unchanged();
    }
    @Test void listFiltersPaginatesAndReturnsEffectiveDetails() {
        CppRunCreateRequest r=request(c1); r.search.metrics=Arrays.asList("PMI");
        CppRunDetails first=manager.create(r,"a",null).run;
        CppRunCreateRequest r2=request(c2); r2.search.node="report"; manager.create(r2,"b",null);
        CppRunFilter f=new CppRunFilter(); f.limit=1;
        assertThat(manager.list(f).total).isEqualTo(2); assertThat(manager.list(f).items).hasSize(1);
        f.offset=1; assertThat(manager.list(f).items).hasSize(1);
        f.offset=0; f.node="crime"; f.author="a"; f.collocationIRI=c1; f.corpusIRI="urn:corpus:news"; f.measure="PMI";
        f.from="2000-01-01T00:00:00Z"; f.to="2099-01-01T00:00:00Z";
        assertThat(manager.list(f).items).extracting(x->x.id).containsExactly(first.id);
        f.measure=score("PMI").stringValue(); assertThat(manager.list(f).total).isEqualTo(1);
        f.node="x\"} UNION { ?s ?p ?o } #"; assertThat(manager.list(f).total).isZero();
        f.offset=-1; error("CPP_INVALID_PARAMETER",()->manager.list(f)); unchanged();
    }
    @Test void idempotencyIsCanonicalAndScopedByAuthor() {
        CppRunCreateRequest r=request(c1,c2);
        CppRunManager.Creation first=manager.create(r,"a","retry-1");
        CppRunManager.Creation again=manager.create(request(c2,c1),"a","retry-1");
        assertThat(first.replayed).isFalse(); assertThat(again.replayed).isTrue(); assertThat(again.run.id).isEqualTo(first.run.id);
        CppRunCreateRequest different=request(c1); error("CPP_IDEMPOTENCY_CONFLICT",()->manager.create(different,"a","retry-1"));
        CppRunManager.Creation other=manager.create(request(c3),"b","retry-1");
        assertThat(other.run.id).isNotEqualTo(first.run.id); unchanged();
    }
    @Test void exportPreservesRdfValuesWithoutExpandingExternalResources() throws Exception {
        CppRunDetails d=manager.create(request(c1),"a",null).run;
        Model minimal=exported(Arrays.asList(d.iri),false);
        assertThat(minimal.contains(iri(c1),RDF.TYPE,cpp("CollocationObservation"))).isTrue();
        assertThat(minimal.contains(iri(c1),RDF.TYPE,iri(FRAC+"Collocation"))).isFalse();
        assertThat(minimal.contains(iri(c1),score("logDice"),null)).isFalse();
        Model full=exported(Arrays.asList(d.iri),true);
        assertThat(full.contains(iri(c1),score("logDice"),vf.createLiteral("7.50",XSD.DECIMAL))).isTrue();
        assertThat(full.contains(iri(c1),RDFS.LABEL,vf.createLiteral("Crìme 😀","it"))).isTrue();
        assertThat(full.contains(iri("urn:lexical:entry"),null,null)).isFalse();
        assertThat(full.contexts()).containsOnly((Resource)null);
        Model expected=new LinkedHashModel(minimal);
        try(RepositoryConnection c=memory.getConnection()) { expected.addAll(repository.subject(c,iri(c1),lexical)); }
        assertThat(Models.isomorphic(full,expected)).isTrue(); unchanged();
    }
    @Test void exportIncludesOnlyWhitelistedInternalScoreAndBlankNodeClosure() throws Exception {
        CppRunDetails d=manager.create(request(c1),"a",null).run;
        BNode meta=vf.createBNode(); IRI result=iri("urn:score:one"),counts=iri("urn:counts:one");
        try(RepositoryConnection c=memory.getConnection()) {
            c.add(iri(c1),score("hasScoreResult"),result,lexical);
            c.add(result,RDF.TYPE,score("ScoreResult"),lexical);
            c.add(result,score("hasCountData"),counts,lexical);
            c.add(counts,RDF.TYPE,score("CooccurrenceCountData"),lexical);
            c.add(counts,score("fxy"),vf.createLiteral(12),lexical);
            c.add(result,score("scoreFor"),iri(c1),lexical);
            c.add(iri(c1),DCTERMS.DESCRIPTION,meta,lexical); c.add(meta,RDF.VALUE,vf.createLiteral("metadata"),lexical);
        }
        IRI inverse=iri("urn:score:inverse-only"),language=iri("https://lexo.ilc.cnr.it/graphs/lexical/lexica/en");
        try(RepositoryConnection c=memory.getConnection()) {
            c.add(inverse,RDF.TYPE,score("ScoreResult"),language);
            c.add(inverse,score("scoreFor"),iri(c1),language);
            c.add(iri(c1),iri(FRAC+"pmi"),vf.createLiteral(4.0),language);
        }
        Model m=exported(null,true);
        assertThat(m.contains(inverse,score("scoreFor"),iri(c1))).isTrue();
        assertThat(m.contains(iri(c1),score("hasScoreResult"),inverse)).isFalse();
        assertThat(m.contains(iri(c1),iri(FRAC+"pmi"),vf.createLiteral(4.0))).isTrue();
        assertThat(m.contains(counts,score("fxy"),vf.createLiteral(12))).isTrue();
        assertThat(m.contains(result,score("scoreFor"),iri(c1))).isTrue();
        assertThat(m.contains(null,RDF.VALUE,vf.createLiteral("metadata"))).isTrue();
        assertThat(m.contains(iri(c2),null,null)).isFalse();
    }
    @Test void exportSelectionIsCompleteDeduplicatedAndBounded() throws Exception {
        assertThat(exported(null,false)).isEmpty();
        CppRunDetails one=manager.create(request(c1),"a",null).run;
        CppRunDetails two=manager.create(request(c2),"b",null).run;
        Model oneModel=exported(Arrays.asList(one.iri,one.iri),false);
        Model twoModel=exported(Arrays.asList(two.iri),false);
        Model union=new LinkedHashModel(oneModel); union.addAll(twoModel);
        assertThat(Models.isomorphic(exported(null,false),union)).isTrue();
        error("CPP_RUN_NOT_FOUND",()->manager.exportTurtle(Arrays.asList(one.iri,new CppResourceUris().run(UUID.randomUUID().toString()).stringValue()),false));
        error("CPP_INVALID_IRI",()->manager.exportTurtle(Arrays.asList("not an IRI"),false));
        error("CPP_INVALID_IRI",()->manager.exportTurtle(Arrays.asList("https://example.org/other"),false));
        String previous=LexOProperties.getProperty("CPP.export.maxTriples");
        try { LexOProperties.setProperty("CPP.export.maxTriples","5"); error("CPP_LIMIT_EXCEEDED",()->manager.exportTurtle(null,false)); }
        finally { LexOProperties.setProperty("CPP.export.maxTriples",previous); }
        unchanged();
    }
    @Test void missingFracFailsFullExportBeforeDownloadButNotProvenanceOnly() throws Exception {
        CppRunDetails d=manager.create(request(c1),"a",null).run;
        try(RepositoryConnection c=memory.getConnection()) { c.remove(iri(c1),null,null,lexical); }
        error("CPP_EXPORT_INCONSISTENT",()->manager.exportTurtle(Arrays.asList(d.iri),true));
        assertThat(exported(Arrays.asList(d.iri),false)).isNotEmpty();
    }
    @Test void rollsBackCreatePatchAndDeleteOnFailureBeforeCommit() {
        CppRunManager failing=new CppRunManager(new CppRunRepository(memory) {
            @Override protected void beforeCommit(RepositoryConnection c) { throw new IllegalStateException("simulated storage failure"); }
        });
        assertThatThrownBy(()->failing.create(request(c1),"a",null)).isInstanceOf(IllegalStateException.class);
        try(RepositoryConnection c=memory.getConnection()) { assertThat(c.size(repository.graph)).isZero(); }
        CppRunDetails d=manager.create(request(c1),"a",null).run; Model before;
        try(RepositoryConnection c=memory.getConnection()) { before=snapshot(c,repository.graph); }
        CppRunPatchRequest p=new CppRunPatchRequest();p.addCollocations=Arrays.asList(c2);p.removeCollocations=Arrays.asList(c1);
        assertThatThrownBy(()->failing.patchAssociations(d.id,p,"e")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->failing.delete(d.id)).isInstanceOf(IllegalStateException.class);
        try(RepositoryConnection c=memory.getConnection()) { assertThat(snapshot(c,repository.graph)).isEqualTo(before); }
        unchanged();
    }
    @Test void concurrentManagersCannotGenerateTheSameCollocation() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<String>> tasks=new ArrayList<Future<String>>();
            for(int i=0;i<2;i++) {
                tasks.add(pool.submit(()->{ start.await();
                    try { new CppRunManager(new CppRunRepository(memory)).create(request(c1),"a",null); return "created"; }
                    catch(CppException e) { return e.code; }
                }));
            }
            start.countDown(); List<String> outcomes=Arrays.asList(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("created","CPP_COLLOCATION_ALREADY_LINKED");
            assertThat(manager.list(new CppRunFilter()).total).isEqualTo(1); unchanged();
        } finally { pool.shutdownNow(); }
    }
    @Test void concurrentPatchesFromDifferentRunsCannotClaimTheSameFrAC() throws Exception {
        String one=manager.create(request(c1),"a",null).run.id;
        String two=manager.create(request(c2),"a",null).run.id;
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<String>> tasks=new ArrayList<Future<String>>();
            for(String id:Arrays.asList(one,two)) {
                tasks.add(pool.submit(()->{ start.await(); CppRunPatchRequest p=new CppRunPatchRequest(); p.addCollocations=Arrays.asList(c3);
                    try { new CppRunManager(new CppRunRepository(memory)).patchAssociations(id,p,"a");return "patched"; }
                    catch(CppException e) { return e.code; }
                }));
            }
            start.countDown();
            assertThat(Arrays.asList(tasks.get(0).get(10,TimeUnit.SECONDS),tasks.get(1).get(10,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("patched","CPP_COLLOCATION_ALREADY_LINKED");
            try(RepositoryConnection c=memory.getConnection()) {
                assertThat(repository.subject(c,iri(c3),repository.graph).filter(null,prov("wasGeneratedBy"),null)).hasSize(1);
            }
        } finally { pool.shutdownNow(); }
        unchanged();
    }
    @Test void validatesRequiredValuesSemanticTimesAndLuceneLimitsBeforeWriting() {
        CppRunCreateRequest missing=request(c1); missing.cppOptions.ordered=null;
        error("CPP_INVALID_PARAMETER",()->manager.create(missing,"a",null));
        CppRunCreateRequest empty=request(); error("CPP_INVALID_PARAMETER",()->manager.create(empty,"a",null));
        CppRunCreateRequest duplicates=request(c1,c1); error("CPP_INVALID_PARAMETER",()->manager.create(duplicates,"a",null));
        CppRunCreateRequest snapshot=request(c1); snapshot.corpusSnapshot.memberIRIs=Collections.emptyList();
        error("CPP_INVALID_PARAMETER",()->manager.create(snapshot,"a",null));
        CppRunCreateRequest punctuation=request(c1); punctuation.search.node="!!!";
        error("CPP_INVALID_PARAMETER",()->manager.create(punctuation,"a",null));
        CppRunCreateRequest window=request(c1); window.search.leftWindow=101;
        error("CPP_INVALID_PARAMETER",()->manager.create(window,"a",null));
        CppRunCreateRequest invalidTime=request(c1); invalidTime.execution=new CppExecutionInput();
        invalidTime.execution.startedAtTime="2026-01-02T00:00:00Z"; invalidTime.execution.endedAtTime="2026-01-01T00:00:00Z";
        error("CPP_INVALID_SEMANTICS",()->manager.create(invalidTime,"a",null));
        error("CPP_INVALID_PARAMETER",()->manager.create(request(c1),"a","unsafe key"));
        CppRunCreateRequest score=request(c1); score.search.minScore=Double.NaN;
        error("CPP_INVALID_PARAMETER",()->manager.create(score,"a",null));
        try(RepositoryConnection c=memory.getConnection()) { assertThat(c.size(repository.graph)).isZero(); }
        unchanged();
    }
    @Test void recordsSubsetAndExecutionWithoutInventingSortScores() {
        CppRunCreateRequest r=request(c1);r.search.metrics=Arrays.asList("PMI");r.search.sortBy="LOG_DICE";
        r.search.contextIRIs=Arrays.asList("urn:context:two","urn:context:one");
        r.corpusSnapshot.memberIRIs=Arrays.asList("urn:context:two","urn:context:one");
        r.index.lastCommit="2026-01-01T00:00:00Z";r.execution=new CppExecutionInput();
        r.execution.startedAtTime="2026-01-01T02:00:00+02:00";r.execution.endedAtTime="2026-01-01T00:00:01Z";
        CppRunDetails d=manager.get(manager.create(r,"a",null).run.id);
        assertThat(d.search.metrics).containsExactly("PMI");assertThat(d.search.sortBy).isEqualTo("LOG_DICE");
        assertThat(d.search.contextIRIs).containsExactly("urn:context:one","urn:context:two");
        assertThat(d.execution.startedAtTime).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(d.index.lastCommit).isEqualTo("2026-01-01T00:00:00Z");
        try(RepositoryConnection c=memory.getConnection()) {
            Model m=repository.runModel(c,iri(d.iri));
            assertThat(m.contains((Resource)binding(m,iri(d.iri),"corpus"),RDF.TYPE,cpp("CorpusSelectionSnapshot"))).isTrue();
            assertThat(m.contains(iri(d.iri),prov("startedAtTime"),vf.createLiteral("2026-01-01T00:00:00Z",XSD.DATETIME))).isTrue();
        }
        unchanged();
    }
    @Test void acceptsIsoLanguageGraphAndPreservesVerifiedGlobalSoftware() throws Exception {
        IRI language=iri("https://lexo.ilc.cnr.it/graphs/lexical/lexica/en");IRI software=iri("urn:global:software");
        try(RepositoryConnection c=memory.getConnection()) {
            c.add(iri("urn:collocation:iso"),RDF.TYPE,iri(FRAC+"Collocation"),language);
            c.add(software,RDF.TYPE,cpp("ExtractorSoftware"),language);
            c.add(software,RDFS.LABEL,vf.createLiteral("LexO"),language);
        }
        CppRunCreateRequest r=request("urn:collocation:iso");r.software.iri=software.stringValue();r.software.version=null;
        CppRunDetails d=manager.create(r,"a",null).run;
        assertThat(d.software.iri).isEqualTo(software.stringValue());
        assertThat(exported(Arrays.asList(d.iri),false).contains(software,null,null)).isFalse();
        manager.delete(d.id);
        try(RepositoryConnection c=memory.getConnection()) {
            assertThat(c.size(language)).isEqualTo(3);assertThat(c.size(repository.graph)).isZero();
        }
        unchanged();
    }
    @Test void exportTemporaryFilesAreCleanedAfterSuccessFailureAndDisconnect() throws Exception {
        CppRunDetails d=manager.create(request(c1),"a",null).run;
        Set<String> before=temporaryExports();
        try(CppTurtleExport file=manager.exportTurtle(Arrays.asList(d.iri),false)) {
            assertThat(temporaryExports().size()).isEqualTo(before.size()+1);
            assertThatThrownBy(()->file.writeTo(new OutputStream() {
                @Override public void write(int b) throws IOException { throw new IOException("client disconnected"); }
            })).isInstanceOf(IOException.class);
        }
        assertThat(temporaryExports()).isEqualTo(before);
        String previous=LexOProperties.getProperty("CPP.export.maxBytes");
        try {
            LexOProperties.setProperty("CPP.export.maxBytes","8");
            Model m=new LinkedHashModel();m.add(iri("urn:a"),RDF.TYPE,iri("urn:b"));
            error("CPP_LIMIT_EXCEEDED",()->CppTurtleExport.prepare(m));
        } finally { LexOProperties.setProperty("CPP.export.maxBytes",previous); }
        assertThat(temporaryExports()).isEqualTo(before);unchanged();
    }
    private Set<String> temporaryExports() throws IOException {
        Set<String> result=new HashSet<String>();
        try(java.util.stream.Stream<java.nio.file.Path> paths=java.nio.file.Files.list(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))) {
            paths.filter(p->p.getFileName().toString().startsWith("lexo-cpp-")).forEach(p->result.add(p.toString()));
        }
        return result;
    }
    @Test void rejectsCollocationsInDefaultSchemaOrForeignGraphs() {
        for(IRI graph:Arrays.asList(iri("urn:foreign"),iri("https://lexo.ilc.cnr.it/graphs/lexical/schema"),iri("https://lexo.ilc.cnr.it/graphs/lexical/lexica/not-a-language"))) {
            try(RepositoryConnection c=memory.getConnection()) { c.add(iri("urn:untrusted"),RDF.TYPE,iri(FRAC+"Collocation"),graph); }
            error("CPP_COLLOCATION_NOT_FOUND",()->manager.create(request("urn:untrusted"),"a",null));
        }
        try(RepositoryConnection c=memory.getConnection()) {
            c.add(iri("urn:default"),RDF.TYPE,iri(FRAC+"Collocation"));
            assertThat(c.size(repository.graph)).isZero();
        }
        error("CPP_COLLOCATION_NOT_FOUND",()->manager.create(request("urn:default"),"a",null));
    }
}
