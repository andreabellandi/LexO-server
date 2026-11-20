package it.cnr.ilc.lexo.manager.lexint;

import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.ENTITY;
import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.LANGUAGE;
import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.VERSION_STATE;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Prevents direct writers from bypassing an initialized managed language. */
public final class LexIntGraphGuard {
    private final ValueFactory vf = SimpleValueFactory.getInstance();

    public void requireDirectWriteAllowed(RepositoryConnection connection,
            String language) {
        if (!enabled("LexInt.enabled")
                || !enabled("LexInt.managedPublication.enabled")) {
            return;
        }
        String canonical = Iso639LanguageValidator.get().requireValid(language);
        IRI auditGraph = vf.createIRI(LexOProperties.getProperty(
                "GraphDb.lexintAuditNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/audit"));
        try (RepositoryResult<org.eclipse.rdf4j.model.Statement> versions =
                connection.getStatements(null, RDF.TYPE, ENTITY, false,
                        auditGraph)) {
            while (versions.hasNext()) {
                org.eclipse.rdf4j.model.Resource version =
                        versions.next().getSubject();
                if (connection.hasStatement(version, LANGUAGE,
                        vf.createLiteral(canonical), false, auditGraph)
                        && connection.hasStatement(version, VERSION_STATE,
                                vf.createLiteral("CURRENT"), false,
                                auditGraph)) {
                    throw new ManagedWriteException(canonical);
                }
            }
        }
    }

    private boolean enabled(String property) {
        return "true".equalsIgnoreCase(LexOProperties.getProperty(property,
                "false").trim());
    }

    /** Domain failure exposed as a conflict by existing lexical resources. */
    public static final class ManagedWriteException extends RuntimeException {
        public final int httpStatus = 409;
        public final String code = "LEXINT_MANAGED_WRITE_REQUIRED";

        ManagedWriteException(String language) {
            super("LEXINT_MANAGED_WRITE_REQUIRED: language " + language
                    + " is managed; use the LexInt publication workflow");
        }
    }
}
