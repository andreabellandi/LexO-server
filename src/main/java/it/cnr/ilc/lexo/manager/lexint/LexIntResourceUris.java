package it.cnr.ilc.lexo.manager.lexint;

import it.cnr.ilc.lexo.LexOProperties;
import it.cnr.ilc.lexo.manager.text.Iso639LanguageValidator;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/** Collision-resistant runtime IRIs and server-derived snapshot graph IRIs. */
public final class LexIntResourceUris {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final Pattern VERSION_CODE = Pattern.compile("v[0-9]{6}");
    private final ValueFactory vf = SimpleValueFactory.getInstance();
    private final String base;
    private final String snapshotsBase;

    public LexIntResourceUris() {
        this(LexOProperties.getProperty("LexInt.resourceNamespace",
                "https://lexo.ilc.cnr.it/id/lexint/"),
                LexOProperties.getProperty("GraphDb.lexintSnapshotsBase",
                "https://lexo.ilc.cnr.it/graphs/lexical/lexint/snapshots/"));
    }

    public LexIntResourceUris(String base, String snapshotsBase) {
        this.base = requireSafeBase(base, "LexInt.resourceNamespace");
        this.snapshotsBase = requireSafeBase(snapshotsBase,
                "GraphDb.lexintSnapshotsBase");
    }

    public IRI mint(String kind) {
        return vf.createIRI(base + kind + "/" + UUID.randomUUID().toString());
    }

    public IRI resource(String kind, String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) {
            throw LexIntException.invalid("A canonical lowercase UUID is required");
        }
        return vf.createIRI(base + kind + "/" + id);
    }

    public String id(IRI iri) {
        String value = iri.stringValue();
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    public IRI snapshotGraph(String language, String code) {
        String canonical = language(language);
        if (code == null || !VERSION_CODE.matcher(code).matches()) {
            throw LexIntException.invalid("Invalid server version code");
        }
        return vf.createIRI(snapshotsBase + canonical + "/" + code);
    }

    public static String language(String value) {
        try {
            return Iso639LanguageValidator.get().requireValid(value);
        } catch (IllegalArgumentException ex) {
            throw LexIntException.invalid(
                    "language must be a supported ISO 639 code");
        }
    }

    public IRI agent(LexIntActor actor) {
        String stable = actor.authenticated && actor.issuer != null
                && actor.subject != null
                ? actor.issuer + "|" + actor.subject
                : "declared|" + actor.username;
        return vf.createIRI(base + "agent/" + sha256(stable));
    }

    public String opaqueEtag() {
        return "\"" + UUID.randomUUID().toString() + "\"";
    }

    public static int limit(String key, int fallback) {
        try {
            int value = Integer.parseInt(LexOProperties.getProperty(key,
                    Integer.toString(fallback)));
            return value > 0 ? value : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String requireSafeBase(String value, String property) {
        if (value == null || !value.endsWith("/") || value.contains("../")
                || value.contains("./")) {
            throw new IllegalStateException(property
                    + " must be an absolute safe IRI base ending in '/'");
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalStateException(property
                        + " must use an absolute HTTP(S) IRI");
            }
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Invalid " + property, ex);
        }
        return value;
    }
}
