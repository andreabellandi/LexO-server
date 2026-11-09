package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ChildChanges;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.DefinitionData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.DefinitionUpdate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ExampleData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ExampleUpdate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SynsetCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SynsetPatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.rdf4j.model.vocabulary.SKOS;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Synset CRUD with named definitions/examples and ordered Sense membership. */
public final class WordnetSynsetManager extends AbstractWordnetManager {

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final WordnetMemberList memberList = new WordnetMemberList();

    public WordnetSynsetManager() {
        this(null);
    }

    public WordnetSynsetManager(Repository repository) {
        super(repository);
    }

    public Map<String, Object> create(String wordnetId, SynsetCreate request,
            String author) {
        if (request == null) {
            throw rdf.invalid("MISSING_REQUIRED_FIELD", "request is required");
        }
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI pos = validatePartOfSpeech(connection, request.partOfSpeech);
            IRI ili = request.ili == null ? null : rdf.absoluteIri(request.ili,
                    "INVALID_ILI", "ili");
            connection.begin();
            IRI synset = rdf.mint(connection);
            String timestamp = rdf.timestamp();
            connection.add(synset, RDF.TYPE, WordnetVocabulary.SYNSET,
                    wordnet.graph);
            connection.add(synset, SKOS.IN_SCHEME, wordnet.root, wordnet.graph);
            connection.add(synset, WordnetVocabulary.PART_OF_SPEECH, pos,
                    wordnet.graph);
            connection.add(synset, WordnetVocabulary.MEMBER_LIST, RDF.NIL,
                    wordnet.graph);
            if (ili != null) connection.add(synset, WordnetVocabulary.ILI, ili,
                    wordnet.graph);
            if (rdf.notBlank(request.lexfile)) connection.add(synset,
                    WordnetVocabulary.LEXFILE,
                    vf.createLiteral(request.lexfile.trim()), wordnet.graph);
            rdf.addAudit(connection, synset, author, timestamp, wordnet.graph);
            rdf.addNativeAndMetadata(connection, synset, request, wordnet.graph);
            if (request.definitions != null) for (DefinitionData item : request.definitions)
                createDefinition(connection, synset, item, wordnet, author, timestamp);
            if (request.examples != null) for (ExampleData item : request.examples)
                createExample(connection, synset, item, wordnet, author, timestamp);
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
            return response(connection, wordnet, synset, null);
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> get(String wordnetId, String id, String sections) {
        return get(wordnetId, id, sections, null);
    }

    public Map<String, Object> get(String wordnetId, String id, String sections,
            String direction) {
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI synset = rdf.resource(connection, id, WordnetVocabulary.SYNSET,
                    wordnet.graph, "SYNSET_NOT_FOUND");
            validateCore(connection, wordnet, synset);
            return response(connection, wordnet, synset, sections, direction);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> list(String wordnetId, String partOfSpeech,
            String ili, String memberLemma, String definition,
            int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0)
            throw rdf.invalid("INVALID_PAGINATION", "Invalid limit or offset");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI pos = partOfSpeech == null ? null
                    : validatePartOfSpeech(connection, partOfSpeech);
            IRI iliFilter = ili == null ? null : rdf.absoluteIri(ili,
                    "INVALID_ILI", "ili");
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, RDF.TYPE, WordnetVocabulary.SYNSET, false, wordnet.graph)) {
                while (statements.hasNext()) {
                    IRI synset = (IRI) statements.next().getSubject();
                    validateCore(connection, wordnet, synset);
                    IRI itemPos = requiredIri(connection, synset,
                            WordnetVocabulary.PART_OF_SPEECH, wordnet.graph);
                    IRI itemIli = rdf.firstIri(connection, synset,
                            WordnetVocabulary.ILI, wordnet.graph);
                    if (pos != null && !pos.equals(itemPos)) continue;
                    if (iliFilter != null && !iliFilter.equals(itemIli)) continue;
                    if (definition != null && !containsDefinition(connection,
                            synset, definition, wordnet.graph)) continue;
                    if (memberLemma != null && !containsMemberLemma(connection,
                            synset, memberLemma, wordnet.language,
                            wordnet.graph)) continue;
                    Map<String, Object> item = rdf.baseResponse(connection,
                            wordnet.wordnetId, "synset", synset, wordnet.graph);
                    item.put("partOfSpeech", itemPos == null ? null
                            : itemPos.stringValue());
                    item.put("ili", itemIli == null ? null : itemIli.stringValue());
                    item.put("memberCount", Integer.valueOf(memberList.read(
                            connection, synset, wordnet.graph).size()));
                    matches.add(item);
                }
            }
            matches.sort((left, right) -> ((String) left.get("iri"))
                    .compareTo((String) right.get("iri")));
            return page(matches, limit, offset);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> patch(String wordnetId, String id,
            SynsetPatch request, String author) {
        if (request == null) throw rdf.invalid("INVALID_PATCH", "request is required");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet =
                    resolver.resolve(connection, wordnetId);
            IRI synset = rdf.resource(connection, id, WordnetVocabulary.SYNSET,
                    wordnet.graph, "SYNSET_NOT_FOUND");
            validateCore(connection, wordnet, synset);
            connection.begin();
            rdf.checkExpectedModified(connection, synset, request, wordnet.graph);
            IRI pos = request.hasPartOfSpeech()
                    ? validatePartOfSpeech(connection, request.getPartOfSpeech()) : null;
            if (pos != null) validateMemberCompatibility(connection, synset, pos,
                    wordnet.graph);
            IRI ili = request.hasIli() && request.getIli() != null
                    ? rdf.absoluteIri(request.getIli(), "INVALID_ILI", "ili") : null;
            List<IRI> order = request.hasMemberOrder()
                    ? decodeMemberOrder(connection, synset, request.getMemberOrder(),
                            wordnet.graph) : null;
            boolean changed = rdf.patchNativeAndMetadata(connection, synset,
                    request, wordnet.graph);
            if (request.hasPartOfSpeech()) {
                connection.remove(synset, WordnetVocabulary.PART_OF_SPEECH,
                        null, wordnet.graph);
                connection.add(synset, WordnetVocabulary.PART_OF_SPEECH, pos,
                        wordnet.graph);
                changed = true;
            }
            if (request.hasIli()) {
                connection.remove(synset, WordnetVocabulary.ILI, null, wordnet.graph);
                if (ili != null) connection.add(synset, WordnetVocabulary.ILI,
                        ili, wordnet.graph);
                changed = true;
            }
            if (request.hasLexfile()) {
                connection.remove(synset, WordnetVocabulary.LEXFILE, null,
                        wordnet.graph);
                if (request.getLexfile() != null) connection.add(synset,
                        WordnetVocabulary.LEXFILE, vf.createLiteral(rdf.required(
                                request.getLexfile(), "INVALID_PATCH",
                                "lexfile cannot be blank")), wordnet.graph);
                changed = true;
            }
            if (order != null) {
                memberList.rebuild(connection, synset, order, wordnet.graph);
                changed = true;
            }
            changed |= mutateDefinitions(connection, synset, request.definitions,
                    wordnet, author);
            changed |= mutateExamples(connection, synset, request.examples,
                    wordnet, author);
            if (!changed) throw rdf.invalid("INVALID_PATCH", "No effective change supplied");
            rdf.touch(connection, synset, rdf.timestamp(), wordnet.graph);
            connection.commit();
            return response(connection, wordnet, synset, null);
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
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI synset = rdf.resource(connection, id, WordnetVocabulary.SYNSET,
                    wordnet.graph, "SYNSET_NOT_FOUND");
            validateCore(connection, wordnet, synset);
            connection.begin();
            if (hasReferences(connection, synset, WordnetVocabulary.REFERENCE,
                    wordnet.graph)) throw new WordnetException(409,
                            "SYNSET_HAS_MEMBERS", "Delete member Senses first");
            if (hasReferences(connection, synset, WordnetVocabulary.SOURCE,
                    wordnet.graph) || hasReferences(connection, synset,
                            WordnetVocabulary.TARGET, wordnet.graph))
                throw new WordnetException(409, "SYNSET_HAS_RELATIONS",
                        "Delete incident relations first");
            checkExpected(connection, synset, expectedModified, wordnet.graph);
            memberList.rebuild(connection, synset, new ArrayList<IRI>(), wordnet.graph);
            for (IRI child : linkedChildren(connection, synset, wordnet.graph)) {
                rdf.requireExclusiveIncoming(connection, child, synset,
                        wordnet.graph, WordnetVocabulary.DEFINITION_LINK,
                        WordnetVocabulary.ILI_DEFINITION_LINK,
                        WordnetVocabulary.EXAMPLE_LINK);
                connection.remove(child, null, null, wordnet.graph);
            }
            connection.remove(synset, null, null, wordnet.graph);
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
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI synset,
            String sections) {
        return response(connection, wordnet, synset, sections, null);
    }

    Map<String, Object> response(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI synset,
            String sections, String direction) {
        Set<String> selected = WordnetSectionParser.parse(sections,
                "members", "definitions", "examples", "relations");
        validateCore(connection, wordnet, synset);
        boolean core = selected.contains("core");
        String relationDirection = requireDirection(direction,
                selected.contains("relations"));
        Map<String, Object> result = rdf.baseResponse(connection,
                wordnet.wordnetId, "synset", synset, wordnet.graph, core);
        if (core) {
            IRI pos = rdf.firstIri(connection, synset,
                    WordnetVocabulary.PART_OF_SPEECH, wordnet.graph);
            IRI ili = rdf.firstIri(connection, synset, WordnetVocabulary.ILI,
                    wordnet.graph);
            result.put("partOfSpeech", pos == null ? null : pos.stringValue());
            result.put("ili", ili == null ? null : ili.stringValue());
            result.put("lexfile", rdf.first(connection, synset,
                    WordnetVocabulary.LEXFILE, wordnet.graph));
        }
        if (selected.contains("metadata")) result.put("metadata",
                rdf.readMetadata(connection, synset, wordnet.graph));
        if (selected.contains("members")) result.put("members",
                memberSummaries(connection, synset, wordnet.graph));
        if (selected.contains("definitions")) {
            List<Map<String, Object>> definitions = childResponses(connection,
                    wordnet, synset, WordnetVocabulary.DEFINITION_LINK, "definition");
            definitions.addAll(childResponses(connection, wordnet, synset,
                    WordnetVocabulary.ILI_DEFINITION_LINK, "iliDefinition"));
            result.put("definitions", definitions);
        }
        if (selected.contains("examples")) result.put("examples",
                childResponses(connection, wordnet, synset,
                        WordnetVocabulary.EXAMPLE_LINK, "example"));
        if (selected.contains("relations")) result.put("relations",
                relationSummaries(connection, synset, wordnet.graph,
                        relationDirection));
        return result;
    }

    private IRI createDefinition(RepositoryConnection connection, IRI synset,
            DefinitionData request, WordnetGraphResolver.ResolvedWordnet wordnet,
            String author, String timestamp) {
        if (request == null) throw rdf.invalid("MISSING_REQUIRED_FIELD", "definition is required");
        String kind = rdf.required(request.kind, "MISSING_REQUIRED_FIELD",
                "definition kind is required");
        boolean iliDefinition = "iliDefinition".equals(kind);
        if (!iliDefinition && !"definition".equals(kind))
            throw rdf.invalid("INVALID_PATCH", "definition kind is invalid");
        String language = requireLanguage(request.language);
        if (iliDefinition && !"en".equals(language))
            throw rdf.invalid("INVALID_PATCH", "ILI definitions must use language en");
        String text = rdf.required(request.text, "MISSING_REQUIRED_FIELD",
                "definition text is required");
        IRI source = null;
        if (request.sourceSenseId != null) {
            if (iliDefinition) throw rdf.invalid("INVALID_PATCH",
                    "ILI definitions cannot have sourceSenseId");
            source = rdf.resource(connection, request.sourceSenseId,
                    WordnetVocabulary.SENSE, wordnet.graph, "SENSE_NOT_FOUND");
            new WordnetSenseManager(repository).validateIntegrity(connection,
                    source, wordnet);
        }
        IRI definition = rdf.mint(connection);
        connection.add(synset, iliDefinition
                ? WordnetVocabulary.ILI_DEFINITION_LINK
                : WordnetVocabulary.DEFINITION_LINK, definition, wordnet.graph);
        connection.add(definition, RDF.TYPE, iliDefinition
                ? WordnetVocabulary.ILI_DEFINITION : WordnetVocabulary.DEFINITION,
                wordnet.graph);
        connection.add(definition, RDF.VALUE, vf.createLiteral(text, language),
                wordnet.graph);
        if (source != null) connection.add(definition,
                WordnetVocabulary.SOURCE_SENSE, source, wordnet.graph);
        rdf.addAudit(connection, definition, author, timestamp, wordnet.graph);
        rdf.addNativeAndMetadata(connection, definition, request, wordnet.graph);
        return definition;
    }

    private IRI createExample(RepositoryConnection connection, IRI owner,
            ExampleData request, WordnetGraphResolver.ResolvedWordnet wordnet,
            String author, String timestamp) {
        if (request == null) throw rdf.invalid("MISSING_REQUIRED_FIELD", "example is required");
        String text = rdf.required(request.text, "MISSING_REQUIRED_FIELD",
                "example text is required");
        String language = requireLanguage(request.language);
        IRI example = rdf.mint(connection);
        connection.add(owner, WordnetVocabulary.EXAMPLE_LINK, example, wordnet.graph);
        connection.add(example, RDF.TYPE, WordnetVocabulary.EXAMPLE, wordnet.graph);
        connection.add(example, RDF.VALUE, vf.createLiteral(text, language), wordnet.graph);
        rdf.addAudit(connection, example, author, timestamp, wordnet.graph);
        rdf.addNativeAndMetadata(connection, example, request, wordnet.graph);
        return example;
    }

    private boolean mutateDefinitions(RepositoryConnection connection, IRI synset,
            ChildChanges<DefinitionData, DefinitionUpdate> changes,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author) {
        if (changes == null) return false;
        if (!changes.hasOperations()) return false;
        validateChanges(changes);
        String timestamp = rdf.timestamp();
        if (changes.add != null) for (DefinitionData item : changes.add)
            createDefinition(connection, synset, item, wordnet, author, timestamp);
        if (changes.update != null) for (DefinitionUpdate update : changes.update) {
            IRI child = definitionChild(connection, synset, update.id, wordnet.graph);
            boolean ili = connection.hasStatement(child, RDF.TYPE,
                    WordnetVocabulary.ILI_DEFINITION, false, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, child,
                    update, wordnet.graph);
            if (update.hasKind()) {
                if (!(ili ? "iliDefinition" : "definition").equals(update.getKind()))
                    throw rdf.invalid("INVALID_PATCH", "definition kind is immutable");
            }
            if (update.hasSourceSenseId()) {
                IRI current = rdf.firstIri(connection, child,
                        WordnetVocabulary.SOURCE_SENSE, wordnet.graph);
                IRI requested = update.getSourceSenseId() == null ? null
                        : rdf.resource(connection, update.getSourceSenseId(),
                                WordnetVocabulary.SENSE, wordnet.graph,
                                "SENSE_NOT_FOUND");
                if (requested != null) {
                    new WordnetSenseManager(repository).validateIntegrity(
                            connection, requested, wordnet);
                }
                if (current == null ? requested != null : !current.equals(requested))
                    throw rdf.invalid("INVALID_PATCH", "definition source is immutable");
            }
            if (update.hasText() || update.hasLanguage()) {
                Literal current = rdf.firstLiteral(connection, child, RDF.VALUE,
                        wordnet.graph);
                if (current == null) throw new WordnetException(409,
                        "GRAPH_INTEGRITY_CONFLICT", "Definition value is missing");
                String text = update.hasText() ? rdf.required(update.getText(),
                        "INVALID_PATCH", "definition text is required")
                        : current.getLabel();
                String language = update.hasLanguage()
                        ? requireLanguage(update.getLanguage())
                        : current.getLanguage().orElseThrow(() ->
                                rdf.invalid("INVALID_LANGUAGE",
                                        "Definition language is missing"));
                if (ili && !"en".equals(language))
                    throw rdf.invalid("INVALID_PATCH", "ILI definitions must use language en");
                connection.remove(child, RDF.VALUE, null, wordnet.graph);
                connection.add(child, RDF.VALUE, vf.createLiteral(text, language),
                        wordnet.graph);
                changed = true;
            }
            if (!changed) throw rdf.invalid("INVALID_PATCH",
                    "definition update contains no effective change");
            rdf.touch(connection, child, timestamp, wordnet.graph);
        }
        removeChildren(connection, synset, changes.remove, true, wordnet.graph);
        return true;
    }

    private boolean mutateExamples(RepositoryConnection connection, IRI synset,
            ChildChanges<ExampleData, ExampleUpdate> changes,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author) {
        if (changes == null) return false;
        if (!changes.hasOperations()) return false;
        validateChanges(changes);
        String timestamp = rdf.timestamp();
        if (changes.add != null) for (ExampleData item : changes.add)
            createExample(connection, synset, item, wordnet, author, timestamp);
        if (changes.update != null) for (ExampleUpdate update : changes.update) {
            IRI child = ownedChild(connection, synset, update.id,
                    WordnetVocabulary.EXAMPLE_LINK, WordnetVocabulary.EXAMPLE, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, child,
                    update, wordnet.graph);
            if (update.hasText() || update.hasLanguage()) {
                Literal current = rdf.firstLiteral(connection, child, RDF.VALUE,
                        wordnet.graph);
                if (current == null) throw new WordnetException(409,
                        "GRAPH_INTEGRITY_CONFLICT", "Example value is missing");
                String text = update.hasText() ? rdf.required(update.getText(),
                        "INVALID_PATCH", "example text is required")
                        : current.getLabel();
                String language = update.hasLanguage()
                        ? requireLanguage(update.getLanguage())
                        : current.getLanguage().orElseThrow(() ->
                                rdf.invalid("INVALID_LANGUAGE",
                                        "Example language is missing"));
                connection.remove(child, RDF.VALUE, null, wordnet.graph);
                connection.add(child, RDF.VALUE, vf.createLiteral(text, language),
                        wordnet.graph);
                changed = true;
            }
            if (!changed) throw rdf.invalid("INVALID_PATCH",
                    "example update contains no effective change");
            rdf.touch(connection, child, timestamp, wordnet.graph);
        }
        removeChildren(connection, synset, changes.remove, false, wordnet.graph);
        return true;
    }

    private List<Map<String, Object>> memberSummaries(RepositoryConnection connection,
            IRI synset, Resource graph) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        int position = 0;
        for (IRI sense : memberList.read(connection, synset, graph)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("position", Integer.valueOf(++position));
            item.put("senseId", WordnetIdCodec.encode(sense.stringValue()));
            IRI entry = incomingOne(connection, sense, WordnetVocabulary.SENSE_LINK, graph);
            item.put("entryId", entry == null ? null
                    : WordnetIdCodec.encode(entry.stringValue()));
            IRI form = entry == null ? null : rdf.firstIri(connection, entry,
                    WordnetVocabulary.CANONICAL_FORM, graph);
            item.put("writtenRep", form == null ? null : rdf.first(connection, form,
                    WordnetVocabulary.WRITTEN_REP, graph));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> childResponses(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI owner, IRI link,
            String type) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (IRI child : rdf.iris(connection, owner, link, wordnet.graph)) {
            Map<String, Object> item = rdf.baseResponse(connection, wordnet.wordnetId,
                    type, child, wordnet.graph);
            Literal value = rdf.firstLiteral(connection, child, RDF.VALUE,
                    wordnet.graph);
            item.put("text", value == null ? null : value.getLabel());
            item.put("language", value == null || !value.getLanguage().isPresent()
                    ? null : value.getLanguage().get());
            item.put("kind", type);
            IRI source = rdf.firstIri(connection, child,
                    WordnetVocabulary.SOURCE_SENSE, wordnet.graph);
            if (source != null) item.put("sourceSenseId",
                    WordnetIdCodec.encode(source.stringValue()));
            item.put("metadata", rdf.readMetadata(connection, child, wordnet.graph));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> relationSummaries(RepositoryConnection connection,
            IRI synset, Resource graph, String direction) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try (RepositoryResult<Statement> relations = connection.getStatements(
                null, RDF.TYPE, WordnetVocabulary.CONCEPTUAL_RELATION, false, graph)) {
            while (relations.hasNext()) {
                IRI relation = (IRI) relations.next().getSubject();
                IRI source = rdf.firstIri(connection, relation, WordnetVocabulary.SOURCE, graph);
                IRI target = rdf.firstIri(connection, relation, WordnetVocabulary.TARGET, graph);
                if (!synset.equals(source) && !synset.equals(target)) continue;
                String itemDirection = synset.equals(source) ? "out" : "in";
                if (!"both".equals(direction)
                        && !direction.equals(itemDirection)) continue;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", WordnetIdCodec.encode(relation.stringValue()));
                item.put("iri", relation.stringValue());
                item.put("direction", itemDirection);
                item.put("category", iriValue(connection, relation,
                        WordnetVocabulary.RELATION_CATEGORY, graph));
                item.put("sourceId", WordnetIdCodec.encode(source.stringValue()));
                item.put("targetId", WordnetIdCodec.encode(target.stringValue()));
                result.add(item);
            }
        }
        result.sort((left, right) -> ((String) left.get("iri"))
                .compareTo((String) right.get("iri")));
        return result;
    }

    private String requireDirection(String direction, boolean relationsSelected) {
        if (direction == null) {
            return "both";
        }
        String value = direction.trim();
        if (!relationsSelected || !("in".equals(value) || "out".equals(value)
                || "both".equals(value))) {
            throw new WordnetException(400, "INVALID_SECTION",
                    "direction is valid only for a selected relations section");
        }
        return value;
    }

    private void validateMembership(RepositoryConnection connection, IRI synset,
            Resource graph) {
        Set<String> list = new HashSet<String>();
        for (IRI member : memberList.read(connection, synset, graph))
            list.add(member.stringValue());
        Set<String> references = new HashSet<String>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, WordnetVocabulary.REFERENCE, synset, false, graph)) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (subject instanceof IRI) references.add(subject.stringValue());
            }
        }
        if (!list.equals(references)) throw new WordnetException(409,
                "GRAPH_INTEGRITY_CONFLICT", "wn:memberList differs from Sense references");
    }

    void validateCore(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI synset) {
        IRI scheme = requiredIri(connection, synset, SKOS.IN_SCHEME,
                wordnet.graph);
        IRI pos = requiredIri(connection, synset,
                WordnetVocabulary.PART_OF_SPEECH, wordnet.graph);
        if (!wordnet.root.equals(scheme)
                || !connection.hasStatement(pos, RDF.TYPE,
                        WordnetVocabulary.POS_TYPE, false,
                        resolver.schemaGraph())) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Synset scheme or part of speech is invalid");
        }
        validateMembership(connection, synset, wordnet.graph);
    }

    private IRI requiredIri(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        List<Value> values = new ArrayList<Value>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                values.add(statements.next().getObject());
            }
        }
        if (values.size() != 1 || !(values.get(0) instanceof IRI)) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Synset property cardinality is invalid");
        }
        return (IRI) values.get(0);
    }

    private List<IRI> decodeMemberOrder(RepositoryConnection connection, IRI synset,
            List<String> ids, Resource graph) {
        if (ids == null) throw rdf.invalid("INVALID_PATCH", "memberOrder cannot be null");
        List<IRI> current = memberList.read(connection, synset, graph);
        Set<IRI> expected = new HashSet<IRI>(current);
        List<IRI> result = new ArrayList<IRI>();
        for (String id : ids) {
            IRI sense = rdf.resource(connection, id, WordnetVocabulary.SENSE,
                    graph, "SENSE_NOT_FOUND");
            if (!result.contains(sense)) result.add(sense);
            else throw new WordnetException(409, "MEMBER_ORDER_MISMATCH",
                    "memberOrder contains duplicates");
        }
        if (result.size() != ids.size() || !new HashSet<IRI>(result).equals(expected))
            throw new WordnetException(409, "MEMBER_ORDER_MISMATCH",
                    "memberOrder must contain exactly the current members");
        return result;
    }

    private void validateMemberCompatibility(RepositoryConnection connection,
            IRI synset, IRI synsetPos, Resource graph) {
        for (IRI sense : memberList.read(connection, synset, graph)) {
            IRI entry = incomingOne(connection, sense,
                    WordnetVocabulary.SENSE_LINK, graph);
            IRI entryPos = entry == null ? null : rdf.firstIri(connection, entry,
                    WordnetVocabulary.PART_OF_SPEECH, graph);
            if (!WordnetEntryManager.compatible(entryPos, synsetPos))
                throw new WordnetException(422, "INCOMPATIBLE_POS",
                        "Synset part of speech conflicts with a member Entry");
        }
    }

    private IRI validatePartOfSpeech(RepositoryConnection connection, String value) {
        IRI pos = rdf.expandedIri(value, "INVALID_PART_OF_SPEECH", "partOfSpeech");
        if (!connection.hasStatement(pos, RDF.TYPE, WordnetVocabulary.POS_TYPE,
                false, resolver.schemaGraph()))
            throw new WordnetException(422, "INVALID_PART_OF_SPEECH",
                    "partOfSpeech is not declared by the WordNet schema");
        return pos;
    }

    private String requireLanguage(String language) {
        String value = rdf.required(language, "MISSING_REQUIRED_FIELD",
                "language is required");
        try {
            new Locale.Builder().setLanguageTag(value);
            if (Locale.forLanguageTag(value).getLanguage().isEmpty()) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException ex) {
            throw rdf.invalid("INVALID_LANGUAGE", "Invalid BCP 47 language tag");
        }
    }

    private boolean containsDefinition(RepositoryConnection connection, IRI synset,
            String text, Resource graph) {
        List<IRI> definitions = new ArrayList<IRI>();
        definitions.addAll(rdf.iris(connection, synset,
                WordnetVocabulary.DEFINITION_LINK, graph));
        definitions.addAll(rdf.iris(connection, synset,
                WordnetVocabulary.ILI_DEFINITION_LINK, graph));
        for (IRI child : definitions) {
            Literal value = rdf.firstLiteral(connection, child, RDF.VALUE, graph);
            if (value == null) continue;
            Locale locale = value.getLanguage().isPresent()
                    ? Locale.forLanguageTag(value.getLanguage().get()) : Locale.ROOT;
            if (value.getLabel().toLowerCase(locale)
                    .contains(text.toLowerCase(locale))) return true;
        }
        return false;
    }

    private boolean containsMemberLemma(RepositoryConnection connection, IRI synset,
            String lemma, String language, Resource graph) {
        for (IRI sense : memberList.read(connection, synset, graph)) {
            IRI entry = incomingOne(connection, sense, WordnetVocabulary.SENSE_LINK, graph);
            IRI form = entry == null ? null : rdf.firstIri(connection, entry,
                    WordnetVocabulary.CANONICAL_FORM, graph);
            Literal written = form == null ? null : rdf.firstLiteral(connection, form,
                    WordnetVocabulary.WRITTEN_REP, graph);
            Locale locale = Locale.forLanguageTag(language);
            if (written != null && written.getLanguage().isPresent()
                    && language.equals(written.getLanguage().get())
                    && written.getLabel().toLowerCase(locale)
                            .contains(lemma.toLowerCase(locale))) return true;
        }
        return false;
    }

    private IRI incomingOne(RepositoryConnection connection, IRI object,
            IRI predicate, Resource graph) {
        IRI result = null;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, predicate, object, false, graph)) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (!(subject instanceof IRI) || result != null)
                    throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                            "Resource must have exactly one owner");
                result = (IRI) subject;
            }
        }
        return result;
    }

    private boolean hasReferences(RepositoryConnection connection, IRI object,
            IRI predicate, Resource graph) {
        return connection.hasStatement(null, predicate, object, false, graph);
    }

    private List<IRI> linkedChildren(RepositoryConnection connection, IRI synset,
            Resource graph) {
        List<IRI> result = new ArrayList<IRI>();
        result.addAll(rdf.iris(connection, synset, WordnetVocabulary.DEFINITION_LINK, graph));
        result.addAll(rdf.iris(connection, synset, WordnetVocabulary.ILI_DEFINITION_LINK, graph));
        result.addAll(rdf.iris(connection, synset, WordnetVocabulary.EXAMPLE_LINK, graph));
        return result;
    }

    private IRI definitionChild(RepositoryConnection connection, IRI owner,
            String id, Resource graph) {
        IRI child = vf.createIRI(WordnetIdCodec.decode(id));
        boolean normal = connection.hasStatement(owner,
                WordnetVocabulary.DEFINITION_LINK, child, false, graph)
                && connection.hasStatement(child, RDF.TYPE,
                        WordnetVocabulary.DEFINITION, false, graph);
        boolean ili = connection.hasStatement(owner,
                WordnetVocabulary.ILI_DEFINITION_LINK, child, false, graph)
                && connection.hasStatement(child, RDF.TYPE,
                        WordnetVocabulary.ILI_DEFINITION, false, graph);
        if (!normal && !ili) throw new WordnetException(404, "CHILD_NOT_FOUND",
                "Definition is not owned by this Synset");
        return child;
    }

    private IRI ownedChild(RepositoryConnection connection, IRI owner, String id,
            IRI link, IRI type, Resource graph) {
        IRI child = rdf.resource(connection, id, type, graph, "CHILD_NOT_FOUND");
        if (!connection.hasStatement(owner, link, child, false, graph))
            throw new WordnetException(404, "CHILD_NOT_FOUND",
                    "Child is not owned by this Synset");
        return child;
    }

    private <C, U extends it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ChildUpdate>
            void validateChanges(ChildChanges<C, U> changes) {
        Set<String> ids = new HashSet<String>();
        if (changes.update != null) for (U item : changes.update)
            if (item == null || item.id == null || !ids.add(item.id))
                throw rdf.invalid("INVALID_PATCH", "Duplicate child id");
        if (changes.remove != null) for (String id : changes.remove)
            if (!ids.add(id)) throw rdf.invalid("INVALID_PATCH", "Child id appears twice");
    }

    private void removeChildren(RepositoryConnection connection, IRI owner,
            List<String> ids, boolean definitions, Resource graph) {
        if (ids == null) return;
        for (String id : ids) {
            IRI child;
            if (definitions) {
                child = definitionChild(connection, owner, id, graph);
                rdf.requireExclusiveIncoming(connection, child, owner, graph,
                        WordnetVocabulary.DEFINITION_LINK,
                        WordnetVocabulary.ILI_DEFINITION_LINK);
                connection.remove(owner, WordnetVocabulary.DEFINITION_LINK, child, graph);
                connection.remove(owner, WordnetVocabulary.ILI_DEFINITION_LINK, child, graph);
            } else {
                child = ownedChild(connection, owner, id,
                        WordnetVocabulary.EXAMPLE_LINK, WordnetVocabulary.EXAMPLE, graph);
                rdf.requireExclusiveIncoming(connection, child, owner, graph,
                        WordnetVocabulary.EXAMPLE_LINK);
                connection.remove(owner, WordnetVocabulary.EXAMPLE_LINK, child, graph);
            }
            connection.remove(child, null, null, graph);
        }
    }

    private void checkExpected(RepositoryConnection connection, IRI resource,
            String expected, Resource graph) {
        if (expected != null && !expected.equals(rdf.first(connection, resource,
                org.eclipse.rdf4j.model.vocabulary.DCTERMS.MODIFIED, graph)))
            throw new WordnetException(409, "MODIFIED_MISMATCH",
                    "Resource was modified after the supplied precondition");
    }

    private String iriValue(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        IRI value = rdf.firstIri(connection, subject, predicate, graph);
        return value == null ? null : value.stringValue();
    }

    private Map<String, Object> page(List<Map<String, Object>> items,
            int limit, int offset) {
        int from = Math.min(offset, items.size());
        int to = Math.min(from + limit, items.size());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", new ArrayList<Map<String, Object>>(items.subList(from, to)));
        result.put("total", Integer.valueOf(items.size()));
        result.put("limit", Integer.valueOf(limit));
        result.put("offset", Integer.valueOf(offset));
        return result;
    }
}
