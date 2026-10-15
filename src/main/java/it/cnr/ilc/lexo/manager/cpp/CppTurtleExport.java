package it.cnr.ilc.lexo.manager.cpp;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.rio.*;

/** A fully validated private temporary file, deleted after response or cancellation. */
public final class CppTurtleExport implements AutoCloseable {
    private final Path path;
    private CppTurtleExport(Path path) { this.path=path; }
    public static CppTurtleExport prepare(Model model) {
        Path file=null;
        try {
            file=Files.createTempFile("lexo-cpp-", ".ttl");
            final long max=CppResourceUris.limit("CPP.export.maxBytes",16777216);
            try(OutputStream raw=Files.newOutputStream(file);
                    OutputStream bounded=new FilterOutputStream(raw) {
                        private long count;
                        @Override public void write(int b) throws IOException { check(1); out.write(b); }
                        @Override public void write(byte[] b,int off,int len) throws IOException { check(len); out.write(b,off,len); }
                        private void check(int size) {
                            count+=size; if(count>max) { throw new CppException(413,"CPP_LIMIT_EXCEEDED","Turtle exceeds configured byte limit"); }
                        }
                    }) {
                RDFWriter writer=Rio.createWriter(RDFFormat.TURTLE,bounded); writer.getWriterConfig().set(org.eclipse.rdf4j.rio.helpers.TurtleWriterSettings.ABBREVIATE_NUMBERS,false);
                writer.startRDF();
                writer.handleNamespace("cpp",CppVocabulary.CPP); writer.handleNamespace("collscore",CppVocabulary.SCORE);
                writer.handleNamespace("prov",CppVocabulary.PROV); writer.handleNamespace("p-plan",CppVocabulary.PLAN);
                writer.handleNamespace("frac",CppVocabulary.FRAC);
                List<Statement> statements=new ArrayList<Statement>(model);
                statements.sort(Comparator.comparing((Statement st)->st.getSubject().toString())
                        .thenComparing(st->st.getPredicate().toString()).thenComparing(st->st.getObject().toString()));
                for(Statement st:statements) { writer.handleStatement(st); }
                writer.endRDF();
            }
            return new CppTurtleExport(file);
        } catch(IOException | RuntimeException e) {
            if(file!=null) { try { Files.deleteIfExists(file); } catch(IOException cleanup) { e.addSuppressed(cleanup); } }
            if(e instanceof CppException) { throw (CppException)e; }
            throw new CppException(500,"CPP_EXPORT_ERROR","Cannot prepare CPP Turtle export");
        }
    }
    public void writeTo(OutputStream out) throws IOException {
        try { Files.copy(path,out); } finally { close(); }
    }
    @Override public void close() throws IOException { Files.deleteIfExists(path); }
}
