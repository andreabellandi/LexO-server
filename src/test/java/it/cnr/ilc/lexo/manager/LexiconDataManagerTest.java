package it.cnr.ilc.lexo.manager;

import it.cnr.ilc.lexo.service.data.lexicon.output.LexicalEntryCore;
import it.cnr.ilc.lexo.sparql.SparqlSelectData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

    private LexicalEntryCore entryWithType(String type) {
        LexicalEntryCore entry = new LexicalEntryCore();
        entry.setType(new ArrayList<>(Collections.singletonList(type)));
        return entry;
    }
}
