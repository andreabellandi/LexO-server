package it.cnr.ilc.lexo.manager.wordnet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Read-only WordNet API-profile and category projection. */
public final class WordnetSchemaManager extends AbstractWordnetManager {

    private static final String PROFILE_BASE =
            "https://lexo.ilc.cnr.it/vocabulary/wordnet/";
    private static final IRI PROFILE = SimpleValueFactory.getInstance().createIRI(
            "https://lexo.ilc.cnr.it/profiles/wordnet/1.4");

    public WordnetSchemaManager() {
        this(null);
    }

    public WordnetSchemaManager(Repository repository) {
        super(repository);
    }

    public Map<String, Object> get() {
        RepositoryConnection connection = acquire();
        try {
            Resource graph = resolver.schemaGraph();
            String version = rdf.first(connection, PROFILE, iri("profileVersion"),
                    graph);
            IRI namespace = rdf.firstIri(connection, PROFILE, iri("namespace"),
                    graph);
            String source = rdf.first(connection, PROFILE, iri("turtleSource"),
                    graph);
            String checksum = rdf.first(connection, PROFILE, iri("turtleSha256"),
                    graph);
            if (version == null || namespace == null || source == null
                    || checksum == null) {
                throw new IllegalStateException(
                        "WordNet API profile provenance is missing from the schema graph");
            }
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("version", version);
            result.put("namespace", namespace.stringValue());
            result.put("source", source);
            result.put("sha256", checksum);
            result.put("partOfSpeech", categories(connection,
                    WordnetVocabulary.POS_TYPE));
            result.put("synsetRelations", categories(connection,
                    WordnetVocabulary.SYNSET_REL_TYPE));
            result.put("senseRelations", categories(connection,
                    WordnetVocabulary.SENSE_REL_TYPE));
            return result;
        } finally {
            release(connection);
        }
    }

    private static IRI iri(String localName) {
        return SimpleValueFactory.getInstance().createIRI(PROFILE_BASE + localName);
    }

    private List<Map<String, Object>> categories(RepositoryConnection connection,
            IRI type) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        try (RepositoryResult<Statement> statements = connection.getStatements(null,
                RDF.TYPE, type, false, resolver.schemaGraph())) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (!(subject instanceof IRI)) continue;
                IRI iri = (IRI) subject;
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("iri", iri.stringValue());
                item.put("name", iri.stringValue().startsWith(WordnetVocabulary.WN_NS)
                        ? "wn:" + iri.stringValue().substring(
                                WordnetVocabulary.WN_NS.length()) : iri.stringValue());
                item.put("label", english(connection, iri, RDFS.LABEL));
                item.put("comment", english(connection, iri, RDFS.COMMENT));
                result.add(item);
            }
        }
        result.sort((left, right) -> ((String) left.get("iri"))
                .compareTo((String) right.get("iri")));
        return result;
    }

    private String english(RepositoryConnection connection, IRI subject,
            IRI predicate) {
        String fallback = null;
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, resolver.schemaGraph())) {
            while (statements.hasNext()) {
                Value value = statements.next().getObject();
                if (!(value instanceof Literal)) continue;
                Literal literal = (Literal) value;
                if (literal.getLanguage().isPresent()
                        && "en".equals(literal.getLanguage().get())) return literal.getLabel();
                if (fallback == null) fallback = literal.getLabel();
            }
        }
        return fallback;
    }
}
