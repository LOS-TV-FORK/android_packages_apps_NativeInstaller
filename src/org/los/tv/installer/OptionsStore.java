package org.los.tv.installer;

import java.util.LinkedHashMap;
import java.util.Map;

/** Selected option tokens shared across the option screens. */
public final class OptionsStore {
    private OptionsStore() {}

    private static final Map<String, String> TOKENS = new LinkedHashMap<>();

    public static synchronized void set(String key, String token) {
        if (token == null) {
            TOKENS.remove(key);
        } else {
            TOKENS.put(key, token);
        }
    }

    public static synchronized boolean has(String key) {
        return TOKENS.containsKey(key);
    }

    public static synchronized String get(String key) {
        return TOKENS.get(key);
    }

    public static synchronized String all() {
        StringBuilder sb = new StringBuilder();
        for (String t : TOKENS.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    public static synchronized int count() {
        return TOKENS.size();
    }

    public static synchronized void clearGroup(String prefix) {
        TOKENS.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
