package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ChildChanges;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.CountData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.CountUpdate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ExampleData;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.ExampleUpdate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SenseCreate;
import it.cnr.ilc.lexo.service.data.wordnet.WordnetRequests.SensePatch;
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
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Sense CRUD with entry/synset integrity and ordered member-list maintenance. */
public final class WordnetSenseManager extends AbstractWordnetManager {

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final WordnetSynsetManager synsetManager;
    private final WordnetMemberList memberList = new WordnetMemberList();

    public WordnetSenseManager() {
        this(null);
    }

    public WordnetSenseManager(Repository repository) {
        super(repository);
        this.synsetManager = new WordnetSynsetManager(repository);
    }

    public Map<String, Object> create(String wordnetId, SenseCreate request,
            String author) {
        if (request == null) throw rdf.invalid("MISSING_REQUIRED_FIELD", "request is required");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI entry = rdf.resource(connection, request.entryId, WordnetVocabulary.ENTRY,
                    wordnet.graph, "ENTRY_NOT_FOUND");
            IRI synset = rdf.resource(connection, request.synsetId, WordnetVocabulary.SYNSET,
                    wordnet.graph, "SYNSET_NOT_FOUND");
            WordnetEntryManager.requireOwnedEntry(connection, wordnet, entry);
            synsetManager.validateCore(connection, wordnet, synset);
            requireCompatible(connection, entry, synset, wordnet.graph);
            connection.begin();
            int number = request.n == null ? nextNumber(connection, entry, wordnet.graph)
                    : requirePositive(request.n, "n");
            requireUniqueNumber(connection, entry, null, number, wordnet.graph);
            List<IRI> behaviours = validateSubcategories(connection, entry,
                    request.subcategorizationIds, wordnet.graph);
            List<IRI> members = memberList.read(connection, synset, wordnet.graph);
            IRI sense = rdf.mint(connection);
            String timestamp = rdf.timestamp();
            connection.add(sense, RDF.TYPE, WordnetVocabulary.SENSE, wordnet.graph);
            connection.add(entry, WordnetVocabulary.SENSE_LINK, sense, wordnet.graph);
            connection.add(sense, WordnetVocabulary.REFERENCE, synset, wordnet.graph);
            connection.add(sense, WordnetVocabulary.N,
                    vf.createLiteral(Integer.toString(number), XSD.INTEGER), wordnet.graph);
            rdf.addAudit(connection, sense, author, timestamp, wordnet.graph);
            rdf.addNativeAndMetadata(connection, sense, request, wordnet.graph);
            members.add(sense);
            memberList.rebuild(connection, synset, members, wordnet.graph);
            for (IRI behaviour : behaviours) connection.add(behaviour,
                    WordnetVocabulary.SENSE_SUBCAT, sense, wordnet.graph);
            if (request.examples != null) for (ExampleData item : request.examples)
                createExample(connection, sense, item, wordnet, author, timestamp);
            if (request.counts != null) for (CountData item : request.counts)
                createCount(connection, sense, item, wordnet, author, timestamp);
            rdf.touch(connection, entry, timestamp, wordnet.graph);
            rdf.touch(connection, synset, timestamp, wordnet.graph);
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
            return response(connection, wordnet, sense, null);
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
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI sense = rdf.resource(connection, id, WordnetVocabulary.SENSE,
                    wordnet.graph, "SENSE_NOT_FOUND");
            validateOwnership(connection, sense, wordnet);
            return response(connection, wordnet, sense, sections, direction);
        } finally {
            release(connection);
        }
    }

    public Map<String, Object> list(String wordnetId, String entryId,
            String synsetId, Integer n, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0)
            throw rdf.invalid("INVALID_PAGINATION", "Invalid limit or offset");
        if (n != null) requirePositive(n, "n");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI entryFilter = entryId == null ? null : rdf.resource(connection, entryId,
                    WordnetVocabulary.ENTRY, wordnet.graph, "ENTRY_NOT_FOUND");
            IRI synsetFilter = synsetId == null ? null : rdf.resource(connection, synsetId,
                    WordnetVocabulary.SYNSET, wordnet.graph, "SYNSET_NOT_FOUND");
            List<Map<String, Object>> matches = new ArrayList<Map<String, Object>>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, RDF.TYPE, WordnetVocabulary.SENSE, false, wordnet.graph)) {
                while (statements.hasNext()) {
                    IRI sense = (IRI) statements.next().getSubject();
                    Ownership ownership = validateOwnership(connection, sense, wordnet);
                    Integer number = number(connection, sense, wordnet.graph);
                    if (entryFilter != null && !entryFilter.equals(ownership.entry)) continue;
                    if (synsetFilter != null && !synsetFilter.equals(ownership.synset)) continue;
                    if (n != null && !n.equals(number)) continue;
                    Map<String, Object> item = rdf.baseResponse(connection,
                            wordnet.wordnetId, "sense", sense, wordnet.graph);
                    item.put("entryId", WordnetIdCodec.encode(ownership.entry.stringValue()));
                    item.put("synsetId", WordnetIdCodec.encode(ownership.synset.stringValue()));
                    item.put("n", number);
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
            SensePatch request, String author) {
        if (request == null) throw rdf.invalid("INVALID_PATCH", "request is required");
        RepositoryConnection connection = acquire();
        try {
            WordnetGraphResolver.ResolvedWordnet wordnet = resolver.resolve(connection, wordnetId);
            IRI sense = rdf.resource(connection, id, WordnetVocabulary.SENSE,
                    wordnet.graph, "SENSE_NOT_FOUND");
            Ownership current = validateOwnership(connection, sense, wordnet);
            connection.begin();
            rdf.checkExpectedModified(connection, sense, request, wordnet.graph);
            IRI entry = request.hasEntryId() ? rdf.resource(connection,
                    request.getEntryId(), WordnetVocabulary.ENTRY, wordnet.graph,
                    "ENTRY_NOT_FOUND") : current.entry;
            IRI synset = request.hasSynsetId() ? rdf.resource(connection,
                    request.getSynsetId(), WordnetVocabulary.SYNSET, wordnet.graph,
                    "SYNSET_NOT_FOUND") : current.synset;
            WordnetEntryManager.requireOwnedEntry(connection, wordnet, entry);
            synsetManager.validateCore(connection, wordnet, synset);
            requireCompatible(connection, entry, synset, wordnet.graph);
            Integer oldNumber = number(connection, sense, wordnet.graph);
            int newNumber = request.hasN()
                    ? requirePositive(request.getN(), "n") : oldNumber.intValue();
            requireUniqueNumber(connection, entry, sense, newNumber, wordnet.graph);
            if (request.hasSubcategorizationIds()
                    && request.getSubcategorizationIds() == null) {
                throw rdf.invalid("INVALID_PATCH",
                        "subcategorizationIds cannot be null; use an empty array to clear it");
            }
            List<IRI> behaviours = request.hasSubcategorizationIds()
                    ? validateSubcategories(connection, entry,
                            request.getSubcategorizationIds(), wordnet.graph) : null;
            if (!entry.equals(current.entry) && behaviours == null) {
                for (IRI behaviour : incomingBehaviours(connection, sense, wordnet.graph))
                    if (!connection.hasStatement(entry, WordnetVocabulary.SYN_BEHAVIOR,
                            behaviour, false, wordnet.graph))
                        throw new WordnetException(422, "INVALID_REFERENCED_ENTITY",
                                "Supply compatible subcategorizationIds when moving the Sense");
            }
            List<IRI> oldMembers = memberList.read(connection, current.synset, wordnet.graph);
            List<IRI> newMembers = synset.equals(current.synset) ? oldMembers
                    : memberList.read(connection, synset, wordnet.graph);
            if (!oldMembers.contains(sense)) throw new WordnetException(409,
                    "GRAPH_INTEGRITY_CONFLICT", "Sense is missing from wn:memberList");
            boolean changed = rdf.patchNativeAndMetadata(connection, sense,
                    request, wordnet.graph);
            if (!entry.equals(current.entry)) {
                connection.remove(current.entry, WordnetVocabulary.SENSE_LINK,
                        sense, wordnet.graph);
                connection.add(entry, WordnetVocabulary.SENSE_LINK, sense, wordnet.graph);
                changed = true;
            }
            if (!synset.equals(current.synset)) {
                connection.remove(sense, WordnetVocabulary.REFERENCE, current.synset,
                        wordnet.graph);
                connection.add(sense, WordnetVocabulary.REFERENCE, synset, wordnet.graph);
                oldMembers.remove(sense);
                newMembers.add(sense);
                memberList.rebuild(connection, current.synset, oldMembers, wordnet.graph);
                memberList.rebuild(connection, synset, newMembers, wordnet.graph);
                changed = true;
            }
            if (request.hasN()) {
                connection.remove(sense, WordnetVocabulary.N, null, wordnet.graph);
                connection.add(sense, WordnetVocabulary.N,
                        vf.createLiteral(Integer.toString(newNumber), XSD.INTEGER), wordnet.graph);
                changed = true;
            }
            if (behaviours != null) {
                connection.remove((Resource) null, WordnetVocabulary.SENSE_SUBCAT,
                        sense, wordnet.graph);
                for (IRI behaviour : behaviours) connection.add(behaviour,
                        WordnetVocabulary.SENSE_SUBCAT, sense, wordnet.graph);
                changed = true;
            }
            changed |= mutateExamples(connection, sense, request.examples,
                    wordnet, author);
            changed |= mutateCounts(connection, sense, request.counts,
                    wordnet, author);
            if (!changed) throw rdf.invalid("INVALID_PATCH", "No effective change supplied");
            String timestamp = rdf.timestamp();
            rdf.touch(connection, sense, timestamp, wordnet.graph);
            rdf.touch(connection, current.entry, timestamp, wordnet.graph);
            rdf.touch(connection, entry, timestamp, wordnet.graph);
            rdf.touch(connection, current.synset, timestamp, wordnet.graph);
            rdf.touch(connection, synset, timestamp, wordnet.graph);
            connection.commit();
            return response(connection, wordnet, sense, null);
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
            IRI sense = rdf.resource(connection, id, WordnetVocabulary.SENSE,
                    wordnet.graph, "SENSE_NOT_FOUND");
            Ownership ownership = validateOwnership(connection, sense, wordnet);
            connection.begin();
            if (connection.hasStatement(null, WordnetVocabulary.SOURCE, sense,
                    false, wordnet.graph) || connection.hasStatement(null,
                            WordnetVocabulary.TARGET, sense, false, wordnet.graph))
                throw new WordnetException(409, "SENSE_HAS_RELATIONS",
                        "Delete incident relations first");
            if (connection.hasStatement(null, WordnetVocabulary.SOURCE_SENSE,
                    sense, false, wordnet.graph))
                throw new WordnetException(409, "SENSE_IS_DEFINITION_SOURCE",
                        "Remove definition source links first");
            checkExpected(connection, sense, expectedModified, wordnet.graph);
            List<IRI> members = memberList.read(connection, ownership.synset, wordnet.graph);
            if (!members.remove(sense)) throw new WordnetException(409,
                    "GRAPH_INTEGRITY_CONFLICT", "Sense is missing from wn:memberList");
            memberList.rebuild(connection, ownership.synset, members, wordnet.graph);
            connection.remove(ownership.entry, WordnetVocabulary.SENSE_LINK,
                    sense, wordnet.graph);
            connection.remove((Resource) null, WordnetVocabulary.SENSE_SUBCAT,
                    sense, wordnet.graph);
            for (IRI child : children(connection, sense, wordnet.graph)) {
                rdf.requireExclusiveIncoming(connection, child, sense,
                        wordnet.graph, WordnetVocabulary.EXAMPLE_LINK,
                        WordnetVocabulary.COUNT_LINK);
                connection.remove(child, null, null, wordnet.graph);
            }
            connection.remove(sense, null, null, wordnet.graph);
            String timestamp = rdf.timestamp();
            rdf.touch(connection, ownership.entry, timestamp, wordnet.graph);
            rdf.touch(connection, ownership.synset, timestamp, wordnet.graph);
            rdf.touch(connection, wordnet.root, timestamp, wordnet.graph);
            connection.commit();
        } catch (RuntimeException ex) {
            rollback(connection);
            throw ex;
        } finally {
            release(connection);
        }
    }

    Map<String, Object> response(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI sense,
            String sections) {
        return response(connection, wordnet, sense, sections, null);
    }

    Map<String, Object> response(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI sense,
            String sections, String direction) {
        Set<String> selected = WordnetSectionParser.parse(sections,
                "examples", "counts", "relations", "subcategorization");
        boolean core = selected.contains("core");
        String relationDirection = requireDirection(direction,
                selected.contains("relations"));
        Ownership ownership = validateOwnership(connection, sense, wordnet);
        Map<String, Object> result = rdf.baseResponse(connection, wordnet.wordnetId,
                "sense", sense, wordnet.graph, core);
        if (core) {
            result.put("entryId", WordnetIdCodec.encode(
                    ownership.entry.stringValue()));
            result.put("synsetId", WordnetIdCodec.encode(
                    ownership.synset.stringValue()));
            result.put("n", number(connection, sense, wordnet.graph));
        }
        if (selected.contains("metadata")) result.put("metadata",
                rdf.readMetadata(connection, sense, wordnet.graph));
        if (selected.contains("examples")) result.put("examples",
                childResponses(connection, wordnet, sense,
                        WordnetVocabulary.EXAMPLE_LINK, "example"));
        if (selected.contains("counts")) result.put("counts",
                childResponses(connection, wordnet, sense,
                        WordnetVocabulary.COUNT_LINK, "count"));
        if (selected.contains("subcategorization")) {
            List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
            for (IRI behaviour : incomingBehaviours(connection, sense, wordnet.graph)) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", WordnetIdCodec.encode(behaviour.stringValue()));
                item.put("iri", behaviour.stringValue());
                values.add(item);
            }
            result.put("subcategorization", values);
        }
        if (selected.contains("relations")) result.put("relations",
                relationSummaries(connection, sense, wordnet.graph,
                        relationDirection));
        return result;
    }

    private IRI createExample(RepositoryConnection connection, IRI sense,
            ExampleData request, WordnetGraphResolver.ResolvedWordnet wordnet,
            String author, String timestamp) {
        if (request == null) throw rdf.invalid("MISSING_REQUIRED_FIELD", "example is required");
        IRI example = rdf.mint(connection);
        connection.add(sense, WordnetVocabulary.EXAMPLE_LINK, example, wordnet.graph);
        connection.add(example, RDF.TYPE, WordnetVocabulary.EXAMPLE, wordnet.graph);
        connection.add(example, RDF.VALUE, vf.createLiteral(rdf.required(request.text,
                "MISSING_REQUIRED_FIELD", "example text is required"),
                requireLanguage(request.language)), wordnet.graph);
        rdf.addAudit(connection, example, author, timestamp, wordnet.graph);
        rdf.addNativeAndMetadata(connection, example, request, wordnet.graph);
        return example;
    }

    private IRI createCount(RepositoryConnection connection, IRI sense,
            CountData request, WordnetGraphResolver.ResolvedWordnet wordnet,
            String author, String timestamp) {
        if (request == null || request.value == null || request.value.intValue() < 0)
            throw rdf.invalid("MISSING_REQUIRED_FIELD", "count value must be nonnegative");
        IRI count = rdf.mint(connection);
        connection.add(sense, WordnetVocabulary.COUNT_LINK, count, wordnet.graph);
        connection.add(count, RDF.TYPE, WordnetVocabulary.COUNT, wordnet.graph);
        connection.add(count, RDF.VALUE, vf.createLiteral(request.value.toString(),
                XSD.NON_NEGATIVE_INTEGER), wordnet.graph);
        rdf.addAudit(connection, count, author, timestamp, wordnet.graph);
        rdf.addNativeAndMetadata(connection, count, request, wordnet.graph);
        return count;
    }

    private boolean mutateExamples(RepositoryConnection connection, IRI sense,
            ChildChanges<ExampleData, ExampleUpdate> changes,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author) {
        if (changes == null) return false;
        if (!changes.hasOperations()) return false;
        validateChanges(changes);
        String timestamp = rdf.timestamp();
        if (changes.add != null) for (ExampleData item : changes.add)
            createExample(connection, sense, item, wordnet, author, timestamp);
        if (changes.update != null) for (ExampleUpdate update : changes.update) {
            IRI child = ownedChild(connection, sense, update.id,
                    WordnetVocabulary.EXAMPLE_LINK, WordnetVocabulary.EXAMPLE,
                    wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, child,
                    update, wordnet.graph);
            if (update.hasText() || update.hasLanguage()) {
                org.eclipse.rdf4j.model.Literal current = rdf.firstLiteral(
                        connection, child, RDF.VALUE, wordnet.graph);
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
        removeChildren(connection, sense, changes.remove,
                WordnetVocabulary.EXAMPLE_LINK, WordnetVocabulary.EXAMPLE, wordnet.graph);
        return true;
    }

    private boolean mutateCounts(RepositoryConnection connection, IRI sense,
            ChildChanges<CountData, CountUpdate> changes,
            WordnetGraphResolver.ResolvedWordnet wordnet, String author) {
        if (changes == null) return false;
        if (!changes.hasOperations()) return false;
        validateChanges(changes);
        String timestamp = rdf.timestamp();
        if (changes.add != null) for (CountData item : changes.add)
            createCount(connection, sense, item, wordnet, author, timestamp);
        if (changes.update != null) for (CountUpdate update : changes.update) {
            IRI child = ownedChild(connection, sense, update.id,
                    WordnetVocabulary.COUNT_LINK, WordnetVocabulary.COUNT, wordnet.graph);
            boolean changed = rdf.patchNativeAndMetadata(connection, child,
                    update, wordnet.graph);
            if (update.hasValue()) {
                if (update.getValue() == null || update.getValue().intValue() < 0)
                    throw rdf.invalid("INVALID_PATCH", "count value must be nonnegative");
                connection.remove(child, RDF.VALUE, null, wordnet.graph);
                connection.add(child, RDF.VALUE, vf.createLiteral(
                        update.getValue().toString(),
                        XSD.NON_NEGATIVE_INTEGER), wordnet.graph);
                changed = true;
            }
            if (!changed) throw rdf.invalid("INVALID_PATCH",
                    "count update contains no effective change");
            rdf.touch(connection, child, timestamp, wordnet.graph);
        }
        removeChildren(connection, sense, changes.remove,
                WordnetVocabulary.COUNT_LINK, WordnetVocabulary.COUNT, wordnet.graph);
        return true;
    }

    void validateIntegrity(RepositoryConnection connection, IRI sense,
            WordnetGraphResolver.ResolvedWordnet wordnet) {
        validateOwnership(connection, sense, wordnet);
    }

    private Ownership validateOwnership(RepositoryConnection connection, IRI sense,
            WordnetGraphResolver.ResolvedWordnet wordnet) {
        Resource graph = wordnet.graph;
        IRI entry = incomingOne(connection, sense, WordnetVocabulary.SENSE_LINK, graph);
        List<IRI> synsets = rdf.iris(connection, sense, WordnetVocabulary.REFERENCE, graph);
        if (entry == null || synsets.size() != 1
                || !connection.hasStatement(entry, RDF.TYPE,
                        WordnetVocabulary.ENTRY, false, graph)
                || !connection.hasStatement(synsets.get(0), RDF.TYPE,
                        WordnetVocabulary.SYNSET, false, graph)
                || !memberList.read(connection, synsets.get(0), graph)
                        .contains(sense))
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Sense must have exactly one typed Entry and Synset membership");
        WordnetEntryManager.requireOwnedEntry(connection, wordnet, entry);
        synsetManager.validateCore(connection, wordnet, synsets.get(0));
        number(connection, sense, graph);
        return new Ownership(entry, synsets.get(0));
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
                            "Sense has multiple owners");
                result = (IRI) subject;
            }
        }
        return result;
    }

    private void requireCompatible(RepositoryConnection connection, IRI entry,
            IRI synset, Resource graph) {
        IRI entryPos = rdf.firstIri(connection, entry,
                WordnetVocabulary.PART_OF_SPEECH, graph);
        IRI synsetPos = rdf.firstIri(connection, synset,
                WordnetVocabulary.PART_OF_SPEECH, graph);
        if (!WordnetEntryManager.compatible(entryPos, synsetPos))
            throw new WordnetException(422, "INCOMPATIBLE_POS",
                    "Entry and Synset parts of speech are incompatible");
    }

    private int nextNumber(RepositoryConnection connection, IRI entry, Resource graph) {
        int max = 0;
        for (IRI sense : rdf.iris(connection, entry, WordnetVocabulary.SENSE_LINK, graph)) {
            Integer value = number(connection, sense, graph);
            if (value != null) max = Math.max(max, value.intValue());
        }
        return max + 1;
    }

    private Integer number(RepositoryConnection connection, IRI sense, Resource graph) {
        List<Literal> values = new ArrayList<Literal>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                sense, WordnetVocabulary.N, null, false, graph)) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (!(value instanceof Literal)) {
                    throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                            "Sense number must be one integer literal");
                }
                values.add((Literal) value);
            }
        }
        if (values.size() != 1
                || !XSD.INTEGER.equals(values.get(0).getDatatype())) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Sense must have exactly one xsd:integer number");
        }
        try {
            int value = values.get(0).intValue();
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return Integer.valueOf(value);
        } catch (RuntimeException ex) {
            throw new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT",
                    "Sense number must be a positive integer");
        }
    }

    private int requirePositive(Integer value, String field) {
        if (value == null || value.intValue() <= 0)
            throw rdf.invalid("INVALID_PATCH", field + " must be positive");
        return value.intValue();
    }

    private void requireUniqueNumber(RepositoryConnection connection, IRI entry,
            IRI excluded, int number, Resource graph) {
        for (IRI sense : rdf.iris(connection, entry, WordnetVocabulary.SENSE_LINK, graph)) {
            if (sense.equals(excluded)) continue;
            Integer current = number(connection, sense, graph);
            if (current != null && current.intValue() == number)
                throw new WordnetException(409, "SENSE_NUMBER_CONFLICT",
                        "Sense number is already used by the Entry");
        }
    }

    private List<IRI> validateSubcategories(RepositoryConnection connection, IRI entry,
            List<String> ids, Resource graph) {
        List<IRI> result = new ArrayList<IRI>();
        if (ids == null) return result;
        for (String id : ids) {
            IRI behaviour = rdf.resource(connection, id,
                    WordnetVocabulary.SYNTACTIC_BEHAVIOUR, graph, "CHILD_NOT_FOUND");
            if (!connection.hasStatement(entry, WordnetVocabulary.SYN_BEHAVIOR,
                    behaviour, false, graph))
                throw new WordnetException(422, "INVALID_REFERENCED_ENTITY",
                        "Syntactic behaviour does not belong to the selected Entry");
            if (!result.contains(behaviour)) result.add(behaviour);
        }
        return result;
    }

    private List<IRI> incomingBehaviours(RepositoryConnection connection, IRI sense,
            Resource graph) {
        List<IRI> result = new ArrayList<IRI>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, WordnetVocabulary.SENSE_SUBCAT, sense, false, graph)) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (subject instanceof IRI) result.add((IRI) subject);
            }
        }
        return result;
    }

    private List<IRI> children(RepositoryConnection connection, IRI sense,
            Resource graph) {
        List<IRI> result = new ArrayList<IRI>();
        result.addAll(rdf.iris(connection, sense, WordnetVocabulary.EXAMPLE_LINK, graph));
        result.addAll(rdf.iris(connection, sense, WordnetVocabulary.COUNT_LINK, graph));
        return result;
    }

    private List<Map<String, Object>> childResponses(RepositoryConnection connection,
            WordnetGraphResolver.ResolvedWordnet wordnet, IRI owner, IRI link,
            String type) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (IRI child : rdf.iris(connection, owner, link, wordnet.graph)) {
            Map<String, Object> item = rdf.baseResponse(connection, wordnet.wordnetId,
                    type, child, wordnet.graph);
            org.eclipse.rdf4j.model.Literal literal = rdf.firstLiteral(
                    connection, child, RDF.VALUE, wordnet.graph);
            String value = literal == null ? null : literal.getLabel();
            item.put("count".equals(type) ? "value" : "text",
                    "count".equals(type) && value != null ? Integer.valueOf(value) : value);
            if (!"count".equals(type)) {
                item.put("language", literal == null
                        || !literal.getLanguage().isPresent() ? null
                        : literal.getLanguage().get());
            }
            item.put("metadata", rdf.readMetadata(connection, child, wordnet.graph));
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> relationSummaries(RepositoryConnection connection,
            IRI sense, Resource graph, String direction) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try (RepositoryResult<Statement> relations = connection.getStatements(null,
                RDF.TYPE, WordnetVocabulary.SENSE_RELATION, false, graph)) {
            while (relations.hasNext()) {
                IRI relation = (IRI) relations.next().getSubject();
                IRI source = rdf.firstIri(connection, relation, WordnetVocabulary.SOURCE, graph);
                IRI target = rdf.firstIri(connection, relation, WordnetVocabulary.TARGET, graph);
                if (!sense.equals(source) && !sense.equals(target)) continue;
                String itemDirection = sense.equals(source) ? "out" : "in";
                if (!"both".equals(direction)
                        && !direction.equals(itemDirection)) continue;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", WordnetIdCodec.encode(relation.stringValue()));
                item.put("iri", relation.stringValue());
                item.put("direction", itemDirection);
                item.put("sourceId", WordnetIdCodec.encode(source.stringValue()));
                item.put("targetId", WordnetIdCodec.encode(target.stringValue()));
                IRI category = rdf.firstIri(connection, relation,
                        WordnetVocabulary.RELATION_CATEGORY, graph);
                item.put("category", category == null ? null : category.stringValue());
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

    private String requireLanguage(String language) {
        String value = rdf.required(language, "MISSING_REQUIRED_FIELD", "language is required");
        try {
            new Locale.Builder().setLanguageTag(value);
            if (Locale.forLanguageTag(value).getLanguage().isEmpty()) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException ex) {
            throw rdf.invalid("INVALID_LANGUAGE", "Invalid BCP 47 language tag");
        }
    }

    private IRI ownedChild(RepositoryConnection connection, IRI owner, String id,
            IRI link, IRI type, Resource graph) {
        IRI child = rdf.resource(connection, id, type, graph, "CHILD_NOT_FOUND");
        if (!connection.hasStatement(owner, link, child, false, graph))
            throw new WordnetException(404, "CHILD_NOT_FOUND", "Child is not owned by this Sense");
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
            List<String> ids, IRI link, IRI type, Resource graph) {
        if (ids == null) return;
        for (String id : ids) {
            IRI child = ownedChild(connection, owner, id, link, type, graph);
            rdf.requireExclusiveIncoming(connection, child, owner, graph, link);
            connection.remove(owner, link, child, graph);
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

    private static final class Ownership {
        final IRI entry;
        final IRI synset;
        Ownership(IRI entry, IRI synset) { this.entry = entry; this.synset = synset; }
    }
}
