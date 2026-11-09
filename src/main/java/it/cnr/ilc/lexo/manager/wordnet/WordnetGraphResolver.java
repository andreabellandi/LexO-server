package it.cnr.ilc.lexo.manager.wordnet;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Resolves server-owned WordNet named graphs inside LexOWordnets only. */
public final class WordnetGraphResolver {

    private static final Pattern WORDNET_ID =
            Pattern.compile("[a-z][a-z0-9_-]{1,63}");

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final Repository repository;
    private final String base;
    private final IRI schemaGraph;
    private final IRI bootstrapGraph;

    public WordnetGraphResolver() {
        this(null);
    }

    public WordnetGraphResolver(Repository repository) {
        this.repository = repository;
        String configuredBase = property("WordnetGraphDb.namedGraphBase",
                "https://lexo.ilc.cnr.it/graphs/wordnet/");
        this.base = configuredBase.endsWith("/")
                ? configuredBase : configuredBase + "/";
        this.schemaGraph = vf.createIRI(property("WordnetGraphDb.schemaNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/wordnet/schema"));
        this.bootstrapGraph = vf.createIRI(property(
                "Bootstrap.wordnet.metadata.namedGraph",
                "https://lexo.ilc.cnr.it/graphs/wordnet/bootstrap"));
        if (schemaGraph.equals(bootstrapGraph)
                || isApplicationGraph(schemaGraph.stringValue())
                || isApplicationGraph(bootstrapGraph.stringValue())) {
            throw new IllegalStateException(
                    "WordNet schema/bootstrap graphs overlap the application graph family");
        }
    }

    public String graphUri(String wordnetId, String language) {
        String id = requireWordnetId(wordnetId);
        String normalized = Iso639LanguageValidator.get().requireValid(language);
        return base + normalized + "/" + id;
    }

    public ResolvedWordnet resolve(String wordnetId) {
        RepositoryConnection connection = acquire();
        try {
            return resolve(connection, wordnetId);
        } finally {
            release(connection);
        }
    }

    public ResolvedWordnet resolve(RepositoryConnection connection,
            String wordnetId) {
        String id = requireWordnetId(wordnetId);
        List<ResolvedWordnet> matches = new ArrayList<ResolvedWordnet>();
        for (IRI context : contexts(connection)) {
            Candidate candidate = candidate(context);
            if (candidate != null && id.equals(candidate.wordnetId)) {
                matches.add(validateRoot(connection, candidate));
            }
        }
        if (matches.isEmpty()) {
            throw new WordnetException(404, "WORDNET_NOT_FOUND",
                    "WordNet does not exist: " + id);
        }
        if (matches.size() != 1) {
            throw integrity("Duplicate WordNet id: " + id);
        }
        return matches.get(0);
    }

    public List<ResolvedWordnet> list(RepositoryConnection connection) {
        List<ResolvedWordnet> result = new ArrayList<ResolvedWordnet>();
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (IRI context : contexts(connection)) {
            Candidate candidate = candidate(context);
            if (candidate != null) {
                if (!ids.add(candidate.wordnetId)) {
                    throw integrity("Duplicate WordNet id: "
                            + candidate.wordnetId);
                }
                result.add(validateRoot(connection, candidate));
            }
        }
        Collections.sort(result, Comparator.comparing(item -> item.wordnetId));
        return result;
    }

    public void requireAvailableId(RepositoryConnection connection,
            String wordnetId) {
        String id = requireWordnetId(wordnetId);
        for (IRI context : contexts(connection)) {
            Candidate candidate = candidate(context);
            if (candidate != null && id.equals(candidate.wordnetId)) {
                throw new WordnetException(409, "WORDNET_ALREADY_EXISTS",
                        "WordNet id already exists: " + id);
            }
        }
    }

    public IRI schemaGraph() {
        return schemaGraph;
    }

    public String requireWordnetId(String wordnetId) {
        String id = wordnetId == null ? "" : wordnetId.trim();
        if (!WORDNET_ID.matcher(id).matches() || "schema".equals(id)) {
            throw new WordnetException(400, "INVALID_WORDNET_ID",
                    "wordnetId must match [a-z][a-z0-9_-]{1,63} and not be schema");
        }
        return id;
    }

    private ResolvedWordnet validateRoot(RepositoryConnection connection,
            Candidate candidate) {
        List<Resource> assertedRoots = new ArrayList<Resource>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, RDF.TYPE, WordnetVocabulary.LEXICON, false,
                candidate.graph)) {
            while (statements.hasNext()) {
                assertedRoots.add(statements.next().getSubject());
            }
        }
        if (assertedRoots.size() != 1 || !(assertedRoots.get(0) instanceof IRI)) {
            throw integrity("Application graph must contain exactly one lime:Lexicon: "
                    + candidate.graph);
        }
        IRI root = (IRI) assertedRoots.get(0);
        List<Value> languages = new ArrayList<Value>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                root, WordnetVocabulary.LANGUAGE, null, false,
                candidate.graph)) {
            while (statements.hasNext()) {
                languages.add(statements.next().getObject());
            }
        }
        if (languages.size() != 1
                || !(languages.get(0) instanceof org.eclipse.rdf4j.model.Literal)
                || !candidate.language.equals(languages.get(0).stringValue())) {
            throw integrity("WordNet root language does not match graph: "
                    + candidate.graph);
        }
        return new ResolvedWordnet(candidate.wordnetId, candidate.language,
                candidate.graph, root);
    }

    private Candidate candidate(IRI context) {
        String value = context.stringValue();
        if (!isApplicationGraph(value)) {
            return null;
        }
        String tail = value.substring(base.length());
        int slash = tail.indexOf('/');
        if (slash <= 0 || slash != tail.lastIndexOf('/')) {
            return null;
        }
        String language = tail.substring(0, slash);
        String id = tail.substring(slash + 1);
        if (!WORDNET_ID.matcher(id).matches() || "schema".equals(id)) {
            return null;
        }
        try {
            String normalized = Iso639LanguageValidator.get().requireValid(language);
            if (!language.equals(normalized)) {
                return null;
            }
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return new Candidate(id, language, context);
    }

    private boolean isApplicationGraph(String value) {
        if (!value.startsWith(base) || value.equals(schemaGraph == null
                ? "" : schemaGraph.stringValue()) || value.equals(bootstrapGraph == null
                ? "" : bootstrapGraph.stringValue())) {
            return false;
        }
        String tail = value.substring(base.length());
        return tail.indexOf('/') > 0 && tail.indexOf('/') == tail.lastIndexOf('/');
    }

    private List<IRI> contexts(RepositoryConnection connection) {
        List<IRI> contexts = new ArrayList<IRI>();
        try (RepositoryResult<Resource> result = connection.getContextIDs()) {
            while (result.hasNext()) {
                Resource context = result.next();
                if (context instanceof IRI) {
                    contexts.add((IRI) context);
                }
            }
        }
        return contexts;
    }

    private RepositoryConnection acquire() {
        return repository == null
                ? GraphDbUtil.getConnection(RepositoryTarget.WORDNET)
                : repository.getConnection();
    }

    private void release(RepositoryConnection connection) {
        if (repository == null) {
            GraphDbUtil.releaseConnection(RepositoryTarget.WORDNET, connection);
        } else {
            connection.close();
        }
    }

    private String property(String key, String fallback) {
        String value = LexOProperties.getProperty(key);
        return value == null || value.trim().isEmpty() || value.contains("${")
                ? fallback : value.trim();
    }

    private WordnetException integrity(String message) {
        return new WordnetException(409, "GRAPH_INTEGRITY_CONFLICT", message);
    }

    private static final class Candidate {
        final String wordnetId;
        final String language;
        final IRI graph;

        Candidate(String wordnetId, String language, IRI graph) {
            this.wordnetId = wordnetId;
            this.language = language;
            this.graph = graph;
        }
    }

    public static final class ResolvedWordnet {
        public final String wordnetId;
        public final String language;
        public final IRI graph;
        public final IRI root;

        ResolvedWordnet(String wordnetId, String language, IRI graph, IRI root) {
            this.wordnetId = wordnetId;
            this.language = language;
            this.graph = graph;
            this.root = root;
        }
    }
}
