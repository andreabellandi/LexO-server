package it.cnr.ilc.lexo.manager.wordnet;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Validates non-recursive detail projections. */
final class WordnetSectionParser {

    private WordnetSectionParser() {
    }

    static Set<String> parse(String sections, String... allowed) {
        Set<String> allowedSet = new LinkedHashSet<String>(Arrays.asList(allowed));
        allowedSet.add("core");
        allowedSet.add("metadata");
        if (sections == null || "all".equals(sections.trim())) {
            return allowedSet;
        }
        Set<String> result = new LinkedHashSet<String>();
        for (String token : sections.split(",", -1)) {
            String value = token.trim();
            if (value.isEmpty() || "all".equals(value)
                    || !allowedSet.contains(value) || !result.add(value)) {
                throw new WordnetException(400, "INVALID_SECTION",
                        "Invalid or duplicate section: " + value);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
