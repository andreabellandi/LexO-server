package it.cnr.ilc.lexo.manager.text.organization;

import it.cnr.ilc.lexo.LexOProperties;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;

/** Centralized RDF vocabulary and configured graph identifiers for text folders. */
public final class TextOrganizationVocabulary {

    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final String namespace;
    private final String folderBaseUri;

    public final IRI dataGraph;
    public final IRI schemaGraph;
    public final IRI folderClass;
    public final IRI parentFolder;
    public final IRI childFolder;
    public final IRI containsText;
    public final IRI inFolder;
    public final IRI title = DCTERMS.TITLE;
    public final IRI description = DCTERMS.DESCRIPTION;
    public final IRI created = DCTERMS.CREATED;
    public final IRI modified = DCTERMS.MODIFIED;

    public TextOrganizationVocabulary() {
        namespace = required("lexo.text.organization.namespace",
                "https://lexo.ilc.cnr.it/vocabulary/text-organization#");
        folderBaseUri = trailingSlash(required(
                "lexo.text.organization.folderBaseUri",
                "https://lexo.ilc.cnr.it/resources/text-folders/"));
        dataGraph = vf.createIRI(required("TextGraphDb.organizationNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/text-organization"));
        schemaGraph = vf.createIRI(required(
                "TextGraphDb.organizationSchemaNamedGraph",
                "https://lexo.ilc.cnr.it/graphs/text-organization/schema"));
        if (dataGraph.equals(schemaGraph)) {
            throw new IllegalStateException(
                    "Text organization schema and data graphs must be different");
        }
        folderClass = vf.createIRI(namespace + "Folder");
        parentFolder = vf.createIRI(namespace + "parentFolder");
        childFolder = vf.createIRI(namespace + "childFolder");
        containsText = vf.createIRI(namespace + "containsText");
        inFolder = vf.createIRI(namespace + "inFolder");
    }

    public IRI folderIri(String folderId) {
        return vf.createIRI(folderBaseUri + folderId);
    }

    public String folderId(IRI folder) {
        String value = folder.stringValue();
        return value.startsWith(folderBaseUri)
                ? value.substring(folderBaseUri.length()) : null;
    }

    public String folderBaseUri() {
        return folderBaseUri;
    }

    private static String required(String name, String fallback) {
        String value = LexOProperties.getProperty(name, fallback);
        if (value == null || value.trim().isEmpty() || value.contains("${")) {
            throw new IllegalStateException("Missing text organization property: " + name);
        }
        return value.trim();
    }

    private static String trailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }
}
