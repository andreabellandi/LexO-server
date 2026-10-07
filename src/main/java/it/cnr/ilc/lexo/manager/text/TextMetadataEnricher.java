package it.cnr.ilc.lexo.manager.text;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.service.data.text.output.TextOccurrence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.helpers.NTriplesUtil;

/** Standard SPARQL 1.1 VALUES enrichment for Lucene occurrence hits. */
public final class TextMetadataEnricher {

    private static final TextMetadataEnricher INSTANCE = new TextMetadataEnricher();
    private static final int BATCH_SIZE = 100;

    public static TextMetadataEnricher get() {
        return INSTANCE;
    }

    private TextMetadataEnricher() {
    }

    public void enrich(List<TextOccurrence> occurrences) {
        Map<String, List<TextOccurrence>> byContext =
                new LinkedHashMap<String, List<TextOccurrence>>();
        for (TextOccurrence occurrence : occurrences) {
            byContext.computeIfAbsent(occurrence.contextIRI,
                    key -> new ArrayList<TextOccurrence>()).add(occurrence);
        }
        List<String> contexts = new ArrayList<String>(byContext.keySet());
        RepositoryConnection connection = GraphDbUtil.getConnection(RepositoryTarget.TEXT);
        try {
            for (int offset = 0; offset < contexts.size(); offset += BATCH_SIZE) {
                int end = Math.min(contexts.size(), offset + BATCH_SIZE);
                query(connection, contexts.subList(offset, end), byContext);
            }
        } finally {
            GraphDbUtil.releaseConnection(RepositoryTarget.TEXT, connection);
        }
    }

    private void query(RepositoryConnection connection, List<String> contexts,
                       Map<String, List<TextOccurrence>> byContext) {
        StringBuilder sparql = new StringBuilder();
        sparql.append("SELECT ?context ?predicate ?value WHERE { VALUES ?context { ");
        for (String context : contexts) {
            sparql.append(NTriplesUtil.toNTriplesString(
                    SimpleValueFactory.getInstance().createIRI(context))).append(' ');
        }
        sparql.append("} VALUES ?predicate { ")
                .append("<http://purl.org/dc/terms/identifier> ")
                .append("<http://purl.org/dc/terms/title> ")
                .append("<http://purl.org/dc/terms/creator> ")
                .append("<http://purl.org/dc/terms/created> ")
                .append("<http://purl.org/dc/terms/description> ")
                .append("<http://purl.org/dc/terms/language> ")
                .append("<http://purl.org/dc/terms/format> ")
                .append("<http://purl.org/dc/terms/isPartOf> }")
                .append(" ?context ?predicate ?value . }");
        try (TupleQueryResult result = connection.prepareTupleQuery(
                QueryLanguage.SPARQL, sparql.toString()).evaluate()) {
            while (result.hasNext()) {
                BindingSet row = result.next();
                if (row.getValue("context") == null || row.getValue("predicate") == null
                        || row.getValue("value") == null) {
                    continue;
                }
                List<TextOccurrence> targets = byContext.get(
                        row.getValue("context").stringValue());
                if (targets == null) {
                    continue;
                }
                for (TextOccurrence occurrence : targets) {
                    if (occurrence.metadata == null) {
                        occurrence.metadata =
                                new LinkedHashMap<String, List<String>>();
                    }
                    occurrence.metadata.computeIfAbsent(
                            row.getValue("predicate").stringValue(),
                            key -> new ArrayList<String>())
                            .add(row.getValue("value").stringValue());
                }
            }
        }
    }
}
