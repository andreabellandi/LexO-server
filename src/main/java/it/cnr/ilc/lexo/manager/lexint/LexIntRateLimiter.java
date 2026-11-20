package it.cnr.ilc.lexo.manager.lexint;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded per-identity fixed-window protection for the LexInt REST surface. */
public final class LexIntRateLimiter {
    private static final long WINDOW_MILLIS = 60_000L;
    private static final Map<String, Bucket> BUCKETS =
            new LinkedHashMap<String, Bucket>(128, 0.75f, true);

    public void check(LexIntActor actor) {
        if (actor == null) {
            throw new LexIntException(401, "LEXINT_AUTH_REQUIRED",
                    "Actor context is required");
        }
        int maximum = LexIntResourceUris.limit(
                "LexInt.rateLimit.requestsPerMinute", 600);
        int maximumIdentities = LexIntResourceUris.limit(
                "LexInt.rateLimit.maxIdentities", 10000);
        long now = System.currentTimeMillis();
        long window = now / WINDOW_MILLIS;
        String identity = identity(actor);
        synchronized (BUCKETS) {
            evictExpired(window);
            Bucket bucket = BUCKETS.get(identity);
            if (bucket == null) {
                while (BUCKETS.size() >= maximumIdentities) {
                    Iterator<String> eldest = BUCKETS.keySet().iterator();
                    if (!eldest.hasNext()) { break; }
                    eldest.next();
                    eldest.remove();
                }
                bucket = new Bucket(window);
                BUCKETS.put(identity, bucket);
            } else if (bucket.window != window) {
                bucket.window = window;
                bucket.count = 0;
            }
            if (bucket.count >= maximum) {
                long retryAfter = 60L - (now / 1000L) % 60L;
                throw new LexIntException(429, "LEXINT_RATE_LIMITED",
                        "LexInt request rate limit exceeded",
                        Collections.<String, Object>singletonMap(
                                "retryAfterSeconds", retryAfter));
            }
            bucket.count++;
        }
    }

    private String identity(LexIntActor actor) {
        String stable = actor.authenticated
                ? value(actor.issuer) + "|" + value(actor.subject)
                : "declared|" + value(actor.username);
        return LexIntResourceUris.sha256(stable);
    }

    private void evictExpired(long currentWindow) {
        Iterator<Map.Entry<String, Bucket>> entries =
                BUCKETS.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().window != currentWindow) {
                entries.remove();
            }
        }
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private static final class Bucket {
        long window;
        int count;
        Bucket(long window) { this.window = window; }
    }
}
