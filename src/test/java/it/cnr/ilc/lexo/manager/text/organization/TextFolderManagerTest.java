package it.cnr.ilc.lexo.manager.text.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.text.input.FolderBulkTextMoveRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderCreateRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderMoveRequest;
import it.cnr.ilc.lexo.service.data.text.input.FolderUpdateRequest;
import it.cnr.ilc.lexo.service.data.text.output.FolderRecord;
import it.cnr.ilc.lexo.service.data.text.output.TextRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Supplier;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextFolderManagerTest {

    private SailRepository store;
    private TextFolderManager manager;
    private FakeTexts texts;

    @BeforeEach
    void setUp() {
        store = new SailRepository(new MemoryStore());
        store.init();
        texts = new FakeTexts();
        texts.add("one", "one.txt");
        texts.add("two", "two.txt");
        Queue<String> ids = new ArrayDeque<String>(
                Arrays.asList("root", "child", "other", "empty"));
        Supplier<String> idSupplier = () -> ids.remove();
        manager = new TextFolderManager(new TextOrganizationRepository(store,
                new TextOrganizationVocabulary()), texts,
                Clock.fixed(Instant.parse("2026-10-23T14:00:00Z"),
                        ZoneOffset.UTC), idSupplier);
    }

    @AfterEach
    void tearDown() {
        store.shutDown();
    }

    @Test
    void buildsTreesMovesFoldersAndRejectsCycles() {
        FolderRecord root = manager.create(create(" Root ", null));
        FolderRecord child = manager.create(create("Child", root.folderId));
        manager.create(create("Other", null));

        assertThat(manager.tree(false).folders).hasSize(2);
        assertThat(manager.get(root.folderId, true, false).children)
                .extracting(node -> node.folderId).containsExactly(child.folderId);

        FolderMoveRequest invalid = new FolderMoveRequest();
        invalid.parentFolderId = child.folderId;
        assertThatThrownBy(() -> manager.move(root.folderId, invalid))
                .isInstanceOf(TextOrganizationException.class)
                .extracting(error -> ((TextOrganizationException) error).code)
                .isEqualTo("FOLDER_CYCLE");

        FolderMoveRequest toRoot = new FolderMoveRequest();
        manager.move(child.folderId, toRoot);
        assertThat(manager.get(child.folderId, false, true).parentFolderId).isNull();

        FolderUpdateRequest update = new FolderUpdateRequest();
        update.name = "Updated";
        update.description = "Description";
        assertThat(manager.update(child.folderId, update))
                .extracting(folder -> folder.name, folder -> folder.description)
                .containsExactly("Updated", "Description");
    }

    @Test
    void movesTextsComputesUnfiledAndProtectsNonEmptyFolders() {
        FolderRecord root = manager.create(create("Root", null));
        FolderRecord child = manager.create(create("Child", root.folderId));

        assertThat(manager.assign(root.folderId, "one").changed).isTrue();
        assertThat(manager.assign(root.folderId, "one").changed).isFalse();
        assertThat(manager.assign(child.folderId, "one").previousFolderId)
                .isEqualTo(root.folderId);
        assertThat(manager.unfiled().texts).extracting(item -> item.fileId)
                .containsExactly("two");

        FolderBulkTextMoveRequest bulk = new FolderBulkTextMoveRequest();
        bulk.fileIds.addAll(Arrays.asList("one", "two"));
        bulk.targetFolderId = root.folderId;
        assertThat(manager.bulkMove(bulk).total).isEqualTo(2);
        assertThat(manager.unfiled().total).isZero();
        assertThat(manager.get(root.folderId, false, true).texts)
                .extracting(item -> item.fileId).containsExactly("one", "two");
        assertThat(manager.get(root.folderId, false, true).texts.get(0))
                .satisfies(item -> {
                    assertThat(item.lemmaSearchAvailable).isTrue();
                    assertThat(item.lemmaTokenCount).isEqualTo(2);
                    assertThat(item.lemmaCoverage).isEqualTo(1.0d);
                });

        assertThatThrownBy(() -> manager.delete(root.folderId))
                .isInstanceOf(TextOrganizationException.class)
                .extracting(error -> ((TextOrganizationException) error).code)
                .isEqualTo("FOLDER_NOT_EMPTY");
        manager.remove(root.folderId, "one");
        manager.remove(root.folderId, "two");
        FolderMoveRequest toRoot = new FolderMoveRequest();
        manager.move(child.folderId, toRoot);
        assertThat(manager.delete(root.folderId)).isTrue();
    }

    @Test
    void bulkValidationIsAllOrNothingAndDeletionMembershipCanBeRestored() {
        FolderRecord root = manager.create(create("Root", null));
        manager.assign(root.folderId, "one");

        FolderBulkTextMoveRequest invalid = new FolderBulkTextMoveRequest();
        invalid.fileIds.addAll(Arrays.asList("one", "missing"));
        invalid.targetFolderId = null;
        assertThatThrownBy(() -> manager.bulkMove(invalid))
                .isInstanceOf(TextOrganizationException.class)
                .extracting(error -> ((TextOrganizationException) error).code)
                .isEqualTo("TEXT_NOT_FOUND");
        assertThat(manager.get(root.folderId, false, true).texts)
                .extracting(item -> item.fileId).containsExactly("one");

        TextOrganizationRepository.MembershipSnapshot snapshot =
                manager.detachForTextDeletion(
                        "https://lexo.test/resources/texts/one#context");
        assertThat(manager.unfiled().texts).extracting(item -> item.fileId)
                .contains("one");
        manager.restoreAfterFailedTextDeletion(snapshot);
        assertThat(manager.get(root.folderId, false, true).texts)
                .extracting(item -> item.fileId).containsExactly("one");
    }

    private static FolderCreateRequest create(String name, String parent) {
        FolderCreateRequest request = new FolderCreateRequest();
        request.name = name;
        request.parentFolderId = parent;
        return request;
    }

    private static final class FakeTexts implements TextFolderManager.TextResolver {
        private final Map<String, TextRecord> records =
                new LinkedHashMap<String, TextRecord>();

        void add(String fileId, String name) {
            TextRecord record = new TextRecord();
            record.fileId = fileId;
            record.documentUri = "https://lexo.test/resources/texts/" + fileId;
            record.originalFileName = name;
            if ("one".equals(fileId)) {
                record.lemmaSearchAvailable = Boolean.TRUE;
                record.lemmaTokenCount = Integer.valueOf(2);
                record.lemmaCoverage = Double.valueOf(1.0d);
            }
            records.put(fileId, record);
        }

        @Override
        public TextRecord get(String fileId) {
            return records.get(fileId);
        }

        @Override
        public List<String> listFileIds() {
            return new ArrayList<String>(records.keySet());
        }
    }
}
