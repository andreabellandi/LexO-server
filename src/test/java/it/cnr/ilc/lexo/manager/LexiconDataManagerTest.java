package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryCore;
import it.cnr.ilc.lexo.sparql.SparqlGraphViz;
import it.cnr.ilc.lexo.sparql.SparqlQueryExpansion;
import it.cnr.ilc.lexo.sparql.SparqlSelectData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LexiconDataManagerTest {

    private final LexiconDataManager manager = new LexiconDataManager();

    /** A missing SPARQL result must be represented as not found, not as an IndexOutOfBoundsException. */
    @Test
    void getLexicalEntityTypesReturnsNullForAnEmptyResult() {
        assertNull(manager.getLexicalEntityTypes(Collections.emptyList()));
    }

    /** A missing form must be represented as not found instead of causing an IndexOutOfBoundsException. */
    @Test
    void getMorphologyInheritanceReturnsNullForAnEmptyResult() {
        assertNull(manager.getMorphologyInheritance(Collections.emptyList()));
    }

    /** Multiple rows for the same entry are collapsed into one entry containing all RDF types. */
    @Test
    void getLexicalEntityTypesMergesTypes() {
        LexicalEntryCore first = entryWithType("LexicalEntry");
        LexicalEntryCore second = entryWithType("Word");

        LexicalEntryCore result = manager.getLexicalEntityTypes(Arrays.asList(first, second));

        assertEquals(Arrays.asList("LexicalEntry", "Word"), result.getType());
    }

    /** The exact-IRI lookup is valid SPARQL and does not depend on the Lucene index. */
    @Test
    void lexicalEntryCoreQueryUsesTheEntryIriDirectly() {
        String query = SparqlSelectData.DATA_LEXICAL_ENTRY_CORE.replace(
                "[IRI]", "<http://lexica/mylexicon#femmina_lexical_entry>");

        QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, query, null);
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("luc:query"));
    }

    /** Link counts for an exact entity IRI also work when the entity is absent from a Lucene index. */
    @Test
    void lexicalEntityLinksQueryUsesTheEntityIriDirectly() {
        String query = SparqlSelectData.DATA_LEXICAL_ENTITY_LINKS.replace(
                "[IRI]", "<http://lexica/mylexicon#femmina_lexical_entry>");

        QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, query, null);
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("luc:query"));
    }

    /** Sense retrieval by lexical-entry ID must not require the entry to be in the Lucene index. */
    @Test
    void lexicalSensesQueryUsesTheEntryIriDirectly() {
        String query = SparqlSelectData.DATA_LEXICAL_SENSES.replace(
                "[IRI]", "<http://lexica/mylexicon#femmina_lexical_entry>");

        QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, query, null);
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("luc:query"));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains(
                "VALUES ?lexicalEntry { <http://lexica/mylexicon#femmina_lexical_entry> }"));
    }

    /** Every detail/children query for a known IRI bypasses label-dependent Lucene indexes. */
    @Test
    void exactIriQueriesDoNotDependOnLuceneIndexes() {
        String iri = "<http://lexica/mylexicon#resource>";
        List<String> queries = Arrays.asList(
                SparqlSelectData.DATA_LEXICAL_ENTRY_ELEMENTS,
                SparqlSelectData.DATA_FORMS_BY_LEXICAL_ENTRY.replace("[FORM_CONSTRAINT]", ""),
                SparqlSelectData.DATA_LEXICAL_SENSES,
                SparqlSelectData.DATA_ETYMOLOGIES,
                SparqlSelectData.DATA_SUBTERMS.replace("_TYPE_", ""),
                SparqlSelectData.DATA_CORRESPONDS_TO.replace("_TYPE_", ""),
                SparqlSelectData.DATA_COMPONENTS,
                SparqlSelectData.DATA_LEXICAL_ENTRY_DIRECT_VARTRANS,
                SparqlSelectData.DATA_LEXICAL_ENTRY_INDIRECT_VARTRANS,
                SparqlSelectData.DATA_FORM_CORE,
                SparqlSelectData.DATA_FORM_DIRECT_VARTRANS,
                SparqlSelectData.DATA_FORM_INDIRECT_VARTRANS,
                SparqlSelectData.DATA_LEXICAL_SENSE_CORE,
                SparqlSelectData.DATA_LEXICAL_SENSE_DIRECT_VARTRANS,
                SparqlSelectData.DATA_LEXICAL_SENSE_INDIRECT_VARTRANS,
                SparqlSelectData.DATA_COMPONENT,
                SparqlSelectData.DATA_ETYMOLOGY,
                SparqlSelectData.DATA_ETYMOLOGY_ETY_LINKS_LIST);

        for (int i = 0; i < queries.size(); i++) {
            String template = queries.get(i);
            String query = template.replace("[IRI]", iri);
            int queryIndex = i;
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, query, null),
                    "Invalid exact-IRI query at index " + queryIndex);
            org.junit.jupiter.api.Assertions.assertFalse(query.contains("luc:query"), query);
        }
    }

    /** Auxiliary form/sense workflows must obey the same exact-IRI lookup rule as data endpoints. */
    @Test
    void auxiliaryExactIriQueriesDoNotDependOnLuceneIndexes() {
        String iri = "<http://lexica/mylexicon#resource>";
        List<String> queries = Arrays.asList(
                SparqlQueryExpansion.QUERY_EXPANSION_FORMS
                        .replace("[LEXICAL_ENTRY_LIST]", iri),
                SparqlQueryExpansion.DATA_FORMS_BY_LEXICAL_SENSE
                        .replace("[IRI]", iri)
                        .replace("[FORM_CONSTRAINT]", ""),
                SparqlGraphViz.GRAPH_VIZ_SENSE_SUMMARY.replace("[IRI]", iri),
                SparqlGraphViz.GRAPH_VIZ_SENSE_LINKS.replace("[IRI]", iri),
                SparqlSelectData.DATA_ECD_MEANING.replace("[IRI]", iri));

        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            int queryIndex = i;
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, query, null),
                    "Invalid auxiliary exact-IRI query at index " + queryIndex);
            org.junit.jupiter.api.Assertions.assertFalse(query.contains("luc:query"), query);
        }
    }

    /** Dictionary-entry details bypass Lucene and provide a label fallback for unlabeled entries. */
    @Test
    void dictionaryEntryDetailQueriesUseTheEntryIriDirectly() {
        String iri = "<http://lexica/mylexicon#dictionary_entry>";
        List<String> queries = Arrays.asList(
                SparqlSelectData.DATA_DICT_ENTRY.replace("[IRI]", iri),
                SparqlSelectData.DATA_ECD_ENTRY.replace("[IRI]", iri));

        for (String query : queries) {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, query, null));
            org.junit.jupiter.api.Assertions.assertFalse(query.contains("luc:query"), query);
            org.junit.jupiter.api.Assertions.assertTrue(
                    query.contains("VALUES ?dictionaryEntry { " + iri + " }"), query);
            org.junit.jupiter.api.Assertions.assertTrue(query.contains("COALESCE(?entryLabel"), query);
        }
    }

    private LexicalEntryCore entryWithType(String type) {
        LexicalEntryCore entry = new LexicalEntryCore();
        entry.setType(new ArrayList<>(Collections.singletonList(type)));
        return entry;
    }
}
