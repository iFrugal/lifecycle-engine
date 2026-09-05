package com.github.ifrugal.lifecycle.rules.yaml;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Shared helpers for the file and classpath {@code DefinitionSource} implementations. */
final class RuleFiles {

    static final Set<String> EXTENSIONS = Set.of("yaml", "yml", "json");

    private RuleFiles() {}

    static boolean hasRuleExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new IllegalArgumentException("no file extension: " + name);
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Forward slashes only, so a fingerprint is stable across operating systems. */
    static String normalizeSeparators(String path) {
        return path.replace('\\', '/');
    }

    static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
