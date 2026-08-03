package it.cnr.ilc.lexo.manager.metadata;

import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataProperty;
import it.cnr.ilc.lexo.service.data.metadata.RdfMetadataValue;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;

/** Shared validation and RDF conversion for metadata on every entity. */
public final class RdfMetadataCodec {

    private final ValueFactory vf = SimpleValueFactory.getInstance();

    public Value decode(RdfMetadataValue item, String path) {
        if (item == null) {
            throw invalid("INVALID_METADATA_VALUE", path + " must be an object");
        }
        String type = required(item.type, "MISSING_METADATA_VALUE_TYPE",
                path + ".type is required").toLowerCase(Locale.ROOT);
        if ("iri".equals(type)) {
            if (notBlank(item.language) || notBlank(item.datatype)) {
                throw invalid("INVALID_METADATA_VALUE",
                        path + " cannot define language or datatype for an IRI");
            }
            return iri(required(item.value, "MISSING_METADATA_VALUE",
                    path + ".value is required"), path + ".value");
        }
        if (!"literal".equals(type)) {
            throw invalid("INVALID_METADATA_VALUE_TYPE",
                    path + ".type must be literal or iri");
        }
        if (item.value == null) {
            throw invalid("MISSING_METADATA_VALUE", path + ".value is required");
        }
        if (notBlank(item.language) && notBlank(item.datatype)) {
            throw invalid("INVALID_METADATA_VALUE",
                    path + " cannot define both language and datatype");
        }
        if (notBlank(item.language)) {
            String language = item.language.trim();
            if (!language.matches("[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*")) {
                throw invalid("INVALID_METADATA_LANGUAGE",
                        path + ".language is not a valid BCP 47 tag");
            }
            return vf.createLiteral(item.value, language);
        }
        if (notBlank(item.datatype)) {
            return vf.createLiteral(item.value,
                    iri(item.datatype.trim(), path + ".datatype"));
        }
        return vf.createLiteral(item.value);
    }

    public LinkedHashMap<IRI, List<Value>> decodeProperties(
            List<RdfMetadataProperty> properties, Set<String> reserved,
            boolean allowEmptyValues) {
        if (properties == null || properties.isEmpty()) {
            throw invalid("MISSING_METADATA_PROPERTIES",
                    "at least one metadata property is required");
        }
        LinkedHashMap<IRI, List<Value>> result =
                new LinkedHashMap<IRI, List<Value>>();
        for (int i = 0; i < properties.size(); i++) {
            RdfMetadataProperty property = properties.get(i);
            String path = "properties[" + i + "]";
            if (property == null) {
                throw invalid("INVALID_METADATA_PROPERTY", path + " is null");
            }
            IRI predicate = iri(required(property.property,
                    "MISSING_METADATA_PROPERTY", path + ".property is required"),
                    path + ".property");
            if (reserved.contains(predicate.stringValue())) {
                throw invalid("RESERVED_METADATA_PROPERTY",
                        predicate.stringValue() + " is managed by the service");
            }
            if (result.containsKey(predicate)) {
                throw invalid("DUPLICATE_METADATA_PROPERTY",
                        predicate.stringValue());
            }
            if (property.values == null) {
                throw invalid("MISSING_METADATA_VALUES",
                        path + ".values is required");
            }
            if (!allowEmptyValues && property.values.isEmpty()) {
                throw invalid("MISSING_METADATA_VALUES",
                        path + ".values must not be empty during creation");
            }
            List<Value> values = new ArrayList<Value>();
            for (int j = 0; j < property.values.size(); j++) {
                values.add(decode(property.values.get(j), path + ".values[" + j + "]"));
            }
            result.put(predicate, values);
        }
        return result;
    }

    public RdfMetadataValue encode(Value value) {
        RdfMetadataValue result = new RdfMetadataValue();
        result.value = value.stringValue();
        if (value instanceof IRI) {
            result.type = "iri";
            return result;
        }
        if (!(value instanceof Literal)) {
            return null;
        }
        Literal literal = (Literal) value;
        result.type = "literal";
        result.value = literal.getLabel();
        result.language = literal.getLanguage().orElse(null);
        if (result.language == null && !XSD.STRING.equals(literal.getDatatype())) {
            result.datatype = literal.getDatatype().stringValue();
        }
        return result;
    }

    public List<RdfMetadataProperty> encode(Map<IRI, List<Value>> properties) {
        List<RdfMetadataProperty> result = new ArrayList<RdfMetadataProperty>();
        List<IRI> predicates = new ArrayList<IRI>(properties.keySet());
        Collections.sort(predicates, Comparator.comparing(IRI::stringValue));
        for (IRI predicate : predicates) {
            RdfMetadataProperty property = new RdfMetadataProperty();
            property.property = predicate.stringValue();
            for (Value value : properties.get(predicate)) {
                RdfMetadataValue encoded = encode(value);
                if (encoded != null) {
                    property.values.add(encoded);
                }
            }
            Collections.sort(property.values,
                    Comparator.comparing(this::sortKey));
            result.add(property);
        }
        return result;
    }

    public IRI iri(String value, String path) {
        String normalized = required(value, "INVALID_METADATA_IRI",
                path + " must be an absolute IRI").trim();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getScheme() == null) {
                throw invalid("INVALID_METADATA_IRI",
                        path + " must be an absolute IRI");
            }
            return vf.createIRI(normalized);
        } catch (URISyntaxException e) {
            throw invalid("INVALID_METADATA_IRI",
                    path + " must be an absolute IRI");
        }
    }

    private String sortKey(RdfMetadataValue value) {
        return value.type + "|" + value.value + "|"
                + (value.language == null ? "" : value.language) + "|"
                + (value.datatype == null ? "" : value.datatype);
    }

    private String required(String value, String code, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(code, message);
        }
        return value;
    }

    private boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }
}
