package it.cnr.ilc.lexo.manager.wordnet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Strict RDF-list handling for ordered Synset membership. */
final class WordnetMemberList {

    private final ValueFactory vf = SimpleValueFactory.getInstance();

    List<IRI> read(RepositoryConnection connection, IRI synset, Resource graph) {
        List<Value> heads = objects(connection, synset,
                WordnetVocabulary.MEMBER_LIST, graph);
        if (heads.size() != 1 || !(heads.get(0) instanceof Resource)) {
            throw integrity("Synset must have exactly one wn:memberList");
        }
        Resource current = (Resource) heads.get(0);
        List<IRI> members = new ArrayList<IRI>();
        Set<String> cells = new HashSet<String>();
        Set<String> items = new HashSet<String>();
        while (!RDF.NIL.equals(current)) {
            if (!cells.add(current.stringValue())) {
                throw integrity("Cyclic wn:memberList");
            }
            List<Value> first = objects(connection, current, RDF.FIRST, graph);
            List<Value> rest = objects(connection, current, RDF.REST, graph);
            if (first.size() != 1 || !(first.get(0) instanceof IRI)
                    || rest.size() != 1 || !(rest.get(0) instanceof Resource)) {
                throw integrity("Broken wn:memberList cell");
            }
            IRI member = (IRI) first.get(0);
            if (!items.add(member.stringValue())) {
                throw integrity("Duplicate Sense in wn:memberList");
            }
            members.add(member);
            current = (Resource) rest.get(0);
        }
        return members;
    }

    void rebuild(RepositoryConnection connection, IRI synset, List<IRI> members,
            Resource graph) {
        removeOldCells(connection, synset, graph);
        if (members.isEmpty()) {
            connection.add(synset, WordnetVocabulary.MEMBER_LIST, RDF.NIL, graph);
            return;
        }
        Resource head = vf.createBNode();
        connection.add(synset, WordnetVocabulary.MEMBER_LIST, head, graph);
        Resource current = head;
        for (int index = 0; index < members.size(); index++) {
            connection.add(current, RDF.FIRST, members.get(index), graph);
            Resource rest = index + 1 == members.size()
                    ? RDF.NIL : vf.createBNode();
            connection.add(current, RDF.REST, rest, graph);
            current = rest;
        }
    }

    private void removeOldCells(RepositoryConnection connection, IRI synset,
            Resource graph) {
        List<Value> heads = objects(connection, synset,
                WordnetVocabulary.MEMBER_LIST, graph);
        if (heads.size() > 1) {
            throw integrity("Synset has multiple wn:memberList heads");
        }
        if (heads.isEmpty()) {
            return;
        }
        if (RDF.NIL.equals(heads.get(0))) {
            connection.remove(synset, WordnetVocabulary.MEMBER_LIST, RDF.NIL,
                    graph);
            return;
        }
        if (!(heads.get(0) instanceof Resource)) {
            throw integrity("Invalid wn:memberList head");
        }
        Resource current = (Resource) heads.get(0);
        Resource expectedOwner = synset;
        IRI expectedPredicate = WordnetVocabulary.MEMBER_LIST;
        Set<String> visited = new HashSet<String>();
        List<Resource> cells = new ArrayList<Resource>();
        while (!RDF.NIL.equals(current)) {
            if (!(current instanceof BNode) || !visited.add(current.stringValue())) {
                throw integrity("Unsafe or cyclic wn:memberList cell");
            }
            List<Value> rest = objects(connection, current, RDF.REST, graph);
            List<Value> first = objects(connection, current, RDF.FIRST, graph);
            if (first.size() != 1 || rest.size() != 1
                    || !(rest.get(0) instanceof Resource)) {
                throw integrity("Broken wn:memberList cell");
            }
            if (!hasExclusiveIncoming(connection, current, expectedOwner,
                    expectedPredicate, graph)) {
                throw integrity("Shared RDF list cells cannot be rewritten");
            }
            Resource next = (Resource) rest.get(0);
            cells.add(current);
            expectedOwner = current;
            expectedPredicate = RDF.REST;
            current = next;
        }
        connection.remove(synset, WordnetVocabulary.MEMBER_LIST, heads.get(0),
                graph);
        for (Resource cell : cells) {
            connection.remove(cell, null, null, graph);
        }
    }

    private boolean hasExclusiveIncoming(RepositoryConnection connection,
            Resource cell, Resource expectedOwner, IRI expectedPredicate,
            Resource graph) {
        int incoming = 0;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, null, cell, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                incoming++;
                if (!expectedOwner.equals(statement.getSubject())
                        || !expectedPredicate.equals(statement.getPredicate())) {
                    return false;
                }
            }
        }
        return incoming == 1;
    }

    private List<Value> objects(RepositoryConnection connection, Resource subject,
            IRI predicate, Resource graph) {
        List<Value> result = new ArrayList<Value>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, graph)) {
            while (statements.hasNext()) {
                result.add(statements.next().getObject());
            }
        }
        return result;
    }

    private WordnetException integrity(String message) {
        return new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT", message);
    }
}
