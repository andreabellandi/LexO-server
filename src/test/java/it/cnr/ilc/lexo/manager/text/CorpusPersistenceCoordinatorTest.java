package it.cnr.ilc.lexo.manager.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CorpusPersistenceCoordinatorTest {

    @Test
    @DisplayName("RDF import failure removes the newly written Lucene document")
    void compensatesLuceneWhenRdfImportFails() {
        FakeIndex index = new FakeIndex();
        CorpusIndexDocument document = document("new");

        assertThatThrownBy(() -> CorpusPersistenceCoordinator.create(index,
                document, () -> {
                    throw new IllegalStateException("RDF unavailable");
                })).isInstanceOf(IllegalStateException.class)
                .hasMessage("RDF unavailable");

        assertThat(index.documents).doesNotContainKey("new");
    }

    @Test
    @DisplayName("Lucene import failure never invokes the RDF transaction")
    void doesNotWriteRdfWhenLuceneImportFails() {
        FakeIndex index = new FakeIndex();
        index.failIndexAfterWrite = true;
        boolean[] rdfCalled = new boolean[]{false};

        assertThatThrownBy(() -> CorpusPersistenceCoordinator.create(index,
                document("new"), () -> rdfCalled[0] = true))
                .isInstanceOf(IOException.class)
                .hasMessage("index failed");

        assertThat(rdfCalled[0]).isFalse();
        assertThat(index.documents).doesNotContainKey("new");
    }

    @Test
    @DisplayName("RDF delete failure restores the previous Lucene snapshot")
    void restoresLuceneWhenRdfDeleteFails() {
        FakeIndex index = new FakeIndex();
        CorpusIndexDocument previous = document("existing");
        index.documents.put(previous.fileId, previous);
        boolean[] rdfPresent = new boolean[]{true};

        assertThatThrownBy(() -> CorpusPersistenceCoordinator.delete(index,
                previous.fileId, previous,
                new CorpusPersistenceCoordinator.CompensatingOperation() {
                    @Override
                    public void run() {
                        rdfPresent[0] = false;
                        throw new IllegalStateException("RDF delete failed");
                    }

                    @Override
                    public void compensate() {
                        rdfPresent[0] = true;
                    }
                })).isInstanceOf(IllegalStateException.class)
                .hasMessage("RDF delete failed");

        assertThat(rdfPresent[0]).isTrue();
        assertThat(index.documents.get("existing")).isSameAs(previous);
    }

    @Test
    @DisplayName("Lucene delete failure leaves RDF untouched and restores the snapshot")
    void doesNotDeleteRdfWhenLuceneDeleteFails() {
        FakeIndex index = new FakeIndex();
        CorpusIndexDocument previous = document("existing");
        index.documents.put(previous.fileId, previous);
        index.failDeleteAfterRemoval = true;
        boolean[] rdfDeleteCalled = new boolean[]{false};

        assertThatThrownBy(() -> CorpusPersistenceCoordinator.delete(index,
                previous.fileId, previous,
                new CorpusPersistenceCoordinator.CompensatingOperation() {
                    @Override
                    public void run() {
                        rdfDeleteCalled[0] = true;
                    }

                    @Override
                    public void compensate() {
                        // Nothing was written when run() was not reached.
                    }
                })).isInstanceOf(IOException.class)
                .hasMessage("delete failed");

        assertThat(rdfDeleteCalled[0]).isFalse();
        assertThat(index.documents.get("existing")).isSameAs(previous);
    }

    private static CorpusIndexDocument document(String fileId) {
        CorpusIndexDocument result = new CorpusIndexDocument();
        result.fileId = fileId;
        return result;
    }

    private static final class FakeIndex implements CorpusIndexService {
        final Map<String, CorpusIndexDocument> documents =
                new LinkedHashMap<String, CorpusIndexDocument>();
        boolean failIndexAfterWrite;
        boolean failDeleteAfterRemoval;

        @Override
        public void index(CorpusIndexDocument document) throws IOException {
            documents.put(document.fileId, document);
            if (failIndexAfterWrite) {
                failIndexAfterWrite = false;
                throw new IOException("index failed");
            }
        }

        @Override
        public boolean delete(String fileId) throws IOException {
            boolean existed = documents.remove(fileId) != null;
            if (failDeleteAfterRemoval) {
                failDeleteAfterRemoval = false;
                throw new IOException("delete failed");
            }
            return existed;
        }

        @Override
        public CorpusIndexDocument get(String fileId) {
            return documents.get(fileId);
        }

        @Override
        public List<String> fileIds() {
            return new ArrayList<String>(documents.keySet());
        }

        @Override
        public long documentCount() {
            return documents.size();
        }

        @Override
        public boolean available() {
            return true;
        }
    }
}
