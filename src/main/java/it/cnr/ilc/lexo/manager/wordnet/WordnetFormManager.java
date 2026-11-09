package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ChildChanges;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.FormPatch;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.PronunciationData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.PronunciationUpdate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.TagData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.TagUpdate;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Form CRUD, including named pronunciation and tag child resources. */
public final class WordnetFormManager extends AbstractWordnetManager {

    private final ValueFactory vf = SimpleValueFactory.getInstance();

    public WordnetFormManager() {
        this(null);
    }

    public WordnetFormManager(Repository repository) {
        super(repository);
    }

    public Map<String, Object> create(String wordnetId, FormCreate request,
            String author) {
        if (request == null) {
            throw rdf.invalid("MISSING_REQUIRED_FIELD", "request is required");
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI entry = rdf.resource(connection, request.entryId,
                    WordnetVocabulary.ENTRY, wordnet.graph, "ENTRY_NOT_FOUND");
            WordnetEntryManager.requireOwnedEntry(connection, wordnet, entry);
            String kind = requiredKind(request.kind);
            connection.begin();
            if ("canonical".equals(kind) && rdf.firstIri(connection, entry,
                    WordnetVocabulary.CANONICAL_FORM, wordnet.graph) != null) {
                throw new WordnetException(409, "CANONICAL_FORM_REQUIRED",
                        "Entry already has a canonical form");
            }
            String timestamp = rdf.timestamp();
            IRI form = createOwnedForm(connection, entry, kind, request,
                    wordnet, author, timestamp);
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
            return response(connection, wordnet, form, null);
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    IRI createOwnedForm(RepositoryConnection connection, IRI entry, String kind,
            FormData request, WordnetGraphResolver.ResolvedWordnet wordnet,
            String author, String timestamp) {
        if (request == null) {
            throw rdf.invalid("MISSING_REQUIRED_FIELD", "form is required");
        }
        String writtenRep = rdf.required(request.writtenRep,
                "MISSING_REQUIRED_FIELD", "writtenRep is required");
        validateScript(request.script);
        IRI form = rdf.mint(connection);
        IRI link = "canonical".equals(kind) ? WordnetVocabulary.CANONICAL_FORM
                : WordnetVocabulary.OTHER_FORM;
        connection.add(entry, link, form, wordnet.graph);
        connection.add(form, RDF.TYPE, WordnetVocabulary.FORM, wordnet.graph);
        connection.add(form, WordnetVocabulary.WRITTEN_REP,
                vf.createLiteral(writtenRep, wordnet.language), wordnet.graph);
        if (rdf.notBlank(request.script)) {
            connection.add(form, WordnetVocabulary.SCRIPT,
                    vf.createLiteral(request.script.trim()), wordnet.graph);
        }
        rdf.addAudit(connection, form, author, timestamp, wordnet.graph);
        rdf.addNativeAndMetadata(connection, form, request, wordnet.graph);
        if (request.tags != null) {
            for (TagData tag : request.tags) {
                createTag(connection, form, tag, wordnet.graph, author, timestamp);
            }
        }
        if (request.pronunciations != null) {
            for (PronunciationData pronunciation : request.pronunciations) {
                createPronunciation(connection, form, pronunciation,
                        wordnet.graph, author, timestamp);
            }
        }
        rdf.touch(connection, entry, timestamp, wordnet.graph);
        return form;
    }

    public Map<String, Object> get(String wordnetId, String id, String sections) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI form = rdf.resource(connection, id, WordnetVocabulary.FORM,
                    wordnet.graph, "FORM_NOT_FOUND");
            return response(connection, wordnet, form, sections);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> list(String wordnetId, String entryId,
            String writtenRep, String kind, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw rdf.invalid("INVALID_PAGINATION", "Invalid limit or offset");
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI entry = entryId == null ? null : rdf.resource(connection, entryId,
                    WordnetVocabulary.ENTRY, wordnet.graph, "ENTRY_NOT_FOUND");
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, RDF.TYPE, WordnetVocabulary.FORM, false, wordnet.graph)) {
                while (statements.hasNext()) {
                    IRI form = (IRI) statements.next().getSubject();
                    Owner owner = owner(connection, form, wordnet.graph);
                    WordnetEntryManager.requireOwnedEntry(connection, wordnet,
                            owner.entry);
                    Literal repLiteral = rdf.firstLiteral(connection, form,
                            WordnetVocabulary.WRITTEN_REP, wordnet.graph);
                    String rep = repLiteral == null ? null : repLiteral.getLabel();
                    if (entry != null && !entry.equals(owner.entry)) {
                        continue;
                    }
                    if (writtenRep != null && (rep == null
                            || !containsIgnoreCase(rep, writtenRep,
                                    wordnet.language))) {
                        continue;
                    }
                    if (kind != null && !requiredKind(kind).equals(owner.kind)) {
                        continue;
                    }
                    Map<String, Object> item = rdf.baseResponse(connection,
                            wordnet.wordnetId, "form", form, wordnet.graph);
                    item.put("entryId", WordnetIdCodec.encode(owner.entry.stringValue()));
                    item.put("kind", owner.kind);
                    item.put("writtenRep", rep);
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
            FormPatch request, String author) {
        if (request == null || request.hasEntryId() || request.hasKind()) {
            throw rdf.invalid("INVALID_PATCH", "Form owner and kind are immutable");
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI form = rdf.resource(connection, id, WordnetVocabulary.FORM,
                    wordnet.graph, "FORM_NOT_FOUND");
            Owner owner = owner(connection, form, wordnet.graph);
            WordnetEntryManager.requireOwnedEntry(connection, wordnet,
                    owner.entry);
            connection.begin();
            rdf.checkExpectedModified(connection, form, request, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, form,
                    request, wordnet.graph);
            if (request.hasWrittenRep()) {
                String value = rdf.required(request.getWrittenRep(),
                        "INVALID_PATCH", "writtenRep cannot be null or blank");
                connection.remove(form, WordnetVocabulary.WRITTEN_REP, null,
                        wordnet.graph);
                connection.add(form, WordnetVocabulary.WRITTEN_REP,
                        vf.createLiteral(value, wordnet.language), wordnet.graph);
                changed = true;
            }
            if (request.hasScript()) {
                validateScript(request.getScript());
                connection.remove(form, WordnetVocabulary.SCRIPT, null, wordnet.graph);
                if (request.getScript() != null) {
                    connection.add(form, WordnetVocabulary.SCRIPT,
                            vf.createLiteral(request.getScript().trim()), wordnet.graph);
                }
                changed = true;
            }
            changed |= mutateTags(connection, form, request.tags, wordnet,
                    author);
            changed |= mutatePronunciations(connection, form,
                    request.pronunciations, wordnet, author);
            if (!changed) {
                throw rdf.invalid("INVALID_PATCH", "No effective change supplied");
            }
            String timestamp = rdf.timestamp();
            rdf.touch(connection, form, timestamp, wordnet.graph);
            rdf.touch(connection, owner.entry, timestamp, wordnet.graph);
            if (request.hasWrittenRep() && "canonical".equals(owner.kind)) {
                touchReferencedSynsets(connection, owner.entry, timestamp,
                        wordnet.graph);
            }
            connection.commit();
            return response(connection, wordnet, form, null);
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
            IRI form = rdf.resource(connection, id, WordnetVocabulary.FORM,
                    wordnet.graph, "FORM_NOT_FOUND");
            Owner owner = owner(connection, form, wordnet.graph);
            WordnetEntryManager.requireOwnedEntry(connection, wordnet,
                    owner.entry);
            connection.begin();
            if ("canonical".equals(owner.kind)) {
                throw new WordnetException(409, "CANONICAL_FORM_REQUIRED",
                        "Promote another form before deleting the canonical form");
            }
            if (expectedModified != null && !expectedModified.equals(rdf.first(
                    connection, form,
                    org.eclipse.rdf4j.model.vocabulary.DCTERMS.MODIFIED,
                    wordnet.graph))) {
                throw new WordnetException(409, "MODIFIED_MISMATCH",
                        "Form was modified after the supplied precondition");
            }
            deleteOwnedForm(connection, owner.entry, form, wordnet.graph);
            String timestamp = rdf.timestamp();
            rdf.touch(connection, owner.entry, timestamp, wordnet.graph);
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    void deleteOwnedForm(RepositoryConnection connection, IRI entry, IRI form,
            Resource graph) {
        rdf.requireExclusiveIncoming(connection, form, entry, graph,
                WordnetVocabulary.CANONICAL_FORM, WordnetVocabulary.OTHER_FORM);
        for (IRI child : rdf.iris(connection, form, WordnetVocabulary.TAG_LINK, graph)) {
            rdf.requireExclusiveIncoming(connection, child, form, graph,
                    WordnetVocabulary.TAG_LINK);
            connection.remove(child, null, null, graph);
        }
        for (IRI child : rdf.iris(connection, form,
                WordnetVocabulary.PRONUNCIATION_LINK, graph)) {
            rdf.requireExclusiveIncoming(connection, child, form, graph,
                    WordnetVocabulary.PRONUNCIATION_LINK);
            connection.remove(child, null, null, graph);
        }
        connection.remove(entry, WordnetVocabulary.CANONICAL_FORM, form, graph);
        connection.remove(entry, WordnetVocabulary.OTHER_FORM, form, graph);
        connection.remove(form, null, null, graph);
    }

    Map<String, Object> response(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI form,
            String sections) {
        Set<String> selected = WordnetSectionParser.parse(sections,
                "pronunciations", "tags");
        boolean core = selected.contains("core");
        Owner owner = owner(connection, form, wordnet.graph);
        WordnetEntryManager.requireOwnedEntry(connection, wordnet, owner.entry);
        Map<String, Object> result = rdf.baseResponse(connection,
                wordnet.wordnetId, "form", form, wordnet.graph, core);
        if (core) {
            result.put("entryId", WordnetIdCodec.encode(owner.entry.stringValue()));
            result.put("kind", owner.kind);
            result.put("writtenRep", rdf.first(connection, form,
                    WordnetVocabulary.WRITTEN_REP, wordnet.graph));
            result.put("script", rdf.first(connection, form,
                    WordnetVocabulary.SCRIPT, wordnet.graph));
        }
        if (selected.contains("metadata")) {
            result.put("metadata", rdf.readMetadata(connection, form, wordnet.graph));
        }
        if (selected.contains("tags")) {
            result.put("tags", children(connection, wordnet, form,
                    WordnetVocabulary.TAG_LINK, "tag"));
        }
        if (selected.contains("pronunciations")) {
            result.put("pronunciations", children(connection, wordnet, form,
                    WordnetVocabulary.PRONUNCIATION_LINK, "pronunciation"));
        }
        return result;
    }

    private IRI createTag(RepositoryConnection connection, IRI form, TagData request,
            Resource graph, String author, String timestamp) {
        if (request == null) {
            throw rdf.invalid("MISSING_REQUIRED_FIELD", "tag is required");
        }
        String category = rdf.required(request.category, "MISSING_REQUIRED_FIELD",
                "tag category is required");
        String value = rdf.required(request.value, "MISSING_REQUIRED_FIELD",
                "tag value is required");
        IRI tag = rdf.mint(connection);
        connection.add(form, WordnetVocabulary.TAG_LINK, tag, graph);
        connection.add(tag, RDF.TYPE, WordnetVocabulary.TAG, graph);
        connection.add(tag, WordnetVocabulary.CATEGORY, vf.createLiteral(category), graph);
        connection.add(tag, RDF.VALUE, vf.createLiteral(value), graph);
        rdf.addAudit(connection, tag, author, timestamp, graph);
        rdf.addNativeAndMetadata(connection, tag, request, graph);
        return tag;
    }

    private IRI createPronunciation(RepositoryConnection connection, IRI form,
            PronunciationData request, Resource graph, String author,
            String timestamp) {
        if (request == null) {
            throw rdf.invalid("MISSING_REQUIRED_FIELD", "pronunciation is required");
        }
        String transcription = rdf.required(request.transcription,
                "MISSING_REQUIRED_FIELD", "transcription is required");
        validateAudio(request.audio);
        IRI pronunciation = rdf.mint(connection);
        connection.add(form, WordnetVocabulary.PRONUNCIATION_LINK, pronunciation, graph);
        connection.add(pronunciation, RDF.TYPE, WordnetVocabulary.PRONUNCIATION, graph);
        connection.add(pronunciation, RDF.VALUE, vf.createLiteral(transcription), graph);
        addOptionalLiteral(connection, pronunciation, WordnetVocabulary.NOTATION,
                request.notation, graph);
        addOptionalLiteral(connection, pronunciation, WordnetVocabulary.VARIETY,
                request.variety, graph);
        if (request.phonemic != null) {
            connection.add(pronunciation, WordnetVocabulary.PHONEMIC,
                    vf.createLiteral(request.phonemic.booleanValue()), graph);
        }
        if (request.audio != null) {
            connection.add(pronunciation, WordnetVocabulary.AUDIO,
                    vf.createLiteral(request.audio, XSD.ANYURI), graph);
        }
        rdf.addAudit(connection, pronunciation, author, timestamp, graph);
        rdf.addNativeAndMetadata(connection, pronunciation, request, graph);
        return pronunciation;
    }

    private boolean mutateTags(RepositoryConnection connection, IRI form,
            ChildChanges<TagData, TagUpdate> changes,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author) {
        if (changes == null) return false;
        if (!changes.hasOperations()) return false;
        validateDisjoint(changes);
        String timestamp = rdf.timestamp();
        if (changes.add != null) for (TagData item : changes.add) {
            createTag(connection, form, item, wordnet.graph, author, timestamp);
        }
        if (changes.update != null) for (TagUpdate update : changes.update) {
            IRI child = ownedChild(connection, form, update.id,
                    WordnetVocabulary.TAG_LINK, WordnetVocabulary.TAG, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, child,
                    update, wordnet.graph);
            if (update.hasCategory()) {
                connection.remove(child, WordnetVocabulary.CATEGORY, null, wordnet.graph);
                connection.add(child, WordnetVocabulary.CATEGORY, vf.createLiteral(
                        rdf.required(update.getCategory(), "INVALID_PATCH",
                                "tag category is required")), wordnet.graph);
                changed = true;
            }
            if (update.hasValue()) {
                connection.remove(child, RDF.VALUE, null, wordnet.graph);
                connection.add(child, RDF.VALUE, vf.createLiteral(
                        rdf.required(update.getValue(), "INVALID_PATCH",
                                "tag value is required")), wordnet.graph);
                changed = true;
            }
            if (!changed) throw rdf.invalid("INVALID_PATCH",
                    "tag update contains no effective change");
            rdf.touch(connection, child, timestamp, wordnet.graph);
        }
        removeChildren(connection, form, changes.remove, WordnetVocabulary.TAG_LINK,
                WordnetVocabulary.TAG, wordnet.graph);
        return true;
    }

    private boolean mutatePronunciations(RepositoryConnection connection, IRI form,
            ChildChanges<PronunciationData, PronunciationUpdate> changes,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author) {
        if (changes == null) return false;
        if (!changes.hasOperations()) return false;
        validateDisjoint(changes);
        String timestamp = rdf.timestamp();
        if (changes.add != null) for (PronunciationData item : changes.add) {
            createPronunciation(connection, form, item, wordnet.graph, author, timestamp);
        }
        if (changes.update != null) for (PronunciationUpdate update : changes.update) {
            IRI child = ownedChild(connection, form, update.id,
                    WordnetVocabulary.PRONUNCIATION_LINK,
                    WordnetVocabulary.PRONUNCIATION, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, child,
                    update, wordnet.graph);
            if (update.hasTranscription()) {
                connection.remove(child, RDF.VALUE, null, wordnet.graph);
                connection.add(child, RDF.VALUE, vf.createLiteral(rdf.required(
                        update.getTranscription(), "INVALID_PATCH",
                        "transcription is required")), wordnet.graph);
                changed = true;
            }
            if (update.hasNotation()) {
                replaceOptionalLiteral(connection, child,
                        WordnetVocabulary.NOTATION, update.getNotation(), wordnet.graph);
                changed = true;
            }
            if (update.hasVariety()) {
                replaceOptionalLiteral(connection, child,
                        WordnetVocabulary.VARIETY, update.getVariety(), wordnet.graph);
                changed = true;
            }
            if (update.hasPhonemic()) {
                connection.remove(child, WordnetVocabulary.PHONEMIC, null, wordnet.graph);
                if (update.getPhonemic() != null) connection.add(child,
                        WordnetVocabulary.PHONEMIC,
                        vf.createLiteral(update.getPhonemic().booleanValue()), wordnet.graph);
                changed = true;
            }
            if (update.hasAudio()) {
                validateAudio(update.getAudio());
                connection.remove(child, WordnetVocabulary.AUDIO, null, wordnet.graph);
                if (update.getAudio() != null) connection.add(child,
                        WordnetVocabulary.AUDIO,
                        vf.createLiteral(update.getAudio(), XSD.ANYURI), wordnet.graph);
                changed = true;
            }
            if (!changed) throw rdf.invalid("INVALID_PATCH",
                    "pronunciation update contains no effective change");
            rdf.touch(connection, child, timestamp, wordnet.graph);
        }
        removeChildren(connection, form, changes.remove,
                WordnetVocabulary.PRONUNCIATION_LINK,
                WordnetVocabulary.PRONUNCIATION, wordnet.graph);
        return true;
    }

    private List<Map<String, Object>> children(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI form, IRI predicate,
            String type) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (IRI child : rdf.iris(connection, form, predicate, wordnet.graph)) {
            Map<String, Object> item = rdf.baseResponse(connection,
                    wordnet.wordnetId, type, child, wordnet.graph);
            item.put("metadata", rdf.readMetadata(connection, child, wordnet.graph));
            if ("tag".equals(type)) {
                item.put("category", rdf.first(connection, child,
                        WordnetVocabulary.CATEGORY, wordnet.graph));
                item.put("value", rdf.first(connection, child, RDF.VALUE, wordnet.graph));
            } else {
                item.put("transcription", rdf.first(connection, child, RDF.VALUE, wordnet.graph));
                item.put("notation", rdf.first(connection, child, WordnetVocabulary.NOTATION, wordnet.graph));
                item.put("variety", rdf.first(connection, child, WordnetVocabulary.VARIETY, wordnet.graph));
                org.eclipse.rdf4j.model.Literal phonemic = rdf.firstLiteral(
                        connection, child, WordnetVocabulary.PHONEMIC, wordnet.graph);
                item.put("phonemic", phonemic == null ? null
                        : Boolean.valueOf(phonemic.booleanValue()));
                item.put("audio", rdf.first(connection, child, WordnetVocabulary.AUDIO, wordnet.graph));
            }
            result.add(item);
        }
        return result;
    }

    private Owner owner(RepositoryConnection connection, IRI form, Resource graph) {
        IRI entry = null;
        String kind = null;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, WordnetVocabulary.CANONICAL_FORM, form, false, graph)) {
            if (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                entry = subject instanceof IRI ? (IRI) subject : null;
                kind = "canonical";
                if (statements.hasNext()) entry = null;
            }
        }
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, WordnetVocabulary.OTHER_FORM, form, false, graph)) {
            if (statements.hasNext()) {
                if (entry != null || kind != null) entry = null;
                else {
                    Resource subject = statements.next().getSubject();
                    entry = subject instanceof IRI ? (IRI) subject : null;
                    kind = "other";
                    if (statements.hasNext()) entry = null;
                }
            }
        }
        if (entry == null || kind == null
                || !connection.hasStatement(entry, RDF.TYPE,
                        WordnetVocabulary.ENTRY, false, graph)) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Form must have exactly one owner and one kind");
        }
        return new Owner(entry, kind);
    }

    private void touchReferencedSynsets(RepositoryConnection connection,
            IRI entry, String timestamp, Resource graph) {
        java.util.Set<IRI> synsets = new java.util.LinkedHashSet<IRI>();
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

    private String requiredKind(String kind) {
        String value = rdf.required(kind, "MISSING_REQUIRED_FIELD", "kind is required");
        if (!"canonical".equals(value) && !"other".equals(value)) {
            throw rdf.invalid("INVALID_PATCH", "kind must be canonical or other");
        }
        return value;
    }

    private void validateScript(String script) {
        if (script != null && !script.matches("[A-Z][a-z]{3}")) {
            throw rdf.invalid("INVALID_PATCH", "script must be an ISO 15924 code");
        }
    }

    private void validateAudio(String audio) {
        if (audio == null) return;
        try {
            URI uri = new URI(audio);
            if (!uri.isAbsolute() || uri.getHost() == null
                    || uri.getHost().trim().isEmpty()
                    || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new URISyntaxException(audio, "unsupported scheme");
            }
        } catch (URISyntaxException ex) {
            throw rdf.invalid("INVALID_PATCH", "audio must be an HTTP(S) URL");
        }
    }

    private boolean containsIgnoreCase(String value, String filter,
            String language) {
        java.util.Locale locale = java.util.Locale.forLanguageTag(language);
        return value.toLowerCase(locale).contains(filter.toLowerCase(locale));
    }

    private void addOptionalLiteral(RepositoryConnection connection, IRI resource,
            IRI predicate, String value, Resource graph) {
        if (rdf.notBlank(value)) connection.add(resource, predicate,
                vf.createLiteral(value.trim()), graph);
    }

    private void replaceOptionalLiteral(RepositoryConnection connection, IRI resource,
            IRI predicate, String value, Resource graph) {
        connection.remove(resource, predicate, null, graph);
        addOptionalLiteral(connection, resource, predicate, value, graph);
    }

    private <C, U extends it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ChildUpdate>
            void validateDisjoint(ChildChanges<C, U> changes) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        if (changes.update != null) for (U update : changes.update) {
            if (update == null || update.id == null || !ids.add(update.id)) {
                throw rdf.invalid("INVALID_PATCH", "Duplicate child id in update");
            }
        }
        if (changes.remove != null) for (String id : changes.remove) {
            if (!ids.add(id)) throw rdf.invalid("INVALID_PATCH",
                    "Child id appears in multiple operations");
        }
    }

    private IRI ownedChild(RepositoryConnection connection, IRI owner, String id,
            IRI link, IRI type, Resource graph) {
        IRI child = rdf.resource(connection, id, type, graph, "CHILD_NOT_FOUND");
        if (!connection.hasStatement(owner, link, child, false, graph)) {
            throw new WordnetException(404, "CHILD_NOT_FOUND",
                    "Child is not owned by this form");
        }
        return child;
    }

    private void removeChildren(RepositoryConnection connection, IRI owner,
            List<String> ids, IRI link, IRI type, Resource graph) {
        if (ids == null) return;
        for (String id : ids) {
            IRI child = ownedChild(connection, owner, id, link, type, graph);
            rdf.requireExclusiveIncoming(connection, child, owner, graph, link);
            connection.remove(owner, link, child, graph);
            connection.remove(child, null, null, graph);
        }
    }

    private static final class Owner {
        final IRI entry;
        final String kind;
        Owner(IRI entry, String kind) { this.entry = entry; this.kind = kind; }
    }
}
