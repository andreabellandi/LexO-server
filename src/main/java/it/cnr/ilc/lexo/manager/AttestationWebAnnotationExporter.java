package it.cnr.ilc.lexo.manager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.service.data.attestation.output.WebAnnotationDocument;
import it.cnr.ilc.lexo.util.LexicalNamedGraphs;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.DCTERMS;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;

/** Read-only mapping over the manager's existing graph loaders and metadata codec. */
final class AttestationWebAnnotationExporter {
    static final String CONTEXT = "http://www.w3.org/ns/anno.jsonld";
    static final String EXTENSION = "https://lexo.ilc.cnr.it/vocabulary/web-annotation#";
    private static final String MOTIVATION = "identifying";
    private static final String FRAC = "http://www.w3.org/ns/lemon/frac#";
    private static final String NIF = "http://persistence.uni-leipzig.org/nlp2rdf/ontologies/nif-core#";
    private static final Pattern CHAR = Pattern.compile("char=([0-9]+),([0-9]+)");
    private static final int BATCH_SIZE = 256;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AttestationManager manager;
    private final String textGraphBase;

    AttestationWebAnnotationExporter(AttestationManager manager, String textGraphBase) {
        this.manager = manager;
        this.textGraphBase = textGraphBase;
    }

    List<IRI> validateContexts(List<String> contexts) throws WebAnnotationExportException {
        if (contexts == null || contexts.isEmpty()) {
            return null; // All supported document graphs, not the default/union graph.
        }
        Set<String> unique = new TreeSet<String>();
        for (String context : contexts) {
            if (!absolute(context) || LexicalNamedGraphs.attestationFileId(context) == null) {
                throw new WebAnnotationExportException(400, "WA_INVALID_CONTEXT",
                        "context must be an absolute attestation document graph IRI");
            }
            unique.add(context);
        }
        List<IRI> result = new ArrayList<IRI>();
        for (String value : unique) {
            result.add(iri(value));
        }
        return result;
    }

    int quoteContextLength() throws WebAnnotationExportException {
        String configured = LexOProperties.getProperty("webAnnotation.quoteContextLength", "50");
        try {
            int value = Integer.parseInt(configured.trim());
            if (value >= 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // Report a configuration error independently of client parameters.
        }
        throw new WebAnnotationExportException(500, "WA_INVALID_CONFIGURATION",
                "webAnnotation.quoteContextLength must be a non-negative integer");
    }

    WebAnnotationDocument export(RepositoryConnection lexical, RepositoryConnection text,
            List<IRI> requested, boolean includeMetadata, int window) throws ManagerException {
        List<IRI> graphs = requested;
        if (graphs == null) {
            graphs = new ArrayList<IRI>();
            try (RepositoryResult<Resource> ids = lexical.getContextIDs()) {
                while (ids.hasNext()) {
                    Resource graph = ids.next();
                    if (graph instanceof IRI && LexicalNamedGraphs.attestationFileId(graph.stringValue()) != null) {
                        graphs.add((IRI) graph);
                    }
                }
            }
            Collections.sort(graphs, Comparator.comparing(IRI::stringValue));
        }
        Map<String, Mapped> annotations = new TreeMap<String, Mapped>();
        for (IRI graph : graphs) {
            IRI textGraph = iri(textGraphBase + "documents/" + LexicalNamedGraphs.attestationFileId(graph.stringValue()));
            List<Resource> subjects = new ArrayList<Resource>();
            try (RepositoryResult<Statement> types = lexical.getStatements(
                    null, RDF.TYPE, iri(FRAC + "Attestation"), false, graph)) {
                while (types.hasNext()) {
                    Resource subject = types.next().getSubject();
                    requireIri(subject, subject, graph);
                    subjects.add(subject);
                }
            }
            Collections.sort(subjects, Comparator.comparing(Resource::stringValue));
            // Cache is local to this TEXT graph, never shared across documents.
            Map<Resource, Model> contextCache = new LinkedHashMap<Resource, Model>();
            for (int offset = 0; offset < subjects.size(); offset += BATCH_SIZE) {
                List<Resource> batch = subjects.subList(offset,
                        Math.min(subjects.size(), offset + BATCH_SIZE));
                Model attestations = manager.loadAttestationModel(lexical, graph, batch);
                Set<Resource> loci = new LinkedHashSet<Resource>();
                Set<Resource> contexts = new LinkedHashSet<Resource>();
                collect(attestations, iri(FRAC + "locus"), loci);
                collect(attestations, iri(FRAC + "observedIn"), contexts);
                Model locusModel = load(text, textGraph, loci);
                collect(locusModel, iri(NIF + "referenceContext"), contexts);
                Set<Resource> missing = new LinkedHashSet<Resource>(contexts);
                missing.removeAll(contextCache.keySet());
                Model loaded = load(text, textGraph, missing);
                for (Resource context : missing) {
                    contextCache.put(context, new LinkedHashModel(loaded.filter(context, null, null)));
                }
                for (Resource attestation : batch) {
                    Mapped current = map(attestation, graph, attestations, locusModel,
                            contextCache, window);
                    Mapped previous = annotations.get(attestation.stringValue());
                    if (previous == null) {
                        annotations.put(attestation.stringValue(), current);
                    } else if (!previous.signature.equals(current.signature)) {
                        throw invalid("WA_CONFLICTING_ATTESTATION", attestation, graph,
                                "duplicate differs from graph " + previous.graph);
                    } else {
                        previous.bodies.addAll(current.bodies);
                    }
                }
            }
        }
        WebAnnotationDocument result = new WebAnnotationDocument();
        result.context.add(CONTEXT);
        if (includeMetadata) {
            ObjectNode extension = JSON.createObjectNode().put("@version", 1.1);
            extension.putObject("metadata").put("@id", EXTENSION + "metadata").put("@type", "@json");
            extension.putObject("lexoProvenance").put("@id", EXTENSION + "provenance").put("@type", "@json");
            result.context.add(extension);
        }
        for (Mapped mapped : annotations.values()) {
            ArrayNode bodies = JSON.createArrayNode();
            for (String body : mapped.bodies) {
                bodies.addObject().put("id", body);
            }
            mapped.annotation.set("body", bodies.size() == 1 ? bodies.get(0) : bodies);
            if (!includeMetadata) {
                mapped.annotation.remove(Arrays.asList("metadata", "lexoProvenance"));
            }
            result.graph.add(mapped.annotation);
        }
        return result;
    }

    private Model load(RepositoryConnection connection, IRI graph, Set<Resource> resources) {
        Model result = new LinkedHashModel();
        List<Resource> list = new ArrayList<Resource>(resources);
        for (int offset = 0; offset < list.size(); offset += BATCH_SIZE) {
            result.addAll(manager.loadResourceModel(connection, graph,
                    list.subList(offset, Math.min(list.size(), offset + BATCH_SIZE))));
        }
        return result;
    }

    private void collect(Model model, IRI predicate, Set<Resource> result) {
        for (Value value : model.filter(null, predicate, null).objects()) {
            if (value instanceof IRI && absolute(value.stringValue())) {
                result.add((Resource) value);
            }
        }
    }

    private Mapped map(Resource attestation, IRI graph, Model model, Model loci,
            Map<Resource, Model> contexts, int window) throws ManagerException {
        requireIri(attestation, attestation, graph);
        if (!model.contains(attestation, RDF.TYPE, iri(FRAC + "Attestation"))) {
            throw invalid("WA_INCOMPLETE_ATTESTATION", attestation, graph,
                    "attestation type is missing from the loaded document graph");
        }
        Mapped result = new Mapped();
        result.graph = graph.stringValue();
        for (Resource body : model.filter(null, iri(FRAC + "attestation"), attestation).subjects()) {
            if (!(body instanceof IRI) || !absolute(body.stringValue())) {
                throw invalid("WA_MISSING_BODY", attestation, graph, "Body must be an absolute IRI");
            }
            result.bodies.add(body.stringValue());
        }
        if (result.bodies.isEmpty()) {
            throw invalid("WA_MISSING_BODY", attestation, graph, "no lexical Body");
        }
        IRI locus = requireIri(one(model, attestation, iri(FRAC + "locus"), true,
                attestation, graph), attestation, graph);
        IRI observed = requireIri(one(model, attestation, iri(FRAC + "observedIn"), true,
                attestation, graph), attestation, graph);
        Value reference = one(loci, locus, iri(NIF + "referenceContext"), false, attestation, graph);
        IRI context = reference == null ? observed : requireIri(reference, attestation, graph);
        Model contextModel = contexts.get(context);
        if (contextModel == null || !contextModel.contains(context, RDF.TYPE, iri(NIF + "Context"))) {
            throw invalid("WA_CANONICAL_TEXT_UNAVAILABLE", attestation, graph, "missing NIF context " + context);
        }
        Model observedModel = contexts.get(observed);
        if (!observed.equals(context) && observedModel != null
                && observedModel.contains(observed, RDF.TYPE, iri(NIF + "Context"))) {
            throw invalid("WA_INCONSISTENT_SOURCE", attestation, graph, "observedIn identifies a different NIF context");
        }
        Value canonicalValue = one(contextModel, context, iri(NIF + "isString"), false, attestation, graph);
        if (!(canonicalValue instanceof Literal)) {
            throw invalid("WA_CANONICAL_TEXT_UNAVAILABLE", attestation, graph, "missing canonical literal for " + context);
        }
        Literal canonical = (Literal) canonicalValue;
        int[] positions = offsets(loci, locus, attestation, graph);
        int start = positions[0];
        int end = positions[1];
        String text = canonical.getLabel();
        int length = text.codePointCount(0, text.length());
        if (start < 0 || end <= start || end > length) {
            throw invalid("WA_INVALID_OFFSETS", attestation, graph, "offsets outside the canonical code-point interval");
        }
        String source = base(locus);
        if (!source.equals(base(context))) {
            throw invalid("WA_INCONSISTENT_SOURCE", attestation, graph, "locus and context identify different textual sources");
        }
        for (IRI predicate : Arrays.asList(DCTERMS.CREATOR, DCTERMS.CREATED, DCTERMS.MODIFIED)) {
            one(model, attestation, predicate, false, attestation, graph);
        }
        JsonNode metadata = JSON.valueToTree(manager.readMetadata(model, attestation));
        JsonNode provenance = JSON.valueToTree(manager.readProvenance(model, attestation));
        result.signature = Arrays.<Object>asList(locus, observed, context, canonical,
                start, end, metadata, provenance);
        ObjectNode annotation = JSON.createObjectNode();
        annotation.put("id", attestation.stringValue()).put("type", "Annotation")
                .put("motivation", MOTIVATION);
        ObjectNode target = annotation.putObject("target");
        target.put("type", "SpecificResource").put("source", source);
        ArrayNode selectors = target.putArray("selector");
        selectors.addObject().put("type", "TextPositionSelector").put("start", start).put("end", end);
        selectors.addObject().put("type", "TextQuoteSelector")
                .put("exact", manager.unicodeSubstring(text, start, end))
                .put("prefix", manager.unicodeSubstring(text, Math.max(0, start - window), start))
                .put("suffix", manager.unicodeSubstring(text, end, (int) Math.min((long) length, (long) end + window)));
        selectors.addObject().put("type", "FragmentSelector").put("value", "char=" + start + "," + end)
                .put("conformsTo", "http://tools.ietf.org/rfc/rfc5147");
        annotation.set("metadata", metadata);
        annotation.set("lexoProvenance", provenance);
        result.annotation = annotation;
        return result;
    }

    private int[] offsets(Model model, IRI locus, Resource attestation, IRI graph)
            throws WebAnnotationExportException {
        Value begin = one(model, locus, iri(NIF + "beginIndex"), false, attestation, graph);
        Value end = one(model, locus, iri(NIF + "endIndex"), false, attestation, graph);
        try {
            int[] nif = null;
            if (begin != null || end != null) {
                if (!(begin instanceof Literal) || !(end instanceof Literal)
                        || !begin.stringValue().matches("[+]?[0-9]+")
                        || !end.stringValue().matches("[+]?[0-9]+")) {
                    throw new IllegalArgumentException();
                }
                nif = new int[]{Integer.parseInt(begin.stringValue()), Integer.parseInt(end.stringValue())};
            }
            String fragment = new URI(locus.stringValue()).getFragment();
            int[] rfc = null;
            if (fragment != null && fragment.startsWith("char=")) {
                Matcher matcher = CHAR.matcher(fragment);
                if (!matcher.matches()) {
                    throw new IllegalArgumentException();
                }
                rfc = new int[]{Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
            }
            if ((nif == null && rfc == null) || (nif != null && rfc != null && !Arrays.equals(nif, rfc))) {
                throw new IllegalArgumentException();
            }
            return nif != null ? nif : rfc;
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw invalid("WA_INVALID_OFFSETS", attestation, graph, "missing, malformed, or inconsistent locus/NIF offsets");
        }
    }

    private Value one(Model model, Resource subject, IRI predicate, boolean required,
            Resource attestation, IRI graph) throws WebAnnotationExportException {
        Set<Value> values = model.filter(subject, predicate, null).objects();
        if (values.size() > 1) {
            throw invalid("WA_INCONSISTENT_SOURCE", attestation, graph, "multiple values for " + predicate);
        }
        if (values.isEmpty()) {
            if (required) {
                throw invalid("WA_INCOMPLETE_ATTESTATION", attestation, graph, "missing " + predicate);
            }
            return null;
        }
        return values.iterator().next();
    }

    private IRI requireIri(Value value, Resource attestation, IRI graph) throws WebAnnotationExportException {
        if (!(value instanceof IRI) || !absolute(value.stringValue())) {
            throw invalid("WA_INCOMPLETE_ATTESTATION", attestation, graph, "expected an absolute structural IRI");
        }
        return (IRI) value;
    }

    private static String base(IRI iri) {
        // Parse before removal; preserve the original escaped form (including opaque IRIs).
        URI parsed = URI.create(iri.stringValue());
        String value = parsed.toString();
        return parsed.getRawFragment() == null ? value : value.substring(0, value.indexOf('#'));
    }

    private static boolean absolute(String value) {
        if (value == null || value.trim().isEmpty()) {
            return false;
        }
        try {
            return new URI(value).isAbsolute();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static IRI iri(String value) {
        return SimpleValueFactory.getInstance().createIRI(value);
    }

    private static WebAnnotationExportException invalid(String code, Resource attestation,
            IRI graph, String detail) {
        return new WebAnnotationExportException(422, code,
                "attestation=" + attestation + " graph=" + graph + ": " + detail);
    }

    private static final class Mapped {
        String graph;
        List<Object> signature;
        ObjectNode annotation;
        Set<String> bodies = new TreeSet<String>();
    }
}
