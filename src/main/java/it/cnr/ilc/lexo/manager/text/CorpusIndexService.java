package it.cnr.ilc.lexo.manager.text;

import java.io.IOException;
import java.util.List;

/** Persistence boundary for the standalone text index. */
public interface CorpusIndexService {
    void index(CorpusIndexDocument document) throws IOException;
    boolean delete(String fileId) throws IOException;
    CorpusIndexDocument get(String fileId) throws IOException;
    List<String> fileIds() throws IOException;
    long documentCount() throws IOException;
    boolean available();
}
