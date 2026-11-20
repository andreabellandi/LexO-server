package it.cnr.ilc.lexo.manager.lexint;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/** Central LexInt, Publication, PROV and private ledger terms. */
public final class LexIntVocabulary {
    public static final String LEXINT_NS =
            "https://lexo.ilc.cnr.it/vocabulary/lexical-interpretation#";
    public static final String PUBLICATION_NS =
            "https://ilc.cnr.it/klab/ontology/lexint/publication#";
    public static final String PROV_NS = "http://www.w3.org/ns/prov#";
    public static final String LEDGER_NS =
            "https://lexo.ilc.cnr.it/vocabulary/lexint-management#";
    public static final String RDF_REIFIES =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies";

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    public static final IRI INTERPRETIVE_CLAIM = lexint("InterpretiveClaim");
    public static final IRI TARGET_ASSERTION = lexint("TargetAssertion");
    public static final IRI LEXICOGRAPHIC_DECISION = lexint("LexicographicDecision");
    public static final IRI TARGET_ASSERTION_PROPERTY = lexint("targetAssertion");
    public static final IRI CONCERNS_CLAIM = lexint("concernsClaim");
    public static final IRI DECISION_OUTCOME = lexint("decisionOutcome");
    public static final IRI APPLIES_TO_VERSION = lexint("appliesToVersion");
    public static final IRI ADOPTED = lexint("adopted");
    public static final IRI REJECTED = lexint("rejected");
    public static final IRI SUSPENDED = lexint("suspended");
    public static final IRI REPLACED = lexint("replaced");

    public static final IRI PUBLICATION = publication("Publication");
    public static final IRI OPERATION = publication("Operation");
    public static final IRI INSERTION = publication("Insertion");
    public static final IRI DELETION = publication("Deletion");
    public static final IRI VERIFICATION = publication("Verification");
    public static final IRI AUDIT_REPORT = publication("AuditReport");
    public static final IRI HAS_OPERATION = publication("hasOperation");
    public static final IRI PUB_TARGET_ASSERTION = publication("targetAssertion");
    public static final IRI AUTHORIZED_BY = publication("authorizedBy");
    public static final IRI SOURCE_VERSION = publication("sourceVersion");
    public static final IRI TARGET_VERSION = publication("targetVersion");
    public static final IRI VERIFIES_PUBLICATION = publication("verifiesPublication");
    public static final IRI HAS_REPORT = publication("hasReport");
    public static final IRI USERNAME = publication("username");
    public static final IRI HAS_OUTCOME = publication("hasOutcome");
    public static final IRI MISSING_COUNT = publication("missingTripleCount");
    public static final IRI UNEXPECTED_COUNT = publication("unexpectedTripleCount");
    public static final IRI PASS = publication("pass");
    public static final IRI FAIL = publication("fail");
    public static final IRI INDETERMINATE = publication("indeterminate");

    public static final IRI AGENT = prov("Agent");
    public static final IRI ENTITY = prov("Entity");
    public static final IRI WAS_ASSOCIATED_WITH = prov("wasAssociatedWith");
    public static final IRI WAS_ATTRIBUTED_TO = prov("wasAttributedTo");
    public static final IRI WAS_DERIVED_FROM = prov("wasDerivedFrom");
    public static final IRI USED = prov("used");
    public static final IRI WAS_GENERATED_BY = prov("wasGeneratedBy");
    public static final IRI WAS_REVISION_OF = prov("wasRevisionOf");
    public static final IRI STARTED_AT_TIME = prov("startedAtTime");
    public static final IRI ENDED_AT_TIME = prov("endedAtTime");

    public static final IRI VERSION_CODE = ledger("versionCode");
    public static final IRI LANGUAGE = ledger("language");
    public static final IRI SNAPSHOT_GRAPH = ledger("snapshotGraph");
    public static final IRI VERSION_STATE = ledger("versionState");
    public static final IRI ETAG = ledger("etag");
    public static final IRI PAYLOAD_HASH = ledger("payloadHash");
    public static final IRI IDEMPOTENCY_HASH = ledger("idempotencyHash");
    public static final IRI IDENTITY_ASSURANCE = ledger("identityAssurance");
    public static final IRI OPERATION_INDEX = ledger("operationIndex");
    public static final IRI AUDIT_SCOPE = ledger("auditScope");
    public static final IRI CHECKED_ACTIVE = ledger("checkedActive");

    public static IRI lexint(String local) { return VF.createIRI(LEXINT_NS, local); }
    public static IRI publication(String local) { return VF.createIRI(PUBLICATION_NS, local); }
    public static IRI prov(String local) { return VF.createIRI(PROV_NS, local); }
    public static IRI ledger(String local) { return VF.createIRI(LEDGER_NS, local); }

    private LexIntVocabulary() { }
}
