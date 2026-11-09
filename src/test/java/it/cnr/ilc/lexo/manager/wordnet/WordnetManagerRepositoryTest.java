package it.cnr.ilc.lexo.manager.wordnet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import it.cnr.ilc.lexo.service.data.metadata.MetadataPatchRequest;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ChildChanges;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.CountData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.DefinitionData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.DefinitionUpdate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.EntryCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.EntryPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ExampleData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.PronunciationData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.RelationCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.RelationPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SenseCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SensePatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.StatusBatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.StatusChange;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SyntacticBehaviourData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SynsetCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SynsetPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.TagData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.WordnetCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.WordnetPatch;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WordnetManagerRepositoryTest {

    private static final String WORDNET_ID = "italwordnet";
    private static final IRI GRAPH = iri(
            "https://lexo.ilc.cnr.it/graphs/wordnet/it/italwordnet");
    private static final IRI OTHER_GRAPH = iri(
            "https://lexo.ilc.cnr.it/graphs/wordnet/it/otherwordnet");
    private static final IRI SCHEMA = iri(
            "https://lexo.ilc.cnr.it/graphs/wordnet/schema");

    private SailRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SailRepository(new MemoryStore());
        repository.init();
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(iri(WordnetVocabulary.WN_NS + "noun"), RDF.TYPE,
                    WordnetVocabulary.POS_TYPE, SCHEMA);
            connection.add(iri(WordnetVocabulary.WN_NS + "verb"), RDF.TYPE,
                    WordnetVocabulary.POS_TYPE, SCHEMA);
            connection.add(iri(WordnetVocabulary.WN_NS + "hypernym"), RDF.TYPE,
                    WordnetVocabulary.SYNSET_REL_TYPE, SCHEMA);
            connection.add(iri(WordnetVocabulary.WN_NS + "derivation"), RDF.TYPE,
                    WordnetVocabulary.SENSE_REL_TYPE, SCHEMA);
        }
    }

    @AfterEach
    void tearDown() {
        repository.shutDown();
    }

    @Test
    void completeCrudUsesOneApplicationGraphAndLeavesDefaultGraphEmpty() {
        WordnetRegistryManager registry = new WordnetRegistryManager(repository);
        WordnetEntryManager entries = new WordnetEntryManager(repository);
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        WordnetSenseManager senses = new WordnetSenseManager(repository);
        WordnetRelationManager relations = new WordnetRelationManager(repository);
        WordnetEntryStatusManager statuses =
                new WordnetEntryStatusManager(repository);

        Map<String, Object> root = registry.create(wordnet("italwordnet", "it"),
                "editor");
        Map<String, Object> entry = entries.create(WORDNET_ID,
                entry("cane", "wn:noun"), "editor");
        Map<String, Object> firstSynset = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        Map<String, Object> secondSynset = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");

        SenseCreate senseRequest = new SenseCreate();
        senseRequest.entryId = string(entry, "id");
        senseRequest.synsetId = string(firstSynset, "id");
        Map<String, Object> sense = senses.create(WORDNET_ID, senseRequest,
                "editor");

        RelationCreate relationRequest = new RelationCreate();
        relationRequest.scope = "synset";
        relationRequest.sourceId = string(firstSynset, "id");
        relationRequest.targetId = string(secondSynset, "id");
        relationRequest.category = "wn:hypernym";
        Map<String, Object> relation = relations.create(WORDNET_ID,
                relationRequest, "editor");

        assertThat(root).containsEntry("graph", GRAPH.stringValue());
        assertThat(entry).containsEntry("workflowStatus", "working");
        firstSynset = synsets.get(WORDNET_ID, string(firstSynset, "id"),
                "members");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members =
                (List<Map<String, Object>>) firstSynset.get("members");
        assertThat(members).extracting(item -> item.get("senseId"))
                .containsExactly((Object) string(sense, "id"));
        assertThat(relation).containsEntry("scope", "synset")
                .containsEntry("category", WordnetVocabulary.WN_NS + "hypernym");

        StatusChange change = new StatusChange();
        change.entryId = string(entry, "id");
        change.fromStatus = "working";
        change.toStatus = "completed";
        StatusBatch batch = new StatusBatch();
        batch.entries = Collections.singletonList(change);
        statuses.change(WORDNET_ID, batch, "reviewer");
        assertThat(statuses.get(WORDNET_ID, string(entry, "id")))
                .containsEntry("workflowStatus", "completed");

        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(GRAPH)).isGreaterThan(0L);
            assertThat(connection.size(OTHER_GRAPH)).isZero();
            assertThat(connection.size((Resource) null)).isZero();
            try (RepositoryResult<Statement> statements =
                    connection.getStatements(null, null, null, false, GRAPH)) {
                while (statements.hasNext()) {
                    assertThat(statements.next().getContext()).isEqualTo(GRAPH);
                }
            }
        }
    }

    @Test
    void resolverIsolatesWordnetsAndRejectsDuplicateIdsAcrossLanguages() {
        WordnetRegistryManager registry = new WordnetRegistryManager(repository);
        registry.create(wordnet("italwordnet", "it"), "editor");
        registry.create(wordnet("otherwordnet", "it"), "editor");
        registry.create(wordnet("oewn", "en"), "editor");

        Map<String, Object> page = registry.list(null, null, 25, 0);
        assertThat(page.get("total")).isEqualTo(Integer.valueOf(3));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed =
                (List<Map<String, Object>>) page.get("items");
        assertThat(listed).extracting(item -> item.get("iri"))
                .isSortedAccordingTo((left, right) -> ((String) left)
                        .compareTo((String) right));
        assertThatThrownBy(() -> registry.create(
                wordnet("italwordnet", "en"), "editor"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("WORDNET_ALREADY_EXISTS"));
        assertThatThrownBy(() -> registry.get("italwordnet", ""))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("INVALID_SECTION"));

        WordnetEntryManager entries = new WordnetEntryManager(repository);
        Map<String, Object> entry = entries.create("italwordnet",
                entry("cane", "wn:noun"), "editor");
        assertThatThrownBy(() -> entries.get("otherwordnet",
                string(entry, "id"), "core"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("ENTRY_NOT_FOUND"));

        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(iri("https://example.org/malformed"), RDF.TYPE,
                    WordnetVocabulary.LEXICON,
                    iri("https://lexo.ilc.cnr.it/graphs/wordnet/not-a-wordnet"));
            assertThat(new WordnetGraphResolver(repository).list(connection))
                    .extracting(item -> item.wordnetId)
                    .containsExactly("italwordnet", "oewn", "otherwordnet");
        }
    }

    @Test
    void protectedMetadataRollsBackTheWholeNestedCreation() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        EntryCreate invalid = entry("cane", "wn:noun");
        invalid.metadata = Collections.singletonList(metadata(
                WordnetVocabulary.WN_NS + "note",
                new RdfMetadataValue("must fail", "literal", null, null)));

        long before;
        try (RepositoryConnection connection = repository.getConnection()) {
            before = connection.size(GRAPH);
        }
        assertThatThrownBy(() -> new WordnetEntryManager(repository)
                .create(WORDNET_ID, invalid, "editor"))
                .isInstanceOf(RuntimeException.class);
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(GRAPH)).isEqualTo(before);
            assertThat(connection.hasStatement(null, RDF.TYPE,
                    WordnetVocabulary.ENTRY, false, GRAPH)).isFalse();
        }
    }

    @Test
    void duplicateRelationDoesNotLeaveAnOrphan() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        Map<String, Object> source = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        Map<String, Object> target = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        RelationCreate request = new RelationCreate();
        request.scope = "synset";
        request.sourceId = string(source, "id");
        request.targetId = string(target, "id");
        request.category = "wn:hypernym";
        WordnetRelationManager manager = new WordnetRelationManager(repository);
        manager.create(WORDNET_ID, request, "editor");
        long before;
        try (RepositoryConnection connection = repository.getConnection()) {
            before = connection.size(GRAPH);
        }

        assertThatThrownBy(() -> manager.create(WORDNET_ID, request, "editor"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("RELATION_ALREADY_EXISTS"));
        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(GRAPH)).isEqualTo(before);
        }
    }

    @Test
    void nestedChildUpdatePreservesIdentityAndPatchesNativeAndRdfMetadata() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        DefinitionData definition = new DefinitionData();
        definition.kind = "definition";
        definition.text = "un animale domestico";
        definition.language = "it";
        SynsetCreate request = synset("wn:noun");
        request.definitions = Collections.singletonList(definition);
        WordnetSynsetManager manager = new WordnetSynsetManager(repository);
        Map<String, Object> created = manager.create(WORDNET_ID, request, "editor");
        @SuppressWarnings("unchecked")
        Map<String, Object> createdDefinition =
                ((List<Map<String, Object>>) created.get("definitions")).get(0);

        DefinitionUpdate update = new DefinitionUpdate();
        update.id = string(createdDefinition, "id");
        update.setNotes(Collections.singletonList("reviewed"));
        update.metadata = new MetadataPatchRequest();
        update.metadata.addValues = Collections.singletonList(metadata(
                "https://example.org/quality",
                new RdfMetadataValue("gold", "literal", "en", null)));
        SynsetPatch patch = new SynsetPatch();
        patch.definitions = new ChildChanges<DefinitionData, DefinitionUpdate>();
        patch.definitions.update = Collections.singletonList(update);

        Map<String, Object> changed = manager.patch(WORDNET_ID,
                string(created, "id"), patch, "reviewer");
        @SuppressWarnings("unchecked")
        Map<String, Object> changedDefinition =
                ((List<Map<String, Object>>) changed.get("definitions")).get(0);
        assertThat(changedDefinition).containsEntry("id", update.id)
                .containsEntry("text", "un animale domestico")
                .containsEntry("language", "it");
        assertThat(changedDefinition.get("notes")).asList()
                .containsExactly("reviewed");
        assertThat(changedDefinition.get("metadata")).asList().hasSize(1);

        SynsetPatch noOp = new SynsetPatch();
        noOp.definitions = new ChildChanges<DefinitionData, DefinitionUpdate>();
        assertThatThrownBy(() -> manager.patch(WORDNET_ID,
                string(created, "id"), noOp, "reviewer"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("INVALID_PATCH"));
    }

    @Test
    void workflowBatchValidatesEveryTransitionBeforeWriting() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        WordnetEntryManager entryManager = new WordnetEntryManager(repository);
        Map<String, Object> first = entryManager.create(WORDNET_ID,
                entry("cane", "wn:noun"), "editor");
        Map<String, Object> second = entryManager.create(WORDNET_ID,
                entry("gatto", "wn:noun"), "editor");
        StatusChange valid = transition(string(first, "id"),
                "working", "completed");
        StatusChange stale = transition(string(second, "id"),
                "completed", "revised");
        StatusBatch batch = new StatusBatch();
        batch.entries = Arrays.asList(valid, stale);
        WordnetEntryStatusManager manager =
                new WordnetEntryStatusManager(repository);

        assertThatThrownBy(() -> manager.change(WORDNET_ID, batch, "reviewer"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("STATUS_MISMATCH"));
        assertThat(manager.get(WORDNET_ID, string(first, "id")))
                .containsEntry("workflowStatus", "working")
                .containsEntry("statusChangedBy", null);
        assertThatThrownBy(() -> manager.change(WORDNET_ID,
                batch(transition(string(first, "id"), "working", "revised")),
                "reviewer"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("STATUS_TRANSITION_NOT_ALLOWED"));
    }

    @Test
    void detailSectionsAndRelationDirectionAreStrict() {
        WordnetRegistryManager registry = new WordnetRegistryManager(repository);
        registry.create(wordnet(WORDNET_ID, "it"), "editor");
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        Map<String, Object> source = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        Map<String, Object> target = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        WordnetRelationManager relations = new WordnetRelationManager(repository);
        RelationCreate outbound = new RelationCreate();
        outbound.scope = "synset";
        outbound.sourceId = string(source, "id");
        outbound.targetId = string(target, "id");
        outbound.category = "wn:hypernym";
        relations.create(WORDNET_ID, outbound, "editor");
        RelationCreate inbound = new RelationCreate();
        inbound.scope = "synset";
        inbound.sourceId = string(target, "id");
        inbound.targetId = string(source, "id");
        inbound.category = "wn:hypernym";
        relations.create(WORDNET_ID, inbound, "editor");

        Map<String, Object> metadataOnly = synsets.get(WORDNET_ID,
                string(source, "id"), "metadata");
        assertThat(metadataOnly).containsKeys("id", "iri", "resourceType",
                "wordnetId", "creator", "created", "modified", "metadata")
                .doesNotContainKeys("partOfSpeech", "ili", "lexfile", "notes",
                        "wordnetStatus", "confidenceScore");

        Map<String, Object> outgoing = synsets.get(WORDNET_ID,
                string(source, "id"), "relations", "out");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outgoingItems =
                (List<Map<String, Object>>) outgoing.get("relations");
        assertThat(outgoingItems).extracting(item -> item.get("direction"))
                .containsExactly("out");
        assertThatThrownBy(() -> synsets.get(WORDNET_ID,
                string(source, "id"), "core", "out"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("INVALID_SECTION"));
    }

    @Test
    void sharedMemberListCellsAndAdditionalLexiconRootsAreIntegrityConflicts() {
        WordnetRegistryManager registry = new WordnetRegistryManager(repository);
        registry.create(wordnet(WORDNET_ID, "it"), "editor");
        WordnetEntryManager entries = new WordnetEntryManager(repository);
        Map<String, Object> entry = entries.create(WORDNET_ID,
                entry("cane", "wn:noun"), "editor");
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        Map<String, Object> synset = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        SenseCreate senseRequest = new SenseCreate();
        senseRequest.entryId = string(entry, "id");
        senseRequest.synsetId = string(synset, "id");
        WordnetSenseManager senses = new WordnetSenseManager(repository);
        Map<String, Object> sense = senses.create(WORDNET_ID, senseRequest,
                "editor");

        try (RepositoryConnection connection = repository.getConnection()) {
            IRI synsetIri = iri((String) synset.get("iri"));
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    synsetIri, WordnetVocabulary.MEMBER_LIST, null, false,
                    GRAPH)) {
                Resource head = (Resource) statements.next().getObject();
                connection.add(iri("https://example.org/shared-list-owner"),
                        iri("https://example.org/shared-list"), head, GRAPH);
            }
        }
        assertThatThrownBy(() -> senses.delete(WORDNET_ID,
                string(sense, "id"), null))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("GRAPH_INTEGRITY_CONFLICT"));
        assertThat(senses.get(WORDNET_ID, string(sense, "id"), "core"))
                .containsEntry("n", Integer.valueOf(1));

        IRI corruptGraph = iri(
                "https://lexo.ilc.cnr.it/graphs/wordnet/en/corruptwn");
        try (RepositoryConnection connection = repository.getConnection()) {
            IRI root = iri("https://example.org/corrupt-root");
            connection.add(root, RDF.TYPE, WordnetVocabulary.LEXICON,
                    corruptGraph);
            connection.add(root, WordnetVocabulary.LANGUAGE,
                    connection.getValueFactory().createLiteral("en"),
                    corruptGraph);
            connection.add(connection.getValueFactory().createBNode(), RDF.TYPE,
                    WordnetVocabulary.LEXICON, corruptGraph);
            assertThatThrownBy(() -> new WordnetGraphResolver(repository)
                    .list(connection))
                    .isInstanceOfSatisfying(WordnetException.class,
                            exception -> assertThat(exception.code)
                                    .isEqualTo("GRAPH_INTEGRITY_CONFLICT"));
        }
    }

    @Test
    void mutationsRejectTypedButStructurallyOrphanedResources() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        WordnetEntryManager entries = new WordnetEntryManager(repository);
        Map<String, Object> validEntry = entries.create(WORDNET_ID,
                entry("cane", "wn:noun"), "editor");
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        Map<String, Object> validSynset = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        IRI orphanEntry = iri("https://example.org/orphan-entry");
        IRI orphanSense = iri("https://example.org/orphan-sense");
        IRI malformedSynset = iri("https://example.org/malformed-synset");
        try (RepositoryConnection connection = repository.getConnection()) {
            connection.add(orphanEntry, RDF.TYPE, WordnetVocabulary.ENTRY, GRAPH);
            connection.add(orphanEntry, WordnetVocabulary.PART_OF_SPEECH,
                    iri(WordnetVocabulary.WN_NS + "noun"), GRAPH);
            connection.add(orphanEntry, WordnetVocabulary.WORKFLOW_STATUS,
                    connection.getValueFactory().createLiteral("working"), GRAPH);
            connection.add(orphanSense, RDF.TYPE, WordnetVocabulary.SENSE, GRAPH);
            connection.add(malformedSynset, RDF.TYPE, WordnetVocabulary.SYNSET,
                    GRAPH);
            connection.add(malformedSynset, WordnetVocabulary.PART_OF_SPEECH,
                    iri(WordnetVocabulary.WN_NS + "noun"), GRAPH);
            connection.add(malformedSynset, WordnetVocabulary.MEMBER_LIST,
                    RDF.NIL, GRAPH);
        }
        long before;
        try (RepositoryConnection connection = repository.getConnection()) {
            before = connection.size(GRAPH);
        }

        FormCreate form = new FormCreate();
        form.entryId = WordnetIdCodec.encode(orphanEntry.stringValue());
        form.kind = "other";
        form.writtenRep = "orfana";
        assertIntegrityConflict(() -> new WordnetFormManager(repository)
                .create(WORDNET_ID, form, "editor"));

        SenseCreate sense = sense(string(validEntry, "id"),
                WordnetIdCodec.encode(malformedSynset.stringValue()));
        assertIntegrityConflict(() -> new WordnetSenseManager(repository)
                .create(WORDNET_ID, sense, "editor"));

        RelationCreate relation = new RelationCreate();
        relation.scope = "synset";
        relation.sourceId = string(validSynset, "id");
        relation.targetId = WordnetIdCodec.encode(malformedSynset.stringValue());
        relation.category = "wn:hypernym";
        assertIntegrityConflict(() -> new WordnetRelationManager(repository)
                .create(WORDNET_ID, relation, "editor"));
        assertIntegrityConflict(() -> new WordnetRelationManager(repository)
                .list(WORDNET_ID, "synset", null, relation.targetId, null,
                        25, 0));
        String orphanEntryId = WordnetIdCodec.encode(orphanEntry.stringValue());
        WordnetEntryStatusManager statuses =
                new WordnetEntryStatusManager(repository);
        assertIntegrityConflict(() -> statuses.get(WORDNET_ID, orphanEntryId));
        assertIntegrityConflict(() -> statuses.change(WORDNET_ID,
                batch(transition(orphanEntryId, "working", "completed")),
                "editor"));
        SynsetCreate sourcedSynset = synset("wn:noun");
        DefinitionData sourcedDefinition = new DefinitionData();
        sourcedDefinition.kind = "definition";
        sourcedDefinition.text = "Invalid source";
        sourcedDefinition.language = "en";
        sourcedDefinition.sourceSenseId = WordnetIdCodec.encode(
                orphanSense.stringValue());
        sourcedSynset.definitions = Collections.singletonList(sourcedDefinition);
        assertIntegrityConflict(() -> synsets.create(WORDNET_ID,
                sourcedSynset, "editor"));

        try (RepositoryConnection connection = repository.getConnection()) {
            assertThat(connection.size(GRAPH)).isEqualTo(before);
            assertThat(connection.hasStatement(orphanEntry,
                    WordnetVocabulary.WORKFLOW_STATUS,
                    connection.getValueFactory().createLiteral("working"),
                    false, GRAPH)).isTrue();
            assertThat(connection.hasStatement(null, WordnetVocabulary.WRITTEN_REP,
                    connection.getValueFactory().createLiteral("orfana", "it"),
                    false, GRAPH)).isFalse();
        }
    }

    @Test
    void canonicalPromotionAndFormDeletionPreserveExactlyOneCanonicalForm() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        EntryCreate request = entry("cane", "wn:noun");
        FormData plural = new FormData();
        plural.writtenRep = "cani";
        request.otherForms = Collections.singletonList(plural);
        WordnetEntryManager entries = new WordnetEntryManager(repository);
        Map<String, Object> entry = entries.create(WORDNET_ID, request, "editor");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> forms = (List<Map<String, Object>>) entry.get("forms");
        Map<String, Object> canonical = forms.stream()
                .filter(item -> "canonical".equals(item.get("kind"))).findFirst().get();
        Map<String, Object> other = forms.stream()
                .filter(item -> "other".equals(item.get("kind"))).findFirst().get();

        EntryPatch promote = new EntryPatch();
        promote.setCanonicalFormId(string(other, "id"));
        entries.patch(WORDNET_ID, string(entry, "id"), promote, "reviewer");
        WordnetFormManager formManager = new WordnetFormManager(repository);
        assertThat(formManager.get(WORDNET_ID, string(other, "id"), "core"))
                .containsEntry("kind", "canonical");
        assertThat(formManager.get(WORDNET_ID, string(canonical, "id"), "core"))
                .containsEntry("kind", "other");

        FormPatch stale = new FormPatch();
        stale.setWrittenRep("cagnolini");
        stale.setExpectedModified("stale");
        assertThatThrownBy(() -> formManager.patch(WORDNET_ID,
                string(canonical, "id"), stale, "reviewer"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("MODIFIED_MISMATCH"));
        assertThat(formManager.get(WORDNET_ID, string(canonical, "id"), "core"))
                .containsEntry("writtenRep", "cane");

        formManager.delete(WORDNET_ID, string(canonical, "id"), null);
        assertThatThrownBy(() -> formManager.delete(WORDNET_ID,
                string(other, "id"), null))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("CANONICAL_FORM_REQUIRED"));
        assertThat(formManager.list(WORDNET_ID, string(entry, "id"), null,
                null, 25, 0).get("total")).isEqualTo(Integer.valueOf(1));
    }

    @Test
    void senseMoveReorderAndDeleteKeepMemberListsConsistent() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        WordnetEntryManager entries = new WordnetEntryManager(repository);
        Map<String, Object> entry = entries.create(WORDNET_ID,
                entry("cane", "wn:noun"), "editor");
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        Map<String, Object> source = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        Map<String, Object> target = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        WordnetSenseManager senses = new WordnetSenseManager(repository);
        Map<String, Object> first = senses.create(WORDNET_ID,
                sense(string(entry, "id"), string(source, "id")), "editor");
        Map<String, Object> second = senses.create(WORDNET_ID,
                sense(string(entry, "id"), string(source, "id")), "editor");

        SynsetPatch reorder = new SynsetPatch();
        reorder.setMemberOrder(Arrays.asList(string(second, "id"),
                string(first, "id")));
        Map<String, Object> reordered = synsets.patch(WORDNET_ID,
                string(source, "id"), reorder, "reviewer");
        assertThat(memberIds(reordered)).containsExactly(string(second, "id"),
                string(first, "id"));

        SensePatch stale = new SensePatch();
        stale.setN(Integer.valueOf(9));
        stale.setExpectedModified("stale");
        assertThatThrownBy(() -> senses.patch(WORDNET_ID,
                string(first, "id"), stale, "reviewer"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("MODIFIED_MISMATCH"));

        SensePatch move = new SensePatch();
        move.setSynsetId(string(target, "id"));
        senses.patch(WORDNET_ID, string(first, "id"), move, "reviewer");
        assertThat(memberIds(synsets.get(WORDNET_ID, string(source, "id"),
                "members"))).containsExactly(string(second, "id"));
        assertThat(memberIds(synsets.get(WORDNET_ID, string(target, "id"),
                "members"))).containsExactly(string(first, "id"));

        senses.delete(WORDNET_ID, string(first, "id"), null);
        assertThat(memberIds(synsets.get(WORDNET_ID, string(target, "id"),
                "members"))).isEmpty();
        assertThatThrownBy(() -> synsets.delete(WORDNET_ID,
                string(source, "id"), null))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("SYNSET_HAS_MEMBERS"));
        senses.delete(WORDNET_ID, string(second, "id"), null);
        synsets.delete(WORDNET_ID, string(source, "id"), null);
        synsets.delete(WORDNET_ID, string(target, "id"), null);
        entries.delete(WORDNET_ID, string(entry, "id"), null);
        new WordnetRegistryManager(repository).delete(WORDNET_ID, null);
        assertThat(new WordnetRegistryManager(repository)
                .list(null, null, 25, 0).get("total"))
                .isEqualTo(Integer.valueOf(0));
    }

    @Test
    void richEntrySynsetAndSenseChildrenUseTheNormativeRdfMapping() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        EntryCreate entryRequest = entry("cane", "wn:noun");
        entryRequest.canonicalForm.script = "Latn";
        TagData tag = new TagData();
        tag.category = "number";
        tag.value = "singular";
        entryRequest.canonicalForm.tags = Collections.singletonList(tag);
        PronunciationData pronunciation = new PronunciationData();
        pronunciation.transcription = "kane";
        pronunciation.notation = "IPA";
        pronunciation.phonemic = Boolean.TRUE;
        pronunciation.audio = "https://example.org/audio/cane.ogg";
        entryRequest.canonicalForm.pronunciations =
                Collections.singletonList(pronunciation);
        SyntacticBehaviourData behaviour = new SyntacticBehaviourData();
        behaviour.label = "transitive";
        entryRequest.syntacticBehaviours = Collections.singletonList(behaviour);
        WordnetEntryManager entries = new WordnetEntryManager(repository);
        Map<String, Object> entry = entries.create(WORDNET_ID, entryRequest,
                "editor");
        @SuppressWarnings("unchecked")
        Map<String, Object> form = ((List<Map<String, Object>>) entry.get("forms")).get(0);
        assertThat(form).containsEntry("script", "Latn");
        assertThat(form.get("tags")).asList().hasSize(1);
        assertThat(form.get("pronunciations")).asList().hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> behaviourResponse =
                ((List<Map<String, Object>>) entry.get("syntacticBehaviours")).get(0);

        DefinitionData definition = new DefinitionData();
        definition.kind = "definition";
        definition.text = "Un animale domestico";
        definition.language = "it";
        DefinitionData iliDefinition = new DefinitionData();
        iliDefinition.kind = "iliDefinition";
        iliDefinition.text = "A domestic animal";
        iliDefinition.language = "en";
        ExampleData synsetExample = new ExampleData();
        synsetExample.text = "Il cane dorme.";
        synsetExample.language = "it";
        SynsetCreate synsetRequest = synset("wn:noun");
        synsetRequest.ili = "http://ili.globalwordnet.org/ili/i90287";
        synsetRequest.lexfile = "noun.animal";
        synsetRequest.definitions = Arrays.asList(definition, iliDefinition);
        synsetRequest.examples = Collections.singletonList(synsetExample);
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        Map<String, Object> synset = synsets.create(WORDNET_ID, synsetRequest,
                "editor");
        assertThat(synset.get("definitions")).asList().hasSize(2);
        assertThat(synset.get("examples")).asList().hasSize(1);

        ExampleData senseExample = new ExampleData();
        senseExample.text = "Vedo un cane.";
        senseExample.language = "it";
        CountData count = new CountData();
        count.value = Integer.valueOf(12);
        SenseCreate senseRequest = sense(string(entry, "id"), string(synset, "id"));
        senseRequest.examples = Collections.singletonList(senseExample);
        senseRequest.counts = Collections.singletonList(count);
        senseRequest.subcategorizationIds = Collections.singletonList(
                string(behaviourResponse, "id"));
        WordnetSenseManager senseManager = new WordnetSenseManager(repository);
        Map<String, Object> sense = senseManager.create(WORDNET_ID, senseRequest,
                "editor");
        assertThat(sense.get("examples")).asList().hasSize(1);
        assertThat(sense.get("counts")).asList().hasSize(1);
        assertThat(sense.get("subcategorization")).asList().hasSize(1);

        SensePatch nullSubcategories = new SensePatch();
        nullSubcategories.setSubcategorizationIds(null);
        assertThatThrownBy(() -> senseManager.patch(WORDNET_ID,
                string(sense, "id"), nullSubcategories, "reviewer"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("INVALID_PATCH"));
        assertThat(senseManager.get(WORDNET_ID, string(sense, "id"),
                "subcategorization").get("subcategorization"))
                .asList().hasSize(1);

        Map<String, Object> secondSense = senseManager.create(WORDNET_ID,
                sense(string(entry, "id"), string(synset, "id")), "editor");
        RelationCreate relationRequest = new RelationCreate();
        relationRequest.scope = "sense";
        relationRequest.sourceId = string(sense, "id");
        relationRequest.targetId = string(secondSense, "id");
        relationRequest.category = "wn:derivation";
        Map<String, Object> senseRelation = new WordnetRelationManager(repository)
                .create(WORDNET_ID, relationRequest, "editor");

        try (RepositoryConnection connection = repository.getConnection()) {
            Literal writtenRep = literal(connection, iri(string(form, "iri")),
                    WordnetVocabulary.WRITTEN_REP);
            assertThat(writtenRep.getLanguage()).contains("it");
            @SuppressWarnings("unchecked")
            Map<String, Object> pronunciationResponse =
                    ((List<Map<String, Object>>) form.get("pronunciations")).get(0);
            assertThat(literal(connection,
                    iri(string(pronunciationResponse, "iri")),
                    WordnetVocabulary.AUDIO).getDatatype()).isEqualTo(XSD.ANYURI);
            @SuppressWarnings("unchecked")
            Map<String, Object> countResponse =
                    ((List<Map<String, Object>>) sense.get("counts")).get(0);
            assertThat(literal(connection, iri(string(countResponse, "iri")),
                    RDF.VALUE).getDatatype()).isEqualTo(XSD.NON_NEGATIVE_INTEGER);
            assertThat(connection.hasStatement(iri(string(behaviourResponse, "iri")),
                    WordnetVocabulary.SENSE_SUBCAT, iri(string(sense, "iri")),
                    false, GRAPH)).isTrue();
            IRI relationIri = iri(string(senseRelation, "iri"));
            assertThat(connection.hasStatement(relationIri, RDF.TYPE,
                    WordnetVocabulary.SENSE_RELATION, false, GRAPH)).isTrue();
            assertThat(connection.hasStatement(relationIri, RDF.TYPE,
                    WordnetVocabulary.CONCEPTUAL_RELATION, false, GRAPH)).isFalse();
        }
    }

    @Test
    void arbitraryMetadataAndNativeAnnotationsRoundTripAndPatchExactly() {
        WordnetCreate request = wordnet(WORDNET_ID, "it");
        String property = "https://example.org/source";
        RdfMetadataValue originalIri = new RdfMetadataValue(
                "https://example.org/source/1", "iri", null, null);
        request.metadata = Arrays.asList(request.metadata.get(0), metadata(property,
                originalIri,
                new RdfMetadataValue("plain", "literal", null, null),
                new RdfMetadataValue("italiano", "literal", "it", null),
                new RdfMetadataValue("7", "literal", null,
                        "http://www.w3.org/2001/XMLSchema#integer")));
        WordnetRegistryManager registry = new WordnetRegistryManager(repository);
        Map<String, Object> created = registry.create(request, "editor");
        assertThat(created.get("notes")).asList().containsExactly("curated");
        assertThat(metadataProperty(created, property).values).hasSize(4);

        WordnetPatch stale = new WordnetPatch();
        stale.setNotes(Collections.singletonList("must not persist"));
        stale.setExpectedModified("stale");
        assertThatThrownBy(() -> registry.patch(WORDNET_ID, stale, "reviewer"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("MODIFIED_MISMATCH"));

        RdfMetadataValue replacementIri = new RdfMetadataValue(
                "https://example.org/source/2", "iri", null, null);
        WordnetPatch patch = new WordnetPatch();
        patch.setNotes(Collections.<String>emptyList());
        patch.setWordnetStatus(null);
        patch.setConfidenceScore(null);
        patch.metadata = new MetadataPatchRequest();
        patch.metadata.addValues = Collections.singletonList(metadata(property,
                replacementIri));
        patch.metadata.removeValues = Collections.singletonList(metadata(property,
                originalIri));
        Map<String, Object> changed = registry.patch(WORDNET_ID, patch,
                "reviewer");
        assertThat(changed.get("notes")).asList().isEmpty();
        assertThat(changed).containsEntry("wordnetStatus", null)
                .containsEntry("confidenceScore", null);
        RdfMetadataProperty metadata = metadataProperty(changed, property);
        assertThat(metadata.values).extracting(value -> value.value)
                .contains("https://example.org/source/2", "plain", "italiano", "7")
                .doesNotContain("https://example.org/source/1");
    }

    @Test
    void relationPatchAndDeletePreserveIdentityAndHonorPreconditions() {
        new WordnetRegistryManager(repository).create(
                wordnet(WORDNET_ID, "it"), "editor");
        WordnetSynsetManager synsets = new WordnetSynsetManager(repository);
        Map<String, Object> first = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        Map<String, Object> second = synsets.create(WORDNET_ID,
                synset("wn:noun"), "editor");
        RelationCreate create = new RelationCreate();
        create.scope = "synset";
        create.sourceId = string(first, "id");
        create.targetId = string(second, "id");
        create.category = "wn:hypernym";
        WordnetRelationManager relations = new WordnetRelationManager(repository);
        Map<String, Object> relation = relations.create(WORDNET_ID, create,
                "editor");

        RelationPatch reverse = new RelationPatch();
        reverse.setSourceId(string(second, "id"));
        reverse.setTargetId(string(first, "id"));
        Map<String, Object> changed = relations.patch(WORDNET_ID,
                string(relation, "id"), reverse, "reviewer");
        assertThat(changed).containsEntry("id", string(relation, "id"))
                .containsEntry("sourceId", string(second, "id"))
                .containsEntry("targetId", string(first, "id"));

        assertThatThrownBy(() -> relations.delete(WORDNET_ID,
                string(relation, "id"), "stale"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("MODIFIED_MISMATCH"));
        relations.delete(WORDNET_ID, string(relation, "id"), null);
        assertThatThrownBy(() -> relations.get(WORDNET_ID,
                string(relation, "id"), "core"))
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("RELATION_NOT_FOUND"));
    }

    private static WordnetCreate wordnet(String id, String language) {
        WordnetCreate request = new WordnetCreate();
        request.wordnetId = id;
        request.language = language;
        request.notes = Arrays.asList("curated", "curated");
        request.wordnetStatus = "checked";
        request.confidenceScore = new BigDecimal("0.90");
        request.metadata = Collections.singletonList(metadata(
                "http://www.w3.org/2000/01/rdf-schema#label",
                new RdfMetadataValue(id, "literal", language, null)));
        return request;
    }

    private static EntryCreate entry(String lemma, String pos) {
        EntryCreate request = new EntryCreate();
        request.partOfSpeech = pos;
        request.index = lemma;
        request.canonicalForm = new FormData();
        request.canonicalForm.writtenRep = lemma;
        return request;
    }

    private static SynsetCreate synset(String pos) {
        SynsetCreate request = new SynsetCreate();
        request.partOfSpeech = pos;
        return request;
    }

    private static SenseCreate sense(String entryId, String synsetId) {
        SenseCreate request = new SenseCreate();
        request.entryId = entryId;
        request.synsetId = synsetId;
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<String> memberIds(Map<String, Object> synset) {
        List<Map<String, Object>> members =
                (List<Map<String, Object>>) synset.get("members");
        List<String> result = new java.util.ArrayList<String>();
        for (Map<String, Object> member : members) {
            result.add(string(member, "senseId"));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static RdfMetadataProperty metadataProperty(
            Map<String, Object> resource, String property) {
        List<RdfMetadataProperty> metadata =
                (List<RdfMetadataProperty>) resource.get("metadata");
        return metadata.stream().filter(item -> property.equals(item.property))
                .findFirst().get();
    }

    private static StatusChange transition(String id, String from, String to) {
        StatusChange change = new StatusChange();
        change.entryId = id;
        change.fromStatus = from;
        change.toStatus = to;
        return change;
    }

    private static StatusBatch batch(StatusChange... changes) {
        StatusBatch result = new StatusBatch();
        result.entries = Arrays.asList(changes);
        return result;
    }

    private static RdfMetadataProperty metadata(String property,
            RdfMetadataValue... values) {
        RdfMetadataProperty result = new RdfMetadataProperty();
        result.property = property;
        result.values = Arrays.asList(values);
        return result;
    }

    private static void assertIntegrityConflict(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(WordnetException.class,
                        exception -> assertThat(exception.code)
                                .isEqualTo("GRAPH_INTEGRITY_CONFLICT"));
    }

    private static String string(Map<String, Object> map, String key) {
        return (String) map.get(key);
    }

    private static IRI iri(String value) {
        ValueFactory vf = SimpleValueFactory.getInstance();
        return vf.createIRI(value);
    }

    private static Literal literal(RepositoryConnection connection, IRI subject,
            IRI predicate) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, GRAPH)) {
            assertThat(statements.hasNext()).isTrue();
            return (Literal) statements.next().getObject();
        }
    }
}
