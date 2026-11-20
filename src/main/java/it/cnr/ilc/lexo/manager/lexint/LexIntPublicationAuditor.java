package it.cnr.ilc.lexo.manager.lexint;

import it.cnr.ilc.lexo.service.data.lexint.output.TermDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.TripleDetails;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.util.Models;

/** Computes a historical RDF delta from immutable source/target snapshots. */
public final class LexIntPublicationAuditor {
    public Result compare(Model expected, Model observed) {
        Result result = new Result();
        if (expected == null || observed == null) {
            result.outcome = "INDETERMINATE";
            result.errorCodes.add("LEXINT_AUDIT_INPUT_UNAVAILABLE");
            return result;
        }
        if (Models.isomorphic(expected, observed)) {
            result.outcome = "PASS";
            return result;
        }
        Model expectedBlank = blankNodeStatements(expected);
        Model observedBlank = blankNodeStatements(observed);
        if (!Models.isomorphic(expectedBlank, observedBlank)) {
            // A false ground diff is worse than an explicit inability to map
            // blank-node components. Ground triples are still compared when
            // the blank-node subgraphs are isomorphic.
            result.outcome = "INDETERMINATE";
            result.errorCodes.add(
                    "LEXINT_BNODE_COMPARISON_INDETERMINATE");
            return result;
        }
        result.outcome = "FAIL";
        List<Statement> missing = new ArrayList<Statement>();
        List<Statement> unexpected = new ArrayList<Statement>();
        for (Statement statement : groundStatements(expected)) {
            if (!observed.contains(statement.getSubject(),
                    statement.getPredicate(), statement.getObject())) {
                missing.add(statement);
            }
        }
        for (Statement statement : groundStatements(observed)) {
            if (!expected.contains(statement.getSubject(),
                    statement.getPredicate(), statement.getObject())) {
                unexpected.add(statement);
            }
        }
        result.missingCount = missing.size();
        result.unexpectedCount = unexpected.size();
        Comparator<Statement> order = new Comparator<Statement>() {
            @Override public int compare(Statement left, Statement right) {
                return left.toString().compareTo(right.toString());
            }
        };
        java.util.Collections.sort(missing, order);
        java.util.Collections.sort(unexpected, order);
        int maximum = LexIntResourceUris.limit(
                "LexInt.audit.maxDifferenceItems", 100);
        append(result.missing, missing, maximum);
        append(result.unexpected, unexpected, maximum);
        return result;
    }

    private Model blankNodeStatements(Model source) {
        Model result = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        for (Statement statement : source) {
            if (statement.getSubject() instanceof BNode
                    || statement.getObject() instanceof BNode) {
                result.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject());
            }
        }
        return result;
    }

    private Model groundStatements(Model source) {
        Model result = new org.eclipse.rdf4j.model.impl.LinkedHashModel();
        for (Statement statement : source) {
            if (!(statement.getSubject() instanceof BNode)
                    && !(statement.getObject() instanceof BNode)) {
                result.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject());
            }
        }
        return result;
    }

    private void append(List<TripleDetails> target, List<Statement> source,
            int maximum) {
        for (int index = 0; index < source.size() && index < maximum; index++) {
            Statement statement = source.get(index);
            TripleDetails triple = new TripleDetails();
            triple.subject = term(statement.getSubject());
            triple.predicate = term(statement.getPredicate());
            triple.object = term(statement.getObject());
            target.add(triple);
        }
    }

    private TermDetails term(Value value) {
        TermDetails term = new TermDetails();
        term.value = value.stringValue();
        if (value instanceof IRI) {
            term.type = "IRI";
        } else if (value instanceof BNode) {
            term.type = "BNODE";
        } else if (value instanceof Literal) {
            term.type = "LITERAL";
            Literal literal = (Literal) value;
            if (literal.getLanguage().isPresent()) {
                term.lang = literal.getLanguage().get();
            } else if (literal.getDatatype() != null) {
                term.datatype = literal.getDatatype().stringValue();
            }
        }
        return term;
    }

    public static final class Result {
        public String outcome;
        public long missingCount;
        public long unexpectedCount;
        public final List<TripleDetails> missing =
                new ArrayList<TripleDetails>();
        public final List<TripleDetails> unexpected =
                new ArrayList<TripleDetails>();
        public final List<String> errorCodes = new ArrayList<String>();
    }
}
