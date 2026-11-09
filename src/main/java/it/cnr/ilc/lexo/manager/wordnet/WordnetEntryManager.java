package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ChildChanges;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.EntryCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.EntryPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SyntacticBehaviourData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SyntacticBehaviourUpdate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** WordNet-specific LexicalEntry CRUD and projections. */
public final class WordnetEntryManager extends AbstractWordnetManager {

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final WordnetFormManager formManager;

    public WordnetEntryManager() {
        this(null);
    }

    public WordnetEntryManager(Repository repository) {
        super(repository);
        this.formManager = new WordnetFormManager(repository);
    }

    public Map<String, Object> create(String wordnetId, EntryCreate request,
            String author) {
        if (request == null || request.canonicalForm == null) {
            throw rdf.invalid("MISSING_REQUIRED_FIELD",
                    "partOfSpeech and canonicalForm are required");
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI pos = validatePartOfSpeech(connection, request.partOfSpeech);
            connection.begin();
            IRI entry = rdf.mint(connection);
            String timestamp = rdf.timestamp();
            connection.add(wordnet.root, WordnetVocabulary.ENTRY_LINK, entry,
                    wordnet.graph);
            connection.add(entry, RDF.TYPE, WordnetVocabulary.ENTRY, wordnet.graph);
            connection.add(entry, WordnetVocabulary.PART_OF_SPEECH, pos, wordnet.graph);
            if (rdf.notBlank(request.index)) {
                connection.add(entry, WordnetVocabulary.INDEX,
                        vf.createLiteral(request.index.trim()), wordnet.graph);
            }
            connection.add(entry, WordnetVocabulary.WORKFLOW_STATUS,
                    vf.createLiteral("working"), wordnet.graph);
            rdf.addAudit(connection, entry, author, timestamp, wordnet.graph);
            rdf.addNativeAndMetadata(connection, entry, request, wordnet.graph);
            formManager.createOwnedForm(connection, entry, "canonical",
                    request.canonicalForm, wordnet, author, timestamp);
            if (request.otherForms != null) {
                for (FormData form : request.otherForms) {
                    formManager.createOwnedForm(connection, entry, "other", form,
                            wordnet, author, timestamp);
                }
            }
            if (request.syntacticBehaviours != null) {
                for (SyntacticBehaviourData behaviour : request.syntacticBehaviours) {
                    createBehaviour(connection, entry, behaviour, wordnet,
                            author, timestamp);
                }
            }
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
            return response(connection, wordnet, entry, null);
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> get(String wordnetId, String id, String sections) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI entry = rdf.resource(connection, id, WordnetVocabulary.ENTRY,
                    wordnet.graph, "ENTRY_NOT_FOUND");
            return response(connection, wordnet, entry, sections);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> list(String wordnetId, String lemma,
            String partOfSpeech, String index, Boolean hasSenses,
            String workflowStatus, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw rdf.invalid("INVALID_PAGINATION", "Invalid limit or offset");
        }
        if (workflowStatus != null) {
            requireWorkflow(workflowStatus);
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI posFilter = partOfSpeech == null ? null
                    : validatePartOfSpeech(connection, partOfSpeech);
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, RDF.TYPE, WordnetVocabulary.ENTRY, false, wordnet.graph)) {
                while (statements.hasNext()) {
                    IRI entry = (IRI) statements.next().getSubject();
                    requireOwnedEntry(connection, wordnet, entry);
                    IRI pos = rdf.firstIri(connection, entry,
                            WordnetVocabulary.PART_OF_SPEECH, wordnet.graph);
                    String entryIndex = rdf.first(connection, entry,
                            WordnetVocabulary.INDEX, wordnet.graph);
                    String status = rdf.first(connection, entry,
                            WordnetVocabulary.WORKFLOW_STATUS, wordnet.graph);
                    IRI canonical = canonicalForm(connection, entry,
                            wordnet.graph);
                    org.eclipse.rdf4j.model.Literal writtenLiteral =
                            rdf.firstLiteral(connection, canonical,
                                    WordnetVocabulary.WRITTEN_REP, wordnet.graph);
                    String written = writtenLiteral == null ? null
                            : writtenLiteral.getLabel();
                    boolean senses = !rdf.iris(connection, entry,
                            WordnetVocabulary.SENSE_LINK, wordnet.graph).isEmpty();
                    if (posFilter != null && !posFilter.equals(pos)) continue;
                    if (index != null && !index.equals(entryIndex)) continue;
                    if (workflowStatus != null && !workflowStatus.equals(status)) continue;
                    if (hasSenses != null && hasSenses.booleanValue() != senses) continue;
                    if (lemma != null && (written == null
                            || !containsIgnoreCase(written, lemma,
                                    wordnet.language))) continue;
                    Map<String, Object> item = rdf.baseResponse(connection,
                            wordnet.wordnetId, "entry", entry, wordnet.graph);
                    item.put("lemma", written);
                    item.put("partOfSpeech", pos == null ? null : pos.stringValue());
                    item.put("index", entryIndex);
                    item.put("workflowStatus", status);
                    matches.add(item);
                }
            }
            matches.sort((left, right) -> ((String) left.get("iri"))
                    .compareTo((String) right.get("iri")));
            int from = Math.min(offset, matches.size());
            int to = Math.min(from + limit, matches.size());
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("items", new ArrayList<Map<String, Object>>(matches.subList(from, to)));
            result.put("total", Integer.valueOf(matches.size()));
            result.put("limit", Integer.valueOf(limit));
            result.put("offset", Integer.valueOf(offset));
            return result;
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> patch(String wordnetId, String id,
            EntryPatch request, String author) {
        if (request == null || request.hasWorkflowStatus()
                || request.hasWorkflowTransition() || request.hasLexoStatus()
                || request.hasRawStatus()) {
            throw rdf.invalid("INVALID_PATCH",
                    "Entry workflow changes use /entries/status");
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI entry = rdf.resource(connection, id, WordnetVocabulary.ENTRY,
                    wordnet.graph, "ENTRY_NOT_FOUND");
            requireOwnedEntry(connection, wordnet, entry);
            connection.begin();
            rdf.checkExpectedModified(connection, entry, request, wordnet.graph);
            IRI replacementPos = request.hasPartOfSpeech()
                    ? validatePartOfSpeech(connection, request.getPartOfSpeech()) : null;
            if (replacementPos != null) {
                validateSenseCompatibility(connection, entry, replacementPos, wordnet.graph);
            }
            boolean changed = rdf.patchNativeAndMetadata(connection, entry,
                    request, wordnet.graph);
            Set<IRI> formsToTouch = new java.util.LinkedHashSet<IRI>();
            boolean canonicalChanged = false;
            if (request.hasPartOfSpeech()) {
                connection.remove(entry, WordnetVocabulary.PART_OF_SPEECH, null,
                        wordnet.graph);
                connection.add(entry, WordnetVocabulary.PART_OF_SPEECH,
                        replacementPos, wordnet.graph);
                changed = true;
            }
            if (request.hasIndex()) {
                connection.remove(entry, WordnetVocabulary.INDEX, null, wordnet.graph);
                if (request.getIndex() != null) {
                    connection.add(entry, WordnetVocabulary.INDEX,
                            vf.createLiteral(rdf.required(request.getIndex(),
                                    "INVALID_PATCH", "index cannot be blank")), wordnet.graph);
                }
                changed = true;
            }
            if (request.hasCanonicalFormId()) {
                IRI promoted = rdf.resource(connection, request.getCanonicalFormId(),
                        WordnetVocabulary.FORM, wordnet.graph, "FORM_NOT_FOUND");
                if (!connection.hasStatement(entry, WordnetVocabulary.OTHER_FORM,
                        promoted, false, wordnet.graph)
                        && !connection.hasStatement(entry,
                                WordnetVocabulary.CANONICAL_FORM, promoted,
                                false, wordnet.graph)) {
                    throw new WordnetException(404, "CHILD_NOT_FOUND",
                            "Form is not owned by the Entry");
                }
                IRI current = canonicalForm(connection, entry, wordnet.graph);
                if (!current.equals(promoted)) {
                    connection.remove(entry, WordnetVocabulary.CANONICAL_FORM,
                            current, wordnet.graph);
                    connection.add(entry, WordnetVocabulary.OTHER_FORM, current,
                            wordnet.graph);
                    connection.remove(entry, WordnetVocabulary.OTHER_FORM,
                            promoted, wordnet.graph);
                    connection.add(entry, WordnetVocabulary.CANONICAL_FORM,
                            promoted, wordnet.graph);
                    formsToTouch.add(current);
                    formsToTouch.add(promoted);
                    canonicalChanged = true;
                    changed = true;
                }
            }
            changed |= mutateBehaviours(connection, entry,
                    request.syntacticBehaviours, wordnet, author);
            if (!changed) {
                throw rdf.invalid("INVALID_PATCH", "No effective change supplied");
            }
            String timestamp = rdf.timestamp();
            rdf.touch(connection, entry, timestamp, wordnet.graph);
            for (IRI form : formsToTouch) {
                rdf.touch(connection, form, timestamp, wordnet.graph);
            }
            if (canonicalChanged) {
                touchReferencedSynsets(connection, entry, timestamp,
                        wordnet.graph);
            }
            connection.commit();
            return response(connection, wordnet, entry, null);
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    public void delete(String wordnetId, String id, String expectedModified) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI entry = rdf.resource(connection, id, WordnetVocabulary.ENTRY,
                    wordnet.graph, "ENTRY_NOT_FOUND");
            requireOwnedEntry(connection, wordnet, entry);
            connection.begin();
            if (!rdf.iris(connection, entry, WordnetVocabulary.SENSE_LINK,
                    wordnet.graph).isEmpty()) {
                throw new WordnetException(409, "ENTRY_HAS_SENSES",
                        "Delete Entry senses first");
            }
            if (expectedModified != null && !expectedModified.equals(rdf.first(
                    connection, entry,
                    org.eclipse.rdf4j.model.vocabulary.DCTERMS.MODIFIED,
                    wordnet.graph))) {
                throw new WordnetException(409, "MODIFIED_MISMATCH",
                        "Entry was modified after the supplied precondition");
            }
            List<IRI> forms = new ArrayList<IRI>();
            forms.add(canonicalForm(connection, entry, wordnet.graph));
            forms.addAll(rdf.iris(connection, entry,
                    WordnetVocabulary.OTHER_FORM, wordnet.graph));
            for (IRI form : forms) {
                formManager.deleteOwnedForm(connection, entry, form, wordnet.graph);
            }
            for (IRI behaviour : rdf.iris(connection, entry,
                    WordnetVocabulary.SYN_BEHAVIOR, wordnet.graph)) {
                if (connection.hasStatement(behaviour,
                        WordnetVocabulary.SENSE_SUBCAT, null, false, wordnet.graph)) {
                    throw new WordnetException(409, "INVALID_REFERENCED_ENTITY",
                            "Syntactic behaviour is still used by a Sense");
                }
                rdf.requireExclusiveIncoming(connection, behaviour, entry,
                        wordnet.graph, WordnetVocabulary.SYN_BEHAVIOR);
                connection.remove(behaviour, null, null, wordnet.graph);
            }
            connection.remove(wordnet.root, WordnetVocabulary.ENTRY_LINK, entry,
                    wordnet.graph);
            connection.remove(entry, null, null, wordnet.graph);
            rdf.touch(connection, wordnet.root, rdf.timestamp(), wordnet.graph);
            connection.commit();
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    Map<String, Object> response(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI entry,
            String sections) {
        Set<String> selected = WordnetSectionParser.parse(sections,
                "forms", "senses", "syntacticBehaviours");
        requireOwnedEntry(connection, wordnet, entry);
        boolean core = selected.contains("core");
        Map<String, Object> result = rdf.baseResponse(connection,
                wordnet.wordnetId, "entry", entry, wordnet.graph, core);
        IRI canonical = canonicalForm(connection, entry, wordnet.graph);
        if (core) {
            IRI pos = rdf.firstIri(connection, entry,
                    WordnetVocabulary.PART_OF_SPEECH, wordnet.graph);
            result.put("partOfSpeech", pos == null ? null : pos.stringValue());
            result.put("index", rdf.first(connection, entry,
                    WordnetVocabulary.INDEX, wordnet.graph));
            result.put("workflowStatus", rdf.first(connection, entry,
                    WordnetVocabulary.WORKFLOW_STATUS, wordnet.graph));
            result.put("canonicalForm", formSummary(connection, canonical,
                    wordnet.graph));
        }
        if (selected.contains("metadata")) {
            result.put("metadata", rdf.readMetadata(connection, entry, wordnet.graph));
        }
        if (selected.contains("forms")) {
            List<Map<String, Object>> forms = new ArrayList<Map<String, Object>>();
            if (canonical != null) forms.add(formManager.response(connection,
                    wordnet, canonical, "all"));
            for (IRI form : rdf.iris(connection, entry,
                    WordnetVocabulary.OTHER_FORM, wordnet.graph)) {
                forms.add(formManager.response(connection, wordnet, form, "all"));
            }
            result.put("forms", forms);
        }
        if (selected.contains("senses")) {
            List<Map<String, Object>> senses = new ArrayList<Map<String, Object>>();
            for (IRI sense : rdf.iris(connection, entry,
                    WordnetVocabulary.SENSE_LINK, wordnet.graph)) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", WordnetIdCodec.encode(sense.stringValue()));
                item.put("iri", sense.stringValue());
                IRI synset = rdf.firstIri(connection, sense,
                        WordnetVocabulary.REFERENCE, wordnet.graph);
                item.put("synsetId", synset == null ? null
                        : WordnetIdCodec.encode(synset.stringValue()));
                String number = rdf.first(connection, sense,
                        WordnetVocabulary.N, wordnet.graph);
                item.put("n", number == null ? null : Integer.valueOf(number));
                senses.add(item);
            }
            result.put("senses", senses);
        }
        if (selected.contains("syntacticBehaviours")) {
            List<Map<String, Object>> behaviours = new ArrayList<Map<String, Object>>();
            for (IRI behaviour : rdf.iris(connection, entry,
                    WordnetVocabulary.SYN_BEHAVIOR, wordnet.graph)) {
                Map<String, Object> item = rdf.baseResponse(connection,
                        wordnet.wordnetId, "syntacticBehaviour", behaviour,
                        wordnet.graph);
                item.put("label", rdf.first(connection, behaviour, RDFS.LABEL,
                        wordnet.graph));
                item.put("metadata", rdf.readMetadata(connection, behaviour,
                        wordnet.graph));
                behaviours.add(item);
            }
            result.put("syntacticBehaviours", behaviours);
        }
        return result;
    }

    private IRI validatePartOfSpeech(RepositoryConnection connection, String value) {
        IRI pos = rdf.expandedIri(value, "INVALID_PART_OF_SPEECH",
                "partOfSpeech");
        if (!connection.hasStatement(pos, RDF.TYPE, WordnetVocabulary.POS_TYPE,
                false, resolver.schemaGraph())) {
            throw new WordnetException(422, "INVALID_PART_OF_SPEECH",
                    "partOfSpeech is not declared by the WordNet schema");
        }
        return pos;
    }

    private void validateSenseCompatibility(RepositoryConnection connection,
            IRI entry, IRI entryPos, Resource graph) {
        for (IRI sense : rdf.iris(connection, entry,
                WordnetVocabulary.SENSE_LINK, graph)) {
            IRI synset = rdf.firstIri(connection, sense,
                    WordnetVocabulary.REFERENCE, graph);
            IRI synsetPos = synset == null ? null : rdf.firstIri(connection,
                    synset, WordnetVocabulary.PART_OF_SPEECH, graph);
            if (!compatible(entryPos, synsetPos)) {
                throw new WordnetException(422, "INCOMPATIBLE_POS",
                        "Entry part of speech is incompatible with an existing Sense");
            }
        }
    }

    static boolean compatible(IRI entryPos, IRI synsetPos) {
        return entryPos != null && (entryPos.equals(synsetPos)
                || (entryPos.stringValue().equals(WordnetVocabulary.WN_NS + "adjective")
                && synsetPos != null && synsetPos.stringValue().equals(
                        WordnetVocabulary.WN_NS + "adjective_satellite")));
    }

    private IRI createBehaviour(RepositoryConnection connection, IRI entry,
            SyntacticBehaviourData request,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author,
            String timestamp) {
        if (request == null) throw rdf.invalid("INVALID_PATCH",
                "syntactic behaviour is required");
        IRI behaviour = rdf.mint(connection);
        connection.add(entry, WordnetVocabulary.SYN_BEHAVIOR, behaviour, wordnet.graph);
        connection.add(behaviour, RDF.TYPE,
                WordnetVocabulary.SYNTACTIC_BEHAVIOUR, wordnet.graph);
        if (rdf.notBlank(request.label)) connection.add(behaviour, RDFS.LABEL,
                vf.createLiteral(request.label.trim()), wordnet.graph);
        rdf.addAudit(connection, behaviour, author, timestamp, wordnet.graph);
        rdf.addNativeAndMetadata(connection, behaviour, request, wordnet.graph);
        return behaviour;
    }

    private boolean mutateBehaviours(RepositoryConnection connection, IRI entry,
            ChildChanges<SyntacticBehaviourData, SyntacticBehaviourUpdate> changes,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author) {
        if (changes == null) return false;
        if (!changes.hasOperations()) return false;
        java.util.Set<String> ids = new java.util.HashSet<String>();
        if (changes.update != null) for (SyntacticBehaviourUpdate update : changes.update) {
            if (update == null || update.id == null || !ids.add(update.id))
                throw rdf.invalid("INVALID_PATCH", "Duplicate behaviour id");
        }
        if (changes.remove != null) for (String id : changes.remove)
            if (!ids.add(id)) throw rdf.invalid("INVALID_PATCH", "Behaviour id appears twice");
        String timestamp = rdf.timestamp();
        if (changes.add != null) for (SyntacticBehaviourData item : changes.add)
            createBehaviour(connection, entry, item, wordnet, author, timestamp);
        if (changes.update != null) for (SyntacticBehaviourUpdate update : changes.update) {
            IRI behaviour = ownedBehaviour(connection, entry, update.id, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, behaviour,
                    update, wordnet.graph);
            if (update.hasLabel()) {
                connection.remove(behaviour, RDFS.LABEL, null, wordnet.graph);
                if (update.getLabel() != null) connection.add(behaviour, RDFS.LABEL,
                        vf.createLiteral(rdf.required(update.getLabel(),
                                "INVALID_PATCH", "label cannot be blank")),
                        wordnet.graph);
                changed = true;
            }
            if (!changed) throw rdf.invalid("INVALID_PATCH",
                    "Behaviour update contains no effective change");
            rdf.touch(connection, behaviour, timestamp, wordnet.graph);
        }
        if (changes.remove != null) for (String id : changes.remove) {
            IRI behaviour = ownedBehaviour(connection, entry, id, wordnet.graph);
            if (connection.hasStatement(behaviour, WordnetVocabulary.SENSE_SUBCAT,
                    null, false, wordnet.graph)) {
                throw new WordnetException(409, "INVALID_REFERENCED_ENTITY",
                        "Behaviour is used by a Sense");
            }
            rdf.requireExclusiveIncoming(connection, behaviour, entry,
                    wordnet.graph, WordnetVocabulary.SYN_BEHAVIOR);
            connection.remove(entry, WordnetVocabulary.SYN_BEHAVIOR, behaviour,
                    wordnet.graph);
            connection.remove(behaviour, null, null, wordnet.graph);
        }
        return true;
    }

    private IRI ownedBehaviour(RepositoryConnection connection, IRI entry,
            String id, Resource graph) {
        IRI behaviour = rdf.resource(connection, id,
                WordnetVocabulary.SYNTACTIC_BEHAVIOUR, graph, "CHILD_NOT_FOUND");
        if (!connection.hasStatement(entry, WordnetVocabulary.SYN_BEHAVIOR,
                behaviour, false, graph)) {
            throw new WordnetException(404, "CHILD_NOT_FOUND",
                    "Behaviour is not owned by this Entry");
        }
        return behaviour;
    }

    private Map<String, Object> formSummary(RepositoryConnection connection,
            IRI form, Resource graph) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", WordnetIdCodec.encode(form.stringValue()));
        result.put("iri", form.stringValue());
        result.put("writtenRep", rdf.first(connection, form,
                WordnetVocabulary.WRITTEN_REP, graph));
        return result;
    }

    private IRI canonicalForm(RepositoryConnection connection, IRI entry,
            Resource graph) {
        List<IRI> values = rdf.iris(connection, entry,
                WordnetVocabulary.CANONICAL_FORM, graph);
        if (values.size() != 1) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Entry must have exactly one canonical Form");
        }
        return values.get(0);
    }

    static void requireOwnedEntry(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI entry) {
        int owners = 0;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, WordnetVocabulary.ENTRY_LINK, entry, false,
                wordnet.graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                if (!wordnet.root.equals(statement.getSubject())) {
                    throw new WordnetException(409,
                            "GRAPH_INTEGRITY_CONFLICT",
                            "Entry is linked from a foreign Lexicon root");
                }
                owners++;
            }
        }
        if (owners != 1) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Entry must be linked exactly once from its WordNet root");
        }
    }

    private void touchReferencedSynsets(RepositoryConnection connection,
            IRI entry, String timestamp, Resource graph) {
        Set<IRI> synsets = new java.util.LinkedHashSet<IRI>();
        for (IRI sense : rdf.iris(connection, entry,
                WordnetVocabulary.SENSE_LINK, graph)) {
            IRI synset = rdf.firstIri(connection, sense,
                    WordnetVocabulary.REFERENCE, graph);
            if (synset != null) {
                synsets.add(synset);
            }
        }
        for (IRI synset : synsets) {
            rdf.touch(connection, synset, timestamp, graph);
        }
    }

    private boolean containsIgnoreCase(String value, String filter,
            String language) {
        java.util.Locale locale = java.util.Locale.forLanguageTag(language);
        return value.toLowerCase(locale).contains(filter.toLowerCase(locale));
    }

    private void requireWorkflow(String status) {
        if (!"working".equals(status) && !"completed".equals(status)
                && !"revised".equals(status)) {
            throw rdf.invalid("INVALID_WORKFLOW_STATUS", "Unsupported workflowStatus");
        }
    }
}
