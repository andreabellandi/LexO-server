package it.cnr.ilc.lexo.manager;

import static it.cnr.ilc.lexo.manager.lexint.LexIntVocabulary.*;
import it.cnr.ilc.lexo.manager.lexint.LexIntActor;
import it.cnr.ilc.lexo.manager.lexint.LexIntException;
import it.cnr.ilc.lexo.manager.lexint.LexIntMetadataWriter;
import it.cnr.ilc.lexo.manager.lexint.LexIntRepository;
import it.cnr.ilc.lexo.manager.lexint.LexIntResourceUris;
import it.cnr.ilc.lexo.manager.lexint.LexIntShaclValidator;
import it.cnr.ilc.lexo.service.data.lexint.input.ClaimCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.ClaimPatchRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.DecisionCreateRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.DecisionPatchRequest;
import it.cnr.ilc.lexo.service.data.lexint.input.TargetInput;
import it.cnr.ilc.lexo.service.data.lexint.input.TargetObjectInput;
import it.cnr.ilc.lexo.service.data.lexint.output.ClaimDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.DecisionDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.LexIntPage;
import it.cnr.ilc.lexo.service.data.lexint.output.TargetDetails;
import it.cnr.ilc.lexo.service.data.lexint.output.TermDetails;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Transactional claim, target and decision management in LexOLexica. */
public final class LexIntManager implements Manager {
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final LexIntRepository repository;
    private final LexIntResourceUris uris;
    private final LexIntMetadataWriter metadata;
    private final LexIntShaclValidator shacl;

    public LexIntManager() {
        this(new LexIntRepository(), new LexIntResourceUris(),
                new LexIntMetadataWriter());
    }

    public LexIntManager(Repository rdfRepository) {
        this(new LexIntRepository(rdfRepository), new LexIntResourceUris(),
                new LexIntMetadataWriter());
    }

    public LexIntManager(LexIntRepository repository, LexIntResourceUris uris,
            LexIntMetadataWriter metadata) {
        this.repository = repository;
        this.uris = uris;
        this.metadata = metadata;
        this.shacl = new LexIntShaclValidator(repository);
    }

    public ClaimDetails createClaim(final ClaimCreateRequest request,
            final LexIntActor actor) {
        final ValidatedClaim input = validateClaim(request);
        return repository.write(new LexIntRepository.Work<ClaimDetails>() {
            @Override public ClaimDetails run(RepositoryConnection connection) {
                List<IRI> focus = new ArrayList<IRI>();
                IRI agent = ensureAgent(connection, actor, focus);
                IRI claim = uris.mint("claim");
                connection.add(claim, RDF.TYPE, INTERPRETIVE_CLAIM,
                        repository.recordsGraph);
                connection.add(claim, DCTERMS.DESCRIPTION,
                        vf.createLiteral(input.description), repository.recordsGraph);
                connection.add(claim, WAS_ATTRIBUTED_TO, agent,
                        repository.recordsGraph);
                for (IRI evidence : input.evidence) {
                    connection.add(claim, WAS_DERIVED_FROM, evidence,
                            repository.recordsGraph);
                }
                metadata.createIndividual(connection, claim, actor,
                        repository.recordsGraph);
                focus.add(claim);
                for (ValidatedTarget item : input.targets) {
                    IRI target = uris.mint("target");
                    addTargetStatements(connection, claim, target, item, agent,
                            actor);
                    focus.add(target);
                }
                shacl.validate(connection, focus);
                ClaimDetails details = claimDetails(connection, claim,
                        actor.identityAssurance);
                details.identityAssurance = actor.identityAssurance;
                return details;
            }
        });
    }

    public ClaimDetails getClaim(String id) {
        final IRI claim = uris.resource("claim", id);
        return repository.read(new LexIntRepository.Work<ClaimDetails>() {
            @Override public ClaimDetails run(RepositoryConnection connection) {
                requireType(connection, claim, INTERPRETIVE_CLAIM,
                        repository.recordsGraph);
                return claimDetails(connection, claim, null);
            }
        });
    }

    public LexIntPage<ClaimDetails> listClaims(final String entryIri,
            final String targetIri, final String creator,
            final Boolean hasDecision, final int limit, final int offset) {
        pagination(limit, offset);
        final IRI entry = blank(entryIri) ? null : inputIri(entryIri, "entryIri");
        final IRI target = blank(targetIri) ? null
                : inputIri(targetIri, "targetIri");
        return repository.read(new LexIntRepository.Work<LexIntPage<ClaimDetails>>() {
            @Override public LexIntPage<ClaimDetails> run(
                    RepositoryConnection connection) {
                List<ClaimDetails> matches = new ArrayList<ClaimDetails>();
                try (RepositoryResult<Statement> rows = connection.getStatements(
                        null, RDF.TYPE, INTERPRETIVE_CLAIM, false,
                        repository.recordsGraph)) {
                    while (rows.hasNext()) {
                        Resource subject = rows.next().getSubject();
                        if (!(subject instanceof IRI)) { continue; }
                        IRI claim = (IRI) subject;
                        if (target != null && !connection.hasStatement(claim,
                                TARGET_ASSERTION_PROPERTY, target, false,
                                repository.recordsGraph)) { continue; }
                        if (entry != null && !claimHasSubject(connection, claim,
                                entry)) { continue; }
                        if (!blank(creator) && !connection.hasStatement(claim,
                                DCTERMS.CREATOR, vf.createLiteral(creator), false,
                                repository.recordsGraph)) { continue; }
                        boolean decision = connection.hasStatement(null,
                                CONCERNS_CLAIM, claim, false,
                                repository.recordsGraph);
                        if (hasDecision != null
                                && hasDecision.booleanValue() != decision) {
                            continue;
                        }
                        matches.add(claimDetails(connection, claim, null));
                    }
                }
                Collections.sort(matches, new Comparator<ClaimDetails>() {
                    @Override public int compare(ClaimDetails left,
                            ClaimDetails right) {
                        return left.iri.compareTo(right.iri);
                    }
                });
                return page(matches, limit, offset);
            }
        });
    }

    public ClaimDetails patchClaim(final String id,
            final ClaimPatchRequest request, final String ifMatch,
            final LexIntActor actor) {
        if (request == null || request.description == null
                && request.evidenceIris == null) {
            throw LexIntException.invalid(
                    "description or evidenceIris must be supplied");
        }
        final String description = request.description == null ? null
                : requiredText(request.description, "description");
        final List<IRI> evidence = request.evidenceIris == null ? null
                : iris(request.evidenceIris, "evidenceIris");
        final IRI claim = uris.resource("claim", id);
        return repository.write(new LexIntRepository.Work<ClaimDetails>() {
            @Override public ClaimDetails run(RepositoryConnection connection) {
                requireEditableClaim(connection, claim);
                requireMatch(connection, claim, ifMatch);
                if (description != null) {
                    connection.remove(claim, DCTERMS.DESCRIPTION, null,
                            repository.recordsGraph);
                    connection.add(claim, DCTERMS.DESCRIPTION,
                            vf.createLiteral(description), repository.recordsGraph);
                }
                if (evidence != null) {
                    connection.remove(claim, WAS_DERIVED_FROM, null,
                            repository.recordsGraph);
                    for (IRI item : evidence) {
                        connection.add(claim, WAS_DERIVED_FROM, item,
                                repository.recordsGraph);
                    }
                }
                metadata.touch(connection, claim, actor,
                        repository.recordsGraph);
                shacl.validate(connection, Collections.singletonList(claim));
                return claimDetails(connection, claim,
                        actor.identityAssurance);
            }
        });
    }

    public void deleteClaim(final String id) {
        final IRI claim = uris.resource("claim", id);
        repository.write(new LexIntRepository.Work<Void>() {
            @Override public Void run(RepositoryConnection connection) {
                requireEditableClaim(connection, claim);
                if (connection.hasStatement(null, CONCERNS_CLAIM, claim,
                        false, repository.recordsGraph)) {
                    throw LexIntException.immutable(
                            "A claim referenced by a decision cannot be deleted");
                }
                List<IRI> targets = targetIris(connection, claim);
                connection.remove(claim, null, null, repository.recordsGraph);
                connection.remove((Resource) null, null, claim,
                        repository.recordsGraph);
                for (IRI target : targets) {
                    if (!connection.hasStatement(null,
                            TARGET_ASSERTION_PROPERTY, target, false,
                            repository.recordsGraph)) {
                        connection.remove(target, null, null,
                                repository.recordsGraph);
                    }
                }
                return null;
            }
        });
    }

    public TargetDetails addTarget(final String claimId,
            final TargetInput request, final LexIntActor actor) {
        final IRI claim = uris.resource("claim", claimId);
        final ValidatedTarget input = validateTarget(request);
        return repository.write(new LexIntRepository.Work<TargetDetails>() {
            @Override public TargetDetails run(RepositoryConnection connection) {
                requireEditableClaim(connection, claim);
                List<IRI> focus = new ArrayList<IRI>();
                IRI agent = ensureAgent(connection, actor, focus);
                IRI target = uris.mint("target");
                addTargetStatements(connection, claim, target, input, agent,
                        actor);
                metadata.touch(connection, claim, actor,
                        repository.recordsGraph);
                focus.add(target);
                focus.add(claim);
                shacl.validate(connection, focus);
                TargetDetails details = targetDetails(connection, target,
                        claim, actor.identityAssurance);
                return details;
            }
        });
    }

    public TargetDetails getTarget(String id) {
        final IRI target = uris.resource("target", id);
        return repository.read(new LexIntRepository.Work<TargetDetails>() {
            @Override public TargetDetails run(RepositoryConnection connection) {
                requireType(connection, target, TARGET_ASSERTION,
                        repository.recordsGraph);
                IRI claim = claimForTarget(connection, target);
                return targetDetails(connection, target, claim, null);
            }
        });
    }

    public TargetDetails patchTarget(final String id, final TargetInput request,
            final String ifMatch, final LexIntActor actor) {
        final IRI target = uris.resource("target", id);
        final ValidatedTarget input = validateTarget(request);
        return repository.write(new LexIntRepository.Work<TargetDetails>() {
            @Override public TargetDetails run(RepositoryConnection connection) {
                IRI claim = claimForTarget(connection, target);
                requireEditableClaim(connection, claim);
                requireMatch(connection, target, ifMatch);
                connection.remove(target, RDF.SUBJECT, null,
                        repository.recordsGraph);
                connection.remove(target, RDF.PREDICATE, null,
                        repository.recordsGraph);
                connection.remove(target, RDF.OBJECT, null,
                        repository.recordsGraph);
                connection.add(target, RDF.SUBJECT, input.subject,
                        repository.recordsGraph);
                connection.add(target, RDF.PREDICATE, input.predicate,
                        repository.recordsGraph);
                connection.add(target, RDF.OBJECT, input.object,
                        repository.recordsGraph);
                metadata.touch(connection, target, actor,
                        repository.recordsGraph);
                shacl.validate(connection, Collections.singletonList(target));
                return targetDetails(connection, target, claim,
                        actor.identityAssurance);
            }
        });
    }

    public void deleteTarget(final String id, final LexIntActor actor) {
        final IRI target = uris.resource("target", id);
        repository.write(new LexIntRepository.Work<Void>() {
            @Override public Void run(RepositoryConnection connection) {
                IRI claim = claimForTarget(connection, target);
                requireEditableClaim(connection, claim);
                List<IRI> siblings = targetIris(connection, claim);
                if (siblings.size() <= 1) {
                    throw new LexIntException(409,
                            "LEXINT_LAST_TARGET",
                            "A claim must retain at least one target");
                }
                connection.remove(claim, TARGET_ASSERTION_PROPERTY, target,
                        repository.recordsGraph);
                connection.remove(target, null, null, repository.recordsGraph);
                metadata.touch(connection, claim, actor,
                        repository.recordsGraph);
                shacl.validate(connection, Collections.singletonList(claim));
                return null;
            }
        });
    }

    public DecisionDetails createDecision(final DecisionCreateRequest request,
            final LexIntActor actor) {
        final ValidatedDecision input = validateDecision(request);
        return repository.write(new LexIntRepository.Work<DecisionDetails>() {
            @Override public DecisionDetails run(RepositoryConnection connection) {
                requireType(connection, input.claim, INTERPRETIVE_CLAIM,
                        repository.recordsGraph);
                requireReservedVersion(connection, input.version);
                if (input.replaces != null) {
                    requireType(connection, input.replaces,
                            LEXICOGRAPHIC_DECISION, repository.recordsGraph);
                }
                List<IRI> focus = new ArrayList<IRI>();
                IRI agent = ensureAgent(connection, actor, focus);
                IRI decision = uris.mint("decision");
                connection.add(decision, RDF.TYPE, LEXICOGRAPHIC_DECISION,
                        repository.recordsGraph);
                connection.add(decision, CONCERNS_CLAIM, input.claim,
                        repository.recordsGraph);
                connection.add(decision, DECISION_OUTCOME, input.outcome,
                        repository.recordsGraph);
                connection.add(decision, APPLIES_TO_VERSION, input.version,
                        repository.recordsGraph);
                connection.add(decision, DCTERMS.DESCRIPTION,
                        vf.createLiteral(input.description), repository.recordsGraph);
                connection.add(decision, WAS_ASSOCIATED_WITH, agent,
                        repository.recordsGraph);
                String created = metadata.createIndividual(connection, decision,
                        actor, repository.recordsGraph);
                connection.add(decision, STARTED_AT_TIME,
                        vf.createLiteral(created, XSD.DATETIME),
                        repository.recordsGraph);
                if (input.replaces != null) {
                    connection.add(decision, DCTERMS.REPLACES, input.replaces,
                            repository.recordsGraph);
                }
                focus.add(decision);
                shacl.validate(connection, focus);
                return decisionDetails(connection, decision,
                        actor.identityAssurance);
            }
        });
    }

    public DecisionDetails getDecision(String id) {
        final IRI decision = uris.resource("decision", id);
        return repository.read(new LexIntRepository.Work<DecisionDetails>() {
            @Override public DecisionDetails run(RepositoryConnection connection) {
                requireType(connection, decision, LEXICOGRAPHIC_DECISION,
                        repository.recordsGraph);
                return decisionDetails(connection, decision, null);
            }
        });
    }

    public LexIntPage<DecisionDetails> listDecisions(final String claimIri,
            final String versionIri, final String outcome, final String creator,
            final Boolean finalized, final int limit, final int offset) {
        pagination(limit, offset);
        final IRI claim = blank(claimIri) ? null : inputIri(claimIri, "claimIri");
        final IRI version = blank(versionIri) ? null
                : inputIri(versionIri, "versionIri");
        final IRI outcomeIri = blank(outcome) ? null : outcome(outcome);
        return repository.read(new LexIntRepository.Work<LexIntPage<DecisionDetails>>() {
            @Override public LexIntPage<DecisionDetails> run(
                    RepositoryConnection connection) {
                List<DecisionDetails> matches = new ArrayList<DecisionDetails>();
                try (RepositoryResult<Statement> rows = connection.getStatements(
                        null, RDF.TYPE, LEXICOGRAPHIC_DECISION, false,
                        repository.recordsGraph)) {
                    while (rows.hasNext()) {
                        Resource subject = rows.next().getSubject();
                        if (!(subject instanceof IRI)) { continue; }
                        IRI decision = (IRI) subject;
                        if (claim != null && !connection.hasStatement(decision,
                                CONCERNS_CLAIM, claim, false,
                                repository.recordsGraph)) { continue; }
                        if (version != null && !connection.hasStatement(decision,
                                APPLIES_TO_VERSION, version, false,
                                repository.recordsGraph)) { continue; }
                        if (outcomeIri != null && !connection.hasStatement(decision,
                                DECISION_OUTCOME, outcomeIri, false,
                                repository.recordsGraph)) { continue; }
                        if (!blank(creator) && !connection.hasStatement(decision,
                                DCTERMS.CREATOR, vf.createLiteral(creator), false,
                                repository.recordsGraph)) { continue; }
                        boolean isFinal = connection.hasStatement(decision,
                                ENDED_AT_TIME, null, false,
                                repository.recordsGraph);
                        if (finalized != null
                                && finalized.booleanValue() != isFinal) { continue; }
                        matches.add(decisionDetails(connection, decision, null));
                    }
                }
                Collections.sort(matches, new Comparator<DecisionDetails>() {
                    @Override public int compare(DecisionDetails left,
                            DecisionDetails right) {
                        return left.iri.compareTo(right.iri);
                    }
                });
                return page(matches, limit, offset);
            }
        });
    }

    public DecisionDetails patchDecision(final String id,
            final DecisionPatchRequest request, final String ifMatch,
            final LexIntActor actor) {
        if (request == null || request.description == null
                && request.outcome == null && request.replacesIri == null) {
            throw LexIntException.invalid(
                    "description, outcome or replacesIri must be supplied");
        }
        final String description = request.description == null ? null
                : requiredText(request.description, "description");
        final IRI outcomeValue = request.outcome == null ? null
                : outcome(request.outcome);
        final IRI replaces = request.replacesIri == null ? null
                : inputIri(request.replacesIri, "replacesIri");
        final IRI decision = uris.resource("decision", id);
        return repository.write(new LexIntRepository.Work<DecisionDetails>() {
            @Override public DecisionDetails run(RepositoryConnection connection) {
                requireMutableDecision(connection, decision);
                requireMatch(connection, decision, ifMatch);
                if (replaces != null) {
                    if (decision.equals(replaces)) {
                        throw LexIntException.invalid(
                                "A decision cannot replace itself");
                    }
                    requireType(connection, replaces,
                            LEXICOGRAPHIC_DECISION, repository.recordsGraph);
                }
                if (description != null) {
                    connection.remove(decision, DCTERMS.DESCRIPTION, null,
                            repository.recordsGraph);
                    connection.add(decision, DCTERMS.DESCRIPTION,
                            vf.createLiteral(description), repository.recordsGraph);
                }
                if (outcomeValue != null) {
                    connection.remove(decision, DECISION_OUTCOME, null,
                            repository.recordsGraph);
                    connection.add(decision, DECISION_OUTCOME, outcomeValue,
                            repository.recordsGraph);
                }
                if (request.replacesIri != null) {
                    connection.remove(decision, DCTERMS.REPLACES, null,
                            repository.recordsGraph);
                    connection.add(decision, DCTERMS.REPLACES, replaces,
                            repository.recordsGraph);
                }
                metadata.touch(connection, decision, actor,
                        repository.recordsGraph);
                shacl.validate(connection, Collections.singletonList(decision));
                return decisionDetails(connection, decision,
                        actor.identityAssurance);
            }
        });
    }

    public void deleteDecision(final String id) {
        final IRI decision = uris.resource("decision", id);
        repository.write(new LexIntRepository.Work<Void>() {
            @Override public Void run(RepositoryConnection connection) {
                requireMutableDecision(connection, decision);
                if (connection.hasStatement(null, AUTHORIZED_BY, decision, false,
                        repository.auditGraph)) {
                    throw LexIntException.immutable(
                            "Decision is already used by a publication");
                }
                connection.remove(decision, null, null, repository.recordsGraph);
                connection.remove((Resource) null, null, decision,
                        repository.recordsGraph);
                return null;
            }
        });
    }

    public DecisionDetails finalizeDecision(final String id,
            final LexIntActor actor) {
        final IRI decision = uris.resource("decision", id);
        return repository.write(new LexIntRepository.Work<DecisionDetails>() {
            @Override public DecisionDetails run(RepositoryConnection connection) {
                requireMutableDecision(connection, decision);
                String modified = metadata.touch(connection, decision, actor,
                        repository.recordsGraph);
                connection.add(decision, ENDED_AT_TIME,
                        vf.createLiteral(modified, XSD.DATETIME),
                        repository.recordsGraph);
                shacl.validate(connection, Collections.singletonList(decision));
                return decisionDetails(connection, decision,
                        actor.identityAssurance);
            }
        });
    }

    private ValidatedClaim validateClaim(ClaimCreateRequest request) {
        if (request == null) { throw LexIntException.invalid("Claim body is required"); }
        if (blank(request.description)) {
            throw new LexIntException(422, "LEXINT_SHACL_VIOLATION",
                    "A claim requires a non-empty description");
        }
        String description = request.description.trim();
        if (request.targets == null || request.targets.isEmpty()) {
            throw new LexIntException(422, "LEXINT_SHACL_VIOLATION",
                    "A claim requires at least one target");
        }
        if (request.targets.size() > LexIntResourceUris.limit(
                "LexInt.write.maxOperations", 1000)) {
            throw new LexIntException(413, "LEXINT_LIMIT_EXCEEDED",
                    "Too many targets");
        }
        List<ValidatedTarget> targets = new ArrayList<ValidatedTarget>();
        for (TargetInput target : request.targets) {
            targets.add(validateTarget(target));
        }
        return new ValidatedClaim(description, iris(request.evidenceIris,
                "evidenceIris"), targets);
    }

    private ValidatedTarget validateTarget(TargetInput input) {
        if (input == null || input.object == null) {
            throw LexIntException.invalid("A complete target is required");
        }
        IRI subject = inputIri(input.subjectIri, "subjectIri");
        IRI predicate = inputIri(input.predicateIri, "predicateIri");
        TargetObjectInput object = input.object;
        if (object.reifies != null) {
            throw new LexIntException(422, "LEXINT_RDF12_UNSUPPORTED",
                    "RDF 1.2 triple terms are not supported by runtime v1");
        }
        String kind = requiredText(object.kind, "object.kind")
                .toUpperCase(Locale.ROOT);
        String value = object.value;
        if (value == null) { throw LexIntException.invalid("object.value is required"); }
        Value rdfObject;
        if ("IRI".equals(kind)) {
            if (!blank(object.language) || !blank(object.datatypeIri)) {
                throw LexIntException.invalid(
                        "An IRI object cannot have language or datatype");
            }
            rdfObject = inputIri(value, "object.value");
        } else if ("LITERAL".equals(kind)) {
            if (!blank(object.language) && !blank(object.datatypeIri)) {
                throw LexIntException.invalid(
                        "Literal language and datatype are mutually exclusive");
            }
            if (!blank(object.language)) {
                if (!object.language.matches(
                        "[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*")) {
                    throw LexIntException.invalid("Invalid literal language tag");
                }
                rdfObject = vf.createLiteral(value, object.language);
            } else if (!blank(object.datatypeIri)) {
                rdfObject = vf.createLiteral(value,
                        inputIri(object.datatypeIri, "object.datatypeIri"));
            } else {
                rdfObject = vf.createLiteral(value);
            }
        } else {
            throw LexIntException.invalid("object.kind must be IRI or LITERAL");
        }
        return new ValidatedTarget(subject, predicate, rdfObject);
    }

    private ValidatedDecision validateDecision(DecisionCreateRequest request) {
        if (request == null) {
            throw LexIntException.invalid("Decision body is required");
        }
        return new ValidatedDecision(inputIri(request.claimIri, "claimIri"),
                inputIri(request.versionIri, "versionIri"),
                outcome(request.outcome), requiredText(request.description,
                        "description"), blank(request.replacesIri) ? null
                        : inputIri(request.replacesIri, "replacesIri"));
    }

    private void addTargetStatements(RepositoryConnection connection, IRI claim,
            IRI target, ValidatedTarget input, IRI agent, LexIntActor actor) {
        connection.add(claim, TARGET_ASSERTION_PROPERTY, target,
                repository.recordsGraph);
        connection.add(target, RDF.TYPE, TARGET_ASSERTION,
                repository.recordsGraph);
        connection.add(target, RDF.TYPE, RDF.STATEMENT,
                repository.recordsGraph);
        connection.add(target, RDF.SUBJECT, input.subject,
                repository.recordsGraph);
        connection.add(target, RDF.PREDICATE, input.predicate,
                repository.recordsGraph);
        connection.add(target, RDF.OBJECT, input.object,
                repository.recordsGraph);
        connection.add(target, WAS_ATTRIBUTED_TO, agent,
                repository.recordsGraph);
        metadata.createIndividual(connection, target, actor,
                repository.recordsGraph);
    }

    private IRI ensureAgent(RepositoryConnection connection, LexIntActor actor,
            List<IRI> focus) {
        IRI agent = uris.agent(actor);
        if (!connection.hasStatement(agent, null, null, false,
                repository.auditGraph)) {
            connection.add(agent, RDF.TYPE, AGENT, repository.auditGraph);
            metadata.createIndividual(connection, agent, actor,
                    repository.auditGraph);
            focus.add(agent);
        } else {
            requireType(connection, agent, AGENT, repository.auditGraph);
            metadata.validate(connection, agent, repository.auditGraph);
        }
        return agent;
    }

    private ClaimDetails claimDetails(RepositoryConnection connection, IRI claim,
            String assurance) {
        ClaimDetails result = new ClaimDetails();
        result.id = uris.id(claim); result.iri = claim.stringValue();
        result.description = firstLiteral(connection, claim,
                DCTERMS.DESCRIPTION, repository.recordsGraph);
        result.evidenceIris.addAll(iriObjects(connection, claim,
                WAS_DERIVED_FROM, repository.recordsGraph));
        for (IRI target : targetIris(connection, claim)) {
            result.targets.add(targetDetails(connection, target, claim,
                    assurance));
        }
        result.creator = metadata.creator(connection, claim,
                repository.recordsGraph);
        result.created = metadata.created(connection, claim,
                repository.recordsGraph);
        result.modified = metadata.modified(connection, claim,
                repository.recordsGraph);
        result.etag = etag(claim, result.modified);
        result.editable = isEditableClaim(connection, claim);
        result.identityAssurance = assurance;
        return result;
    }

    private TargetDetails targetDetails(RepositoryConnection connection,
            IRI target, IRI claim, String assurance) {
        TargetDetails result = new TargetDetails();
        result.id = uris.id(target); result.iri = target.stringValue();
        result.claimIri = claim == null ? null : claim.stringValue();
        result.subjectIri = firstIri(connection, target, RDF.SUBJECT,
                repository.recordsGraph).stringValue();
        result.predicateIri = firstIri(connection, target, RDF.PREDICATE,
                repository.recordsGraph).stringValue();
        result.object = term(firstValue(connection, target, RDF.OBJECT,
                repository.recordsGraph));
        result.creator = metadata.creator(connection, target,
                repository.recordsGraph);
        result.created = metadata.created(connection, target,
                repository.recordsGraph);
        result.modified = metadata.modified(connection, target,
                repository.recordsGraph);
        result.etag = etag(target, result.modified);
        result.editable = claim != null && isEditableClaim(connection, claim);
        result.identityAssurance = assurance;
        return result;
    }

    private DecisionDetails decisionDetails(RepositoryConnection connection,
            IRI decision, String assurance) {
        DecisionDetails result = new DecisionDetails();
        result.id = uris.id(decision); result.iri = decision.stringValue();
        result.claimIri = firstIri(connection, decision, CONCERNS_CLAIM,
                repository.recordsGraph).stringValue();
        result.versionIri = firstIri(connection, decision, APPLIES_TO_VERSION,
                repository.recordsGraph).stringValue();
        result.outcome = local(firstIri(connection, decision, DECISION_OUTCOME,
                repository.recordsGraph));
        result.description = firstLiteral(connection, decision,
                DCTERMS.DESCRIPTION, repository.recordsGraph);
        IRI replaces = optionalIri(connection, decision, DCTERMS.REPLACES,
                repository.recordsGraph);
        result.replacesIri = replaces == null ? null : replaces.stringValue();
        result.finalized = connection.hasStatement(decision, ENDED_AT_TIME,
                null, false, repository.recordsGraph);
        result.startedAt = optionalLiteral(connection, decision,
                STARTED_AT_TIME, repository.recordsGraph);
        result.endedAt = optionalLiteral(connection, decision,
                ENDED_AT_TIME, repository.recordsGraph);
        result.creator = metadata.creator(connection, decision,
                repository.recordsGraph);
        result.created = metadata.created(connection, decision,
                repository.recordsGraph);
        result.modified = metadata.modified(connection, decision,
                repository.recordsGraph);
        result.etag = etag(decision, result.modified);
        result.identityAssurance = assurance;
        return result;
    }

    private void requireReservedVersion(RepositoryConnection connection,
            IRI version) {
        requireType(connection, version, ENTITY, repository.auditGraph);
        if (!connection.hasStatement(version, VERSION_STATE,
                vf.createLiteral("RESERVED"), false, repository.auditGraph)) {
            throw new LexIntException(422, "LEXINT_DECISION_NOT_ELIGIBLE",
                    "Decision version must be reserved and unpublished");
        }
    }

    private void requireEditableClaim(RepositoryConnection connection,
            IRI claim) {
        requireType(connection, claim, INTERPRETIVE_CLAIM,
                repository.recordsGraph);
        if (!isEditableClaim(connection, claim)) {
            throw LexIntException.immutable(
                    "Claim is frozen by a finalized decision or publication");
        }
    }

    private boolean isEditableClaim(RepositoryConnection connection, IRI claim) {
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                CONCERNS_CLAIM, claim, false, repository.recordsGraph)) {
            while (rows.hasNext()) {
                Resource decision = rows.next().getSubject();
                if (connection.hasStatement(decision, ENDED_AT_TIME, null,
                        false, repository.recordsGraph)) { return false; }
            }
        }
        for (IRI target : targetIris(connection, claim)) {
            if (connection.hasStatement(null, PUB_TARGET_ASSERTION, target,
                    false, repository.auditGraph)) { return false; }
        }
        return true;
    }

    private void requireMutableDecision(RepositoryConnection connection,
            IRI decision) {
        requireType(connection, decision, LEXICOGRAPHIC_DECISION,
                repository.recordsGraph);
        if (connection.hasStatement(decision, ENDED_AT_TIME, null, false,
                repository.recordsGraph)) {
            throw LexIntException.immutable("Finalized decisions are immutable");
        }
    }

    private IRI claimForTarget(RepositoryConnection connection, IRI target) {
        requireType(connection, target, TARGET_ASSERTION,
                repository.recordsGraph);
        IRI found = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(null,
                TARGET_ASSERTION_PROPERTY, target, false,
                repository.recordsGraph)) {
            while (rows.hasNext()) {
                Resource subject = rows.next().getSubject();
                if (!(subject instanceof IRI) || found != null) {
                    throw new LexIntException(409,
                            "LEXINT_AMBIGUOUS_AUTHORIZATION",
                            "Target must belong to exactly one claim");
                }
                found = (IRI) subject;
            }
        }
        if (found == null) { throw LexIntException.notFound("Target claim not found"); }
        return found;
    }

    private List<IRI> targetIris(RepositoryConnection connection, IRI claim) {
        List<IRI> result = new ArrayList<IRI>();
        try (RepositoryResult<Statement> rows = connection.getStatements(claim,
                TARGET_ASSERTION_PROPERTY, null, false,
                repository.recordsGraph)) {
            while (rows.hasNext()) {
                Value value = rows.next().getObject();
                if (value instanceof IRI) { result.add((IRI) value); }
            }
        }
        Collections.sort(result, new Comparator<IRI>() {
            @Override public int compare(IRI left, IRI right) {
                return left.stringValue().compareTo(right.stringValue());
            }
        });
        return result;
    }

    private boolean claimHasSubject(RepositoryConnection connection, IRI claim,
            IRI subject) {
        for (IRI target : targetIris(connection, claim)) {
            if (connection.hasStatement(target, RDF.SUBJECT, subject, false,
                    repository.recordsGraph)) { return true; }
        }
        return false;
    }

    private void requireMatch(RepositoryConnection connection, IRI resource,
            String ifMatch) {
        if (blank(ifMatch)) {
            throw new LexIntException(412, "LEXINT_BASE_VERSION_MISMATCH",
                    "If-Match is required");
        }
        String modified = metadata.modified(connection, resource,
                repository.recordsGraph);
        if (!etag(resource, modified).equals(ifMatch)) {
            throw new LexIntException(412, "LEXINT_BASE_VERSION_MISMATCH",
                    "If-Match does not match the current resource");
        }
    }

    private String etag(IRI resource, String modified) {
        return "\"" + LexIntResourceUris.sha256(resource.stringValue() + "|"
                + modified) + "\"";
    }

    private void requireType(RepositoryConnection connection, IRI resource,
            IRI type, Resource graph) {
        if (!connection.hasStatement(resource, RDF.TYPE, type, false, graph)) {
            throw LexIntException.notFound(resource.stringValue()
                    + " was not found in the required LexInt graph");
        }
    }

    private IRI outcome(String value) {
        String normalized = requiredText(value, "outcome")
                .toLowerCase(Locale.ROOT);
        if ("adopted".equals(normalized)) { return ADOPTED; }
        if ("rejected".equals(normalized)) { return REJECTED; }
        if ("suspended".equals(normalized)) { return SUSPENDED; }
        if ("replaced".equals(normalized)) { return REPLACED; }
        throw LexIntException.invalid("Unsupported decision outcome");
    }

    private IRI inputIri(String value, String field) {
        String text = requiredText(value, field);
        try {
            URI uri = new URI(text);
            if (!uri.isAbsolute()) { throw LexIntException.invalid(field
                    + " must be an absolute IRI"); }
            return vf.createIRI(text);
        } catch (URISyntaxException | IllegalArgumentException ex) {
            throw LexIntException.invalid(field + " must be an absolute IRI");
        }
    }

    private List<IRI> iris(List<String> values, String field) {
        if (values == null) { return new ArrayList<IRI>(); }
        Set<String> unique = new LinkedHashSet<String>();
        List<IRI> result = new ArrayList<IRI>();
        for (String value : values) {
            IRI iri = inputIri(value, field);
            if (!unique.add(iri.stringValue())) {
                throw LexIntException.invalid("Duplicate " + field + " value");
            }
            result.add(iri);
        }
        return result;
    }

    private String requiredText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw LexIntException.invalid(field + " is required");
        }
        return value.trim();
    }

    private void pagination(int limit, int offset) {
        if (limit < 1 || limit > LexIntResourceUris.limit(
                "LexInt.list.maxLimit", 500) || offset < 0) {
            throw LexIntException.invalid("Invalid pagination");
        }
    }

    private <T> LexIntPage<T> page(List<T> all, int limit, int offset) {
        LexIntPage<T> page = new LexIntPage<T>();
        page.total = all.size(); page.limit = limit; page.offset = offset;
        if (offset < all.size()) {
            page.items.addAll(all.subList(offset,
                    Math.min(all.size(), offset + limit)));
        }
        return page;
    }

    private List<String> iriObjects(RepositoryConnection connection,
            IRI subject, IRI predicate, Resource graph) {
        Set<String> values = new TreeSet<String>();
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                Value value = rows.next().getObject();
                if (value instanceof IRI) { values.add(value.stringValue()); }
            }
        }
        return new ArrayList<String>(values);
    }

    private IRI firstIri(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        IRI value = optionalIri(connection, subject, predicate, graph);
        if (value == null) { throw new LexIntException(409,
                "LEXINT_DATA_INCONSISTENT", "Required IRI value is missing"); }
        return value;
    }

    private IRI optionalIri(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        IRI result = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                Value value = rows.next().getObject();
                if (!(value instanceof IRI) || result != null) {
                    throw new LexIntException(409,
                            "LEXINT_DATA_INCONSISTENT",
                            "IRI cardinality is invalid");
                }
                result = (IRI) value;
            }
        }
        return result;
    }

    private Value firstValue(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        Value result = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                if (result != null) { throw new LexIntException(409,
                        "LEXINT_DATA_INCONSISTENT",
                        "RDF term cardinality is invalid"); }
                result = rows.next().getObject();
            }
        }
        if (result == null) { throw new LexIntException(409,
                "LEXINT_DATA_INCONSISTENT", "Required RDF term is missing"); }
        return result;
    }

    private String firstLiteral(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        Value value = firstValue(connection, subject, predicate, graph);
        if (!(value instanceof Literal)) { throw new LexIntException(409,
                "LEXINT_DATA_INCONSISTENT", "Required literal is missing"); }
        return value.stringValue();
    }

    private String optionalLiteral(RepositoryConnection connection, IRI subject,
            IRI predicate, Resource graph) {
        String result = null;
        try (RepositoryResult<Statement> rows = connection.getStatements(subject,
                predicate, null, false, graph)) {
            while (rows.hasNext()) {
                Value value = rows.next().getObject();
                if (!(value instanceof Literal) || result != null) {
                    throw new LexIntException(409,
                            "LEXINT_DATA_INCONSISTENT",
                            "Literal cardinality is invalid");
                }
                result = value.stringValue();
            }
        }
        return result;
    }

    private TermDetails term(Value value) {
        TermDetails result = new TermDetails();
        result.value = value.stringValue();
        if (value instanceof IRI) { result.type = "IRI"; }
        else if (value instanceof Literal) {
            result.type = "LITERAL";
            Literal literal = (Literal) value;
            if (literal.getLanguage().isPresent()) {
                result.lang = literal.getLanguage().get();
            } else if (literal.getDatatype() != null) {
                result.datatype = literal.getDatatype().stringValue();
            }
        } else { throw new LexIntException(422,
                "LEXINT_INVALID_SEMANTICS", "Blank node targets are unsupported"); }
        return result;
    }

    private String local(IRI iri) {
        String value = iri.stringValue();
        int hash = value.lastIndexOf('#');
        int slash = value.lastIndexOf('/');
        return value.substring(Math.max(hash, slash) + 1);
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static final class ValidatedTarget {
        final IRI subject; final IRI predicate; final Value object;
        ValidatedTarget(IRI subject, IRI predicate, Value object) {
            this.subject = subject; this.predicate = predicate;
            this.object = object;
        }
    }
    private static final class ValidatedClaim {
        final String description; final List<IRI> evidence;
        final List<ValidatedTarget> targets;
        ValidatedClaim(String description, List<IRI> evidence,
                List<ValidatedTarget> targets) {
            this.description = description; this.evidence = evidence;
            this.targets = targets;
        }
    }
    private static final class ValidatedDecision {
        final IRI claim; final IRI version; final IRI outcome;
        final String description; final IRI replaces;
        ValidatedDecision(IRI claim, IRI version, IRI outcome,
                String description, IRI replaces) {
            this.claim = claim; this.version = version; this.outcome = outcome;
            this.description = description; this.replaces = replaces;
        }
    }
}
