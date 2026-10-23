package it.cnr.ilc.lexo.manager.text.organization;

import it.cnr.ilc.lexo.GraphDbUtil;
import it.cnr.ilc.lexo.RepositoryTarget;
import it.cnr.ilc.lexo.service.data.text.output.FolderRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** RDF4J persistence for the isolated text-organization data graph. */
public final class TextOrganizationRepository {

    static final class MembershipChange {
        final String previousFolderId;
        final boolean changed;

        MembershipChange(String previousFolderId, boolean changed) {
            this.previousFolderId = previousFolderId;
            this.changed = changed;
        }
    }

    public static final class MembershipSnapshot {
        public final String folderId;
        public final String contextUri;

        MembershipSnapshot(String folderId, String contextUri) {
            this.folderId = folderId;
            this.contextUri = contextUri;
        }
    }

    private static final class Holder {
        private static final TextOrganizationRepository INSTANCE =
                new TextOrganizationRepository(null);
    }

    public static TextOrganizationRepository get() {
        return Holder.INSTANCE;
    }

    private final Repository providedRepository;
    private final TextOrganizationVocabulary vocabulary;
    private final ValueFactory vf = SimpleValueFactory.getInstance();

    private TextOrganizationRepository(Repository providedRepository) {
        this.providedRepository = providedRepository;
        this.vocabulary = new TextOrganizationVocabulary();
    }

    TextOrganizationRepository(Repository providedRepository,
                               TextOrganizationVocabulary vocabulary) {
        this.providedRepository = providedRepository;
        this.vocabulary = vocabulary;
    }

    TextOrganizationVocabulary vocabulary() {
        return vocabulary;
    }

    boolean folderExists(String folderId) {
        RepositoryConnection connection = acquire();
        try {
            return folderExists(connection, vocabulary.folderIri(folderId));
        } finally {
            release(connection);
        }
    }

    FolderRecord getFolder(String folderId) {
        RepositoryConnection connection = acquire();
        try {
            return readFolder(connection, vocabulary.folderIri(folderId));
        } finally {
            release(connection);
        }
    }

    List<FolderRecord> listFolders() {
        RepositoryConnection connection = acquire();
        try {
            List<FolderRecord> folders = new ArrayList<FolderRecord>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, RDF.TYPE, vocabulary.folderClass, false,
                    vocabulary.dataGraph)) {
                while (statements.hasNext()) {
                    Resource subject = statements.next().getSubject();
                    if (subject instanceof IRI) {
                        FolderRecord folder = readFolder(connection, (IRI) subject);
                        if (folder != null) {
                            folders.add(folder);
                        }
                    }
                }
            }
            return folders;
        } finally {
            release(connection);
        }
    }

    void createFolder(FolderRecord folder) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            IRI subject = vf.createIRI(folder.uri);
            if (folderExists(connection, subject)) {
                throw new IllegalStateException("Folder already exists: " + folder.folderId);
            }
            connection.add(subject, RDF.TYPE, vocabulary.folderClass,
                    vocabulary.dataGraph);
            connection.add(subject, vocabulary.title, vf.createLiteral(folder.name),
                    vocabulary.dataGraph);
            if (folder.description != null) {
                connection.add(subject, vocabulary.description,
                        vf.createLiteral(folder.description), vocabulary.dataGraph);
            }
            connection.add(subject, vocabulary.created, dateTime(folder.createdAt),
                    vocabulary.dataGraph);
            connection.add(subject, vocabulary.modified, dateTime(folder.modifiedAt),
                    vocabulary.dataGraph);
            if (folder.parentFolderId != null) {
                connection.add(subject, vocabulary.parentFolder,
                        vocabulary.folderIri(folder.parentFolderId),
                        vocabulary.dataGraph);
            }
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    void updateFolder(String folderId, String name, String description,
                      String modifiedAt) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            IRI folder = vocabulary.folderIri(folderId);
            connection.remove(folder, vocabulary.title, null,
                    vocabulary.dataGraph);
            connection.add(folder, vocabulary.title, vf.createLiteral(name),
                    vocabulary.dataGraph);
            connection.remove(folder, vocabulary.description, null,
                    vocabulary.dataGraph);
            if (description != null) {
                connection.add(folder, vocabulary.description,
                        vf.createLiteral(description), vocabulary.dataGraph);
            }
            replaceModified(connection, folder, modifiedAt);
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    void moveFolder(String folderId, String parentFolderId, String modifiedAt) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            IRI folder = vocabulary.folderIri(folderId);
            connection.remove(folder, vocabulary.parentFolder, null,
                    vocabulary.dataGraph);
            if (parentFolderId != null) {
                connection.add(folder, vocabulary.parentFolder,
                        vocabulary.folderIri(parentFolderId),
                        vocabulary.dataGraph);
            }
            replaceModified(connection, folder, modifiedAt);
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    boolean deleteEmptyFolder(String folderId) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            IRI folder = vocabulary.folderIri(folderId);
            if (connection.hasStatement(null, vocabulary.parentFolder, folder,
                    false, vocabulary.dataGraph)
                    || connection.hasStatement(folder, vocabulary.containsText,
                            null, false, vocabulary.dataGraph)) {
                connection.rollback();
                return false;
            }
            connection.remove(folder, null, null, vocabulary.dataGraph);
            connection.remove((Resource) null, null, folder,
                    vocabulary.dataGraph);
            connection.commit();
            return true;
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    Map<String, String> membershipsByContext() {
        RepositoryConnection connection = acquire();
        try {
            Map<String, String> memberships = new LinkedHashMap<String, String>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    null, vocabulary.containsText, null, false,
                    vocabulary.dataGraph)) {
                while (statements.hasNext()) {
                    Statement statement = statements.next();
                    if (statement.getSubject() instanceof IRI
                            && statement.getObject() instanceof IRI) {
                        String folderId = vocabulary.folderId(
                                (IRI) statement.getSubject());
                        if (folderId != null) {
                            memberships.put(statement.getObject().stringValue(),
                                    folderId);
                        }
                    }
                }
            }
            return memberships;
        } finally {
            release(connection);
        }
    }

    MembershipChange moveText(String contextUri, String targetFolderId,
                              String modifiedAt) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            MembershipChange result = moveText(connection, vf.createIRI(contextUri),
                    targetFolderId, modifiedAt);
            connection.commit();
            return result;
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    Map<String, MembershipChange> moveTexts(List<String> contextUris,
                                             String targetFolderId,
                                             String modifiedAt) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            Map<String, MembershipChange> changes =
                    new LinkedHashMap<String, MembershipChange>();
            for (String contextUri : contextUris) {
                changes.put(contextUri, moveText(connection,
                        vf.createIRI(contextUri), targetFolderId, modifiedAt));
            }
            connection.commit();
            return changes;
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    boolean removeTextFromFolder(String folderId, String contextUri,
                                 String modifiedAt) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            IRI folder = vocabulary.folderIri(folderId);
            IRI context = vf.createIRI(contextUri);
            if (!connection.hasStatement(folder, vocabulary.containsText,
                    context, false, vocabulary.dataGraph)) {
                connection.rollback();
                return false;
            }
            connection.remove(folder, vocabulary.containsText, context,
                    vocabulary.dataGraph);
            replaceModified(connection, folder, modifiedAt);
            connection.commit();
            return true;
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    MembershipSnapshot detachText(String contextUri, String modifiedAt) {
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            IRI context = vf.createIRI(contextUri);
            List<IRI> folders = foldersContaining(connection, context);
            for (IRI folder : folders) {
                connection.remove(folder, vocabulary.containsText, context,
                        vocabulary.dataGraph);
                replaceModified(connection, folder, modifiedAt);
            }
            connection.commit();
            if (folders.isEmpty()) {
                return null;
            }
            return new MembershipSnapshot(vocabulary.folderId(folders.get(0)),
                    contextUri);
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    void restoreText(MembershipSnapshot snapshot, String modifiedAt) {
        if (snapshot == null) {
            return;
        }
        RepositoryConnection connection = acquire();
        try {
            connection.begin();
            IRI folder = vocabulary.folderIri(snapshot.folderId);
            if (!folderExists(connection, folder)) {
                throw new IllegalStateException(
                        "Cannot restore membership: folder no longer exists: "
                        + snapshot.folderId);
            }
            IRI context = vf.createIRI(snapshot.contextUri);
            connection.remove((Resource) null, vocabulary.containsText,
                    context, vocabulary.dataGraph);
            connection.add(folder, vocabulary.containsText, context,
                    vocabulary.dataGraph);
            replaceModified(connection, folder, modifiedAt);
            connection.commit();
        } catch (RuntimeException e) {
            rollback(connection);
            throw e;
        } finally {
            release(connection);
        }
    }

    Set<String> textContexts(String folderId) {
        RepositoryConnection connection = acquire();
        try {
            Set<String> contexts = new LinkedHashSet<String>();
            try (RepositoryResult<Statement> statements = connection.getStatements(
                    vocabulary.folderIri(folderId), vocabulary.containsText,
                    null, false, vocabulary.dataGraph)) {
                while (statements.hasNext()) {
                    Value value = statements.next().getObject();
                    if (value instanceof IRI) {
                        contexts.add(value.stringValue());
                    }
                }
            }
            return contexts;
        } finally {
            release(connection);
        }
    }

    private MembershipChange moveText(RepositoryConnection connection,
                                      IRI context, String targetFolderId,
                                      String modifiedAt) {
        List<IRI> previousFolders = foldersContaining(connection, context);
        String previousFolderId = previousFolders.isEmpty() ? null
                : vocabulary.folderId(previousFolders.get(0));
        boolean alreadyOnlyTarget = previousFolders.size() == 1
                && equal(previousFolderId, targetFolderId);
        if (alreadyOnlyTarget) {
            return new MembershipChange(previousFolderId, false);
        }
        for (IRI previous : previousFolders) {
            connection.remove(previous, vocabulary.containsText, context,
                    vocabulary.dataGraph);
            replaceModified(connection, previous, modifiedAt);
        }
        if (targetFolderId != null) {
            IRI target = vocabulary.folderIri(targetFolderId);
            connection.add(target, vocabulary.containsText, context,
                    vocabulary.dataGraph);
            replaceModified(connection, target, modifiedAt);
        }
        return new MembershipChange(previousFolderId, true);
    }

    private List<IRI> foldersContaining(RepositoryConnection connection,
                                        IRI context) {
        List<IRI> folders = new ArrayList<IRI>();
        try (RepositoryResult<Statement> statements = connection.getStatements(
                null, vocabulary.containsText, context, false,
                vocabulary.dataGraph)) {
            while (statements.hasNext()) {
                Resource subject = statements.next().getSubject();
                if (subject instanceof IRI) {
                    folders.add((IRI) subject);
                }
            }
        }
        Collections.sort(folders, (left, right) -> left.stringValue()
                .compareTo(right.stringValue()));
        return folders;
    }

    private FolderRecord readFolder(RepositoryConnection connection, IRI folder) {
        if (!folderExists(connection, folder)) {
            return null;
        }
        FolderRecord record = new FolderRecord();
        record.uri = folder.stringValue();
        record.folderId = vocabulary.folderId(folder);
        record.name = stringValue(connection, folder, vocabulary.title);
        record.description = stringValue(connection, folder,
                vocabulary.description);
        record.createdAt = stringValue(connection, folder, vocabulary.created);
        record.modifiedAt = stringValue(connection, folder,
                vocabulary.modified);
        Value parent = firstValue(connection, folder, vocabulary.parentFolder);
        if (parent instanceof IRI) {
            record.parentFolderId = vocabulary.folderId((IRI) parent);
        }
        return record;
    }

    private boolean folderExists(RepositoryConnection connection, IRI folder) {
        return connection.hasStatement(folder, RDF.TYPE, vocabulary.folderClass,
                false, vocabulary.dataGraph);
    }

    private String stringValue(RepositoryConnection connection, Resource subject,
                               IRI predicate) {
        Value value = firstValue(connection, subject, predicate);
        return value == null ? null : value.stringValue();
    }

    private Value firstValue(RepositoryConnection connection, Resource subject,
                             IRI predicate) {
        try (RepositoryResult<Statement> statements = connection.getStatements(
                subject, predicate, null, false, vocabulary.dataGraph)) {
            return statements.hasNext() ? statements.next().getObject() : null;
        }
    }

    private void replaceModified(RepositoryConnection connection, IRI folder,
                                 String modifiedAt) {
        connection.remove(folder, vocabulary.modified, null,
                vocabulary.dataGraph);
        connection.add(folder, vocabulary.modified, dateTime(modifiedAt),
                vocabulary.dataGraph);
    }

    private Literal dateTime(String value) {
        return vf.createLiteral(value, XSD.DATETIME);
    }

    private RepositoryConnection acquire() {
        return providedRepository == null
                ? GraphDbUtil.getConnection(RepositoryTarget.TEXT)
                : providedRepository.getConnection();
    }

    private void release(RepositoryConnection connection) {
        if (providedRepository == null) {
            GraphDbUtil.releaseConnection(RepositoryTarget.TEXT, connection);
        } else if (connection != null) {
            connection.close();
        }
    }

    private static void rollback(RepositoryConnection connection) {
        if (connection != null && connection.isActive()) {
            connection.rollback();
        }
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
