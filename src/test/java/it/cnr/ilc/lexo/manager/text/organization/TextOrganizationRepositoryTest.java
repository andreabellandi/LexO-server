package it.cnr.ilc.lexo.manager.text.organization;

import static org.assertj.core.api.Assertions.assertThat;

import it.cnr.ilc.lexo.service.data.text.output.FolderRecord;
import java.util.Arrays;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;

class TextOrganizationRepositoryTest {

    @Test
    void keepsHierarchyAndMembershipOnlyInTheOrganizationDataGraph() {
        SailRepository store = new SailRepository(new MemoryStore());
        store.init();
        TextOrganizationVocabulary vocabulary = new TextOrganizationVocabulary();
        TextOrganizationRepository repository =
                new TextOrganizationRepository(store, vocabulary);
        FolderRecord root = folder("root", "Root", null);
        FolderRecord child = folder("child", "Child", "root");

        repository.createFolder(root);
        repository.createFolder(child);
        TextOrganizationRepository.MembershipChange first = repository.moveText(
                "https://lexo.test/text/one#context", "root", timestamp(1));
        TextOrganizationRepository.MembershipChange moved = repository.moveText(
                "https://lexo.test/text/one#context", "child", timestamp(2));

        assertThat(first.previousFolderId).isNull();
        assertThat(first.changed).isTrue();
        assertThat(moved.previousFolderId).isEqualTo("root");
        assertThat(repository.membershipsByContext())
                .containsEntry("https://lexo.test/text/one#context", "child")
                .hasSize(1);

        IRI context = SimpleValueFactory.getInstance().createIRI(
                "https://lexo.test/text/one#context");
        try (RepositoryConnection connection = store.getConnection()) {
            assertThat(connection.hasStatement(vocabulary.folderIri("child"),
                    vocabulary.containsText, context, false,
                    vocabulary.dataGraph)).isTrue();
            assertThat(connection.hasStatement(vocabulary.folderIri("root"),
                    vocabulary.containsText, context, false,
                    vocabulary.dataGraph)).isFalse();
            assertThat(connection.hasStatement(null, null, null, false,
                    (org.eclipse.rdf4j.model.Resource) null)).isFalse();
            assertThat(connection.hasStatement(null, null, null, false,
                    vocabulary.schemaGraph)).isFalse();
            assertThat(connection.hasStatement(null, vocabulary.childFolder,
                    null, false, vocabulary.dataGraph)).isFalse();
            assertThat(connection.hasStatement(null, vocabulary.inFolder,
                    null, false, vocabulary.dataGraph)).isFalse();
        }

        assertThat(repository.deleteEmptyFolder("root")).isFalse();
        assertThat(repository.removeTextFromFolder("child",
                context.stringValue(), timestamp(3))).isTrue();
        repository.moveFolder("child", null, timestamp(4));
        assertThat(repository.deleteEmptyFolder("root")).isTrue();
        assertThat(repository.getFolder("child").parentFolderId).isNull();
        store.shutDown();
    }

    @Test
    void bulkMoveAndDeletionSnapshotAreTransactionalAndRestorable() {
        SailRepository store = new SailRepository(new MemoryStore());
        store.init();
        TextOrganizationRepository repository = new TextOrganizationRepository(
                store, new TextOrganizationVocabulary());
        repository.createFolder(folder("a", "A", null));
        repository.createFolder(folder("b", "B", null));

        Map<String, TextOrganizationRepository.MembershipChange> changes =
                repository.moveTexts(Arrays.asList(
                        "https://lexo.test/text/one#context",
                        "https://lexo.test/text/two#context"), "a", timestamp(1));
        assertThat(changes.values()).allMatch(change -> change.changed);

        TextOrganizationRepository.MembershipSnapshot snapshot =
                repository.detachText("https://lexo.test/text/one#context",
                        timestamp(2));
        assertThat(snapshot.folderId).isEqualTo("a");
        assertThat(repository.membershipsByContext())
                .doesNotContainKey(snapshot.contextUri);
        repository.restoreText(snapshot, timestamp(3));
        assertThat(repository.membershipsByContext())
                .containsEntry(snapshot.contextUri, "a");
        store.shutDown();
    }

    private static FolderRecord folder(String id, String name, String parent) {
        FolderRecord record = new FolderRecord();
        record.folderId = id;
        record.uri = "https://lexo.ilc.cnr.it/resources/text-folders/" + id;
        record.name = name;
        record.parentFolderId = parent;
        record.createdAt = timestamp(0);
        record.modifiedAt = timestamp(0);
        return record;
    }

    private static String timestamp(int second) {
        return String.format("2026-10-23T14:00:%02dZ", Integer.valueOf(second));
    }
}
