package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.manager.metadata.MetadataPolicy;
import it.cnr.ilc.lexo.manager.metadata.RdfMetadataCodec;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryListItem;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Retrieves lexical entries with validated, conjunctive advanced filters. */
public final class LexicalEntryListManager implements Manager {

    private static final String LEXINFO =
            "http://www.lexinfo.net/ontology/3.0/lexinfo#";

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final RdfMetadataCodec metadataCodec = new RdfMetadataCodec();
    private final Repository repository;

    /** Runtime constructor used by {@link ManagerFactory}. */
    public LexicalEntryListManager() {
        this(null);
    }

    LexicalEntryListManager(Repository repository) {
        this.repository = repository;
    }

    /** Returns every entry satisfying all supplied filters. */
    public List<LexicalEntryListItem> list(
            String language, String key, String searchMode, String matchCase,
            String type, String pos, String author, String status,
            String senseNumber) {
        ValidatedFilter filter = validate(language, key, searchMode, matchCase,
                type, pos, author, status, senseNumber);
        RepositoryConnection connection = acquire();
        try {
            Resource graph = vf.createIRI(
                    LexiconCrudSupport.lexicalGraphUri(filter.language));
            Resource schemaGraph = vf.createIRI(LexicalNamedGraphs.schemaGraphUri());
            validateType(connection, filter.type, graph, schemaGraph);
            validatePartOfSpeech(connection, filter.pos, graph, schemaGraph);
            List<LexicalEntryListItem> entries = execute(
                    connection, graph, schemaGraph, filter);
            enrich(connection, graph, entries);
            return entries;
        } finally {
            release(connection);
        }
    }

    private List<LexicalEntryListItem> execute(
            RepositoryConnection connection, Resource graph,
            Resource schemaGraph, ValidatedFilter filter) {
        TupleQuery query = connection.prepareTupleQuery(
                QueryLanguage.SPARQL, query(filter));
        query.setBinding("graph", graph);
        query.setBinding("schemaGraph", schemaGraph);
        query.setBinding("languageFilter", vf.createLiteral(filter.language));
        if (filter.key != null) {
            query.setBinding("keyFilter", vf.createLiteral(filter.key));
        }
        if (filter.type != null) {
            query.setBinding("typeFilter", filter.type);
        }
        if (filter.pos != null) {
            query.setBinding("posFilter", filter.pos);
        }
        if (filter.author != null) {
            query.setBinding("authorFilter", vf.createLiteral(filter.author));
        }
        if (filter.status != null) {
            query.setBinding("statusFilter", vf.createLiteral(filter.status));
        }
        if (filter.senseNumber != null) {
            query.setBinding("senseNumberFilter",
                    vf.createLiteral(filter.senseNumber.intValue()));
        }
        List<LexicalEntryListItem> entries =
                new ArrayList<LexicalEntryListItem>();
        try (TupleQueryResult result = query.evaluate()) {
            while (result.hasNext()) {
                BindingSet row = result.next();
                LexicalEntryListItem item = new LexicalEntryListItem();
                item.entry = string(row, "entry");
                item.label = string(row, "label");
                item.type = string(row, "type");
                item.pos = string(row, "pos");
                item.author = string(row, "author");
                item.status = string(row, "status");
                Value count = row.getValue("senseNumber");
                item.senseNumber = count instanceof Literal
                        ? ((Literal) count).intValue() : 0;
                entries.add(item);
            }
        }
        return entries;
    }

    private String query(ValidatedFilter filter) {
        StringBuilder query = new StringBuilder();
        query.append("PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>\n")
                .append("PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\n")
                .append("PREFIX dcterms: <http://purl.org/dc/terms/>\n")
                .append("PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>\n")
                .append("PREFIX lexinfo: <http://www.lexinfo.net/ontology/3.0/lexinfo#>\n")
                .append("PREFIX lime: <http://www.w3.org/ns/lemon/lime#>\n")
                .append("PREFIX lexo: <https://lexo.ilc.cnr.it#>\n")
                .append("SELECT ?entry (MIN(STR(?effectiveLabel)) AS ?label)\n")
                .append("       (MIN(STR(?entryType)) AS ?type)\n")
                .append("       (MIN(STR(?entryPos)) AS ?pos)\n")
                .append("       (MIN(STR(?entryAuthor)) AS ?author)\n")
                .append("       (MIN(STR(?entryStatus)) AS ?status)\n")
                .append("       (COUNT(DISTINCT ?sense) AS ?senseNumber)\n")
                .append("WHERE {\n")
                .append("  GRAPH ?graph {\n")
                .append("    ?lexicon rdf:type lime:Lexicon ; lime:entry ?entry .\n")
                .append("    FILTER(\n")
                .append("      EXISTS { ?lexicon lime:language ?lexiconLanguage . FILTER(LCASE(STR(?lexiconLanguage)) = STR(?languageFilter)) }\n")
                .append("      || EXISTS { ?lexicon dcterms:language ?lexiconLanguage . FILTER(LCASE(STR(?lexiconLanguage)) = STR(?languageFilter)) })\n")
                .append("    ?entry rdf:type ?entryType .\n")
                .append("    FILTER(?entryType = ontolex:LexicalEntry\n")
                .append("      || EXISTS { ?entryType rdfs:subClassOf+ ontolex:LexicalEntry }\n")
                .append("      || EXISTS { GRAPH ?schemaGraph { ?entryType rdfs:subClassOf+ ontolex:LexicalEntry } })\n")
                .append("    OPTIONAL { ?entry rdfs:label ?directLabel . }\n")
                .append("    OPTIONAL {\n")
                .append("      FILTER NOT EXISTS { ?entry rdfs:label ?existingLabel . }\n")
                .append("      ?entry ontolex:canonicalForm ?canonicalForm .\n")
                .append("      ?canonicalForm ontolex:writtenRep ?canonicalLabel .\n")
                .append("    }\n")
                .append("    OPTIONAL {\n")
                .append("      FILTER NOT EXISTS { ?entry rdfs:label ?existingLabel . }\n")
                .append("      FILTER NOT EXISTS { ?entry ontolex:canonicalForm ?existingCanonical . }\n")
                .append("      ?entry ontolex:otherForm ?otherForm .\n")
                .append("      ?otherForm ontolex:writtenRep ?otherLabel .\n")
                .append("    }\n")
                .append("    BIND(COALESCE(?directLabel, ?canonicalLabel, ?otherLabel) AS ?effectiveLabel)\n")
                .append("    OPTIONAL { ?entry lexinfo:partOfSpeech ?entryPos . }\n")
                .append("    OPTIONAL { ?entry dcterms:creator ?entryAuthor . }\n")
                .append("    OPTIONAL { ?entry lexo:status ?entryStatus . }\n")
                .append("    OPTIONAL { ?entry ontolex:sense ?sense . FILTER(isIRI(?sense)) }\n");
        appendFilters(query, filter);
        query.append("  }\n")
                .append("}\n")
                .append("GROUP BY ?entry\n");
        if (filter.senseNumber != null) {
            query.append("HAVING(COUNT(DISTINCT ?sense) = ?senseNumberFilter)\n");
        }
        query.append("ORDER BY LCASE(COALESCE(?label, \"\")) STR(?entry)\n");
        return query.toString();
    }

    private void enrich(RepositoryConnection connection, Resource graph,
                        List<LexicalEntryListItem> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Map<String, EntryEnrichment> enrichment =
                new LinkedHashMap<String, EntryEnrichment>();
        for (LexicalEntryListItem item : entries) {
            enrichment.put(item.entry, new EntryEnrichment());
        }
        IRI senseProperty = vf.createIRI(
                "http://www.w3.org/ns/lemon/ontolex#sense");
        IRI canonicalProperty = vf.createIRI(
                "http://www.w3.org/ns/lemon/ontolex#canonicalForm");
        IRI otherProperty = vf.createIRI(
                "http://www.w3.org/ns/lemon/ontolex#otherForm");
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, null, null, false, graph)) {
            while (statements.hasNext()) {
                Statement statement = statements.next();
                EntryEnrichment target = enrichment.get(
                        statement.getSubject().stringValue());
                if (target == null) {
                    continue;
                }
                IRI predicate = statement.getPredicate();
                Value object = statement.getObject();
                if (senseProperty.equals(predicate)) {
                    addIri(target.senses, object);
                } else if (canonicalProperty.equals(predicate)) {
                    addIri(target.canonicalForms, object);
                } else if (otherProperty.equals(predicate)) {
                    addIri(target.otherForms, object);
                }
                if (!MetadataPolicy.isProtected(predicate.stringValue())
                        && (object instanceof IRI || object instanceof Literal)) {
                    List<Value> values = target.metadata.get(predicate);
                    if (values == null) {
                        values = new ArrayList<Value>();
                        target.metadata.put(predicate, values);
                    }
                    if (!values.contains(object)) {
                        values.add(object);
                    }
                }
            }
        }
        for (LexicalEntryListItem item : entries) {
            EntryEnrichment source = enrichment.get(item.entry);
            item.senses.addAll(source.senses);
            item.senseNumber = item.senses.size();
            item.canonicalFormNumber = source.canonicalForms.size();
            item.canonicalForm = source.canonicalForms.isEmpty()
                    ? null : source.canonicalForms.iterator().next();
            item.otherForms.addAll(source.otherForms);
            item.otherFormNumber = item.otherForms.size();
            item.metadata = metadataCodec.encode(source.metadata);
        }
    }

    private void addIri(Set<String> target, Value value) {
        if (value instanceof IRI) {
            target.add(value.stringValue());
        }
    }

    private void appendFilters(StringBuilder query, ValidatedFilter filter) {
        if (filter.key != null) {
            String label = filter.caseSensitive
                    ? "STR(?effectiveLabel)" : "LCASE(STR(?effectiveLabel))";
            String key = filter.caseSensitive
                    ? "STR(?keyFilter)" : "LCASE(STR(?keyFilter))";
            if ("contains".equals(filter.searchMode)) {
                query.append("    FILTER(CONTAINS(").append(label).append(", ")
                        .append(key).append("))\n");
            } else if ("endsWith".equals(filter.searchMode)) {
                query.append("    FILTER(STRENDS(").append(label).append(", ")
                        .append(key).append("))\n");
            } else {
                query.append("    FILTER(STRSTARTS(").append(label).append(", ")
                        .append(key).append("))\n");
            }
        }
        if (filter.type != null) {
            query.append("    FILTER EXISTS { ?entry rdf:type ?typeFilter . }\n");
        }
        if (filter.pos != null) {
            query.append("    FILTER EXISTS { ?entry lexinfo:partOfSpeech ?posFilter . }\n");
        }
        if (filter.author != null) {
            query.append("    FILTER EXISTS { ?entry dcterms:creator ?authorFilter . }\n");
        }
        if (filter.status != null) {
            query.append("    FILTER EXISTS { ?entry lexo:status ?statusFilter . }\n");
        }
    }

    private ValidatedFilter validate(
            String language, String key, String searchMode, String matchCase,
            String type, String pos, String author, String status,
            String senseNumber) {
        ValidatedFilter filter = new ValidatedFilter();
        filter.language = Iso639LanguageValidator.get().requireValid(language);
        filter.key = optional(key);
        filter.searchMode = optional(searchMode);
        if (filter.searchMode == null) {
            filter.searchMode = "startsWith";
        } else if (!"startsWith".equals(filter.searchMode)
                && !"contains".equals(filter.searchMode)
                && !"endsWith".equals(filter.searchMode)) {
            throw invalid("INVALID_SEARCH_MODE",
                    "searchMode must be startsWith, contains, or endsWith");
        }
        String caseValue = optional(matchCase);
        if (caseValue == null || "sensitive".equals(caseValue)) {
            filter.caseSensitive = true;
        } else if ("insensitive".equals(caseValue)) {
            filter.caseSensitive = false;
        } else {
            throw invalid("INVALID_CASE", "case must be sensitive or insensitive");
        }
        filter.type = optionalIri(type, "type", "INVALID_TYPE_IRI");
        filter.pos = optionalIri(pos, "pos", "INVALID_PART_OF_SPEECH_IRI");
        filter.author = optional(author);
        String statusValue = optional(status);
        filter.status = statusValue == null ? null
                : LexicalWorkflowStatus.require(statusValue, "status").value();
        String senseValue = optional(senseNumber);
        if (senseValue != null) {
            try {
                filter.senseNumber = Integer.valueOf(senseValue);
            } catch (NumberFormatException e) {
                throw invalid("INVALID_SENSE_NUMBER",
                        "senseNumber must be an integer greater than or equal to zero");
            }
            if (filter.senseNumber.intValue() < 0) {
                throw invalid("INVALID_SENSE_NUMBER",
                        "senseNumber must be an integer greater than or equal to zero");
            }
        }
        return filter;
    }

    private void validateType(RepositoryConnection connection, IRI type,
                              Resource graph, Resource schemaGraph) {
        if (type != null && !connection.hasStatement(
                type, null, null, false, graph, schemaGraph)) {
            throw new EntryListException(404, "TYPE_NOT_FOUND: type "
                    + type.stringValue() + " does not exist");
        }
    }

    private void validatePartOfSpeech(RepositoryConnection connection, IRI pos,
                                      Resource graph, Resource schemaGraph) {
        if (pos == null) {
            return;
        }
        IRI partOfSpeech = vf.createIRI(LEXINFO + "PartOfSpeech");
        try (RepositoryResult<Statement> types = connection.getStatements(
                pos, RDF.TYPE, null, false, graph, schemaGraph)) {
            while (types.hasNext()) {
                Value type = types.next().getObject();
                if (type instanceof IRI && LexiconCrudSupport.isSubclassOf(
                        connection, (IRI) type, partOfSpeech, graph, schemaGraph)) {
                    return;
                }
            }
        }
        throw new EntryListException(422, "INVALID_PART_OF_SPEECH: pos "
                + pos.stringValue() + " is not a lexinfo:PartOfSpeech individual");
    }

    private IRI optionalIri(String value, String field, String code) {
        String normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw invalid(code, field + " must be an absolute IRI");
            }
            return vf.createIRI(normalized);
        } catch (URISyntaxException e) {
            throw invalid(code, field + " must be an absolute IRI");
        }
    }

    private String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String string(BindingSet row, String name) {
        Value value = row.getValue(name);
        return value == null ? null : value.stringValue();
    }

    private IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    private RepositoryConnection acquire() {
        return repository == null
                ? GraphDbUtil.getConnection(RepositoryTarget.LEXICON)
                : repository.getConnection();
    }

    private void release(RepositoryConnection connection) {
        if (repository == null) {
            GraphDbUtil.releaseConnection(RepositoryTarget.LEXICON, connection);
        } else {
            connection.close();
        }
    }

    /** Domain failure carrying the intended HTTP response status. */
    public static final class EntryListException extends RuntimeException {
        public final int httpStatus;

        EntryListException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }
    }

    private static final class ValidatedFilter {
        String language;
        String key;
        String searchMode;
        boolean caseSensitive;
        IRI type;
        IRI pos;
        String author;
        String status;
        Integer senseNumber;
    }

    private static final class EntryEnrichment {
        final Set<String> senses = new TreeSet<String>();
        final Set<String> canonicalForms = new TreeSet<String>();
        final Set<String> otherForms = new TreeSet<String>();
        final Map<IRI, List<Value>> metadata =
                new LinkedHashMap<IRI, List<Value>>();
    }
}
