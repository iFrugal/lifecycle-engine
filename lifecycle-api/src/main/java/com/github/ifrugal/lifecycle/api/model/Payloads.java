package com.github.ifrugal.lifecycle.api.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deep-immutable copies of JSON-shaped payloads and dotted-path access into them. */
public final class Payloads {

    private Payloads() {}

    /** Returns a deeply unmodifiable copy. {@code null} becomes an empty map. Null values are preserved. */
    public static Map<String, Object> immutable(Map<String, ?> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, freeze(v)));
        return Collections.unmodifiableMap(out);
    }

    /** Deep-freezes any value: maps and collections become unmodifiable, everything else is returned as is. */
    public static Object freeze(Object v) {
        if (v instanceof Map<?, ?> m) {
            LinkedHashMap<String, Object> o = new LinkedHashMap<>();
            m.forEach((k, x) -> o.put(String.valueOf(k), freeze(x)));
            return Collections.unmodifiableMap(o);
        }
        if (v instanceof Collection<?> c) {
            List<Object> l = new ArrayList<>(c.size());
            c.forEach(x -> l.add(freeze(x)));
            return Collections.unmodifiableList(l);
        }
        return v;
    }

    /** Resolves {@code a.b.c} against nested maps. Absent at any level, or a non-map on the way, is empty. */
    public static Optional<Object> path(Map<String, ?> root, String dotted) {
        if (root == null || dotted == null || dotted.isEmpty()) {
            return Optional.empty();
        }
        Object cur = root;
        for (String seg : dotted.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return Optional.empty();
            }
            cur = m.get(seg);
            if (cur == null) {
                return Optional.empty();
            }
        }
        return Optional.of(cur);
    }
}
