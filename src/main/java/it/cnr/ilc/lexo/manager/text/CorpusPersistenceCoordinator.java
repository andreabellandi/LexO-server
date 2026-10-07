package it.cnr.ilc.lexo.manager.text;

import java.io.IOException;

/**
 * Coordinates the two durable corpus stores with compensating actions.
 *
 * <p>Lucene is changed first. RDF operations retain their own local
 * transaction, while this coordinator restores the previous Lucene snapshot
 * whenever the following RDF step fails.</p>
 */
final class CorpusPersistenceCoordinator {

    interface Operation {
        void run() throws IOException;
    }

    interface CompensatingOperation extends Operation {
        void compensate() throws IOException;
    }

    private CorpusPersistenceCoordinator() {
    }

    static void create(CorpusIndexService index, CorpusIndexDocument document,
                       Operation rdfWrite) throws IOException {
        CorpusIndexDocument previous = index.get(document.fileId);
        boolean indexAttempted = false;
        try {
            indexAttempted = true;
            index.index(document);
            rdfWrite.run();
        } catch (Throwable failure) {
            if (indexAttempted) {
                restoreIndex(index, document.fileId, previous, failure);
            }
            rethrow(failure);
        }
    }

    static boolean delete(CorpusIndexService index, String fileId,
                          CorpusIndexDocument previous,
                          CompensatingOperation rdfDelete) throws IOException {
        boolean indexAttempted = false;
        try {
            indexAttempted = true;
            boolean deleted = index.delete(fileId);
            rdfDelete.run();
            return deleted;
        } catch (Throwable failure) {
            try {
                rdfDelete.compensate();
            } catch (Throwable compensation) {
                failure.addSuppressed(compensation);
            }
            if (indexAttempted) {
                restoreIndex(index, fileId, previous, failure);
            }
            rethrow(failure);
            return false;
        }
    }

    private static void restoreIndex(CorpusIndexService index, String fileId,
                                     CorpusIndexDocument previous,
                                     Throwable failure) {
        try {
            if (previous == null) {
                index.delete(fileId);
            } else {
                index.index(previous);
            }
        } catch (Throwable compensation) {
            failure.addSuppressed(compensation);
        }
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException) {
            throw (IOException) failure;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IOException(failure);
    }
}
