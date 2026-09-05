package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;
import com.github.ifrugal.lifecycle.rules.yaml.ClasspathDefinitionSource;
import com.github.ifrugal.lifecycle.rules.yaml.FileDefinitionSource;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code lifecycle.rules.files} strings into {@code DefinitionSource}s.
 *
 * <p>Two prefixes are understood. {@code file:} (or a bare path) becomes a {@link FileDefinitionSource}, which
 * accepts a single file or a directory walked recursively. {@code classpath:} normally names one resource
 * exactly, and every such entry is collected into a single {@link ClasspathDefinitionSource}: a classpath
 * directory cannot be listed in the general case, so nothing is scanned.
 *
 * <p>The one concession: an entry whose resource resolves to a {@code file:} URL that is a real directory — the
 * everyday exploded-classes case of {@code target/test-classes/rules/} — is walked as a filesystem directory
 * instead. Inside a jar the same entry is refused with a message telling you to list the resources, because
 * silently loading nothing is the worse failure.
 */
final class RuleFileSources {

    static final String CLASSPATH_PREFIX = "classpath:";
    static final String FILE_PREFIX = "file:";

    private RuleFileSources() {}

    static List<DefinitionSource> build(List<String> locations, ClassLoader classLoader, RuleSetParser parser) {
        List<DefinitionSource> sources = new ArrayList<>();
        List<String> classpathResources = new ArrayList<>();

        for (String raw : locations) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String location = raw.strip();
            if (location.startsWith(CLASSPATH_PREFIX)) {
                String name = trimLeadingSlash(location.substring(CLASSPATH_PREFIX.length()));
                Path directory = classpathDirectory(name, classLoader);
                if (directory != null) {
                    sources.add(new FileDefinitionSource(List.of(directory), parser));
                } else {
                    classpathResources.add(name);
                }
            } else {
                String path = location.startsWith(FILE_PREFIX) ? location.substring(FILE_PREFIX.length()) : location;
                sources.add(new FileDefinitionSource(List.of(Path.of(path)), parser));
            }
        }

        if (!classpathResources.isEmpty()) {
            sources.add(new ClasspathDefinitionSource(classpathResources, classLoader, parser));
        }
        return sources;
    }

    /**
     * @return the directory this classpath entry names when it is an unpacked directory, else null (meaning
     *         "treat it as one named resource")
     */
    private static Path classpathDirectory(String name, ClassLoader classLoader) {
        boolean looksLikeDirectory = name.endsWith("/");
        String lookup = looksLikeDirectory ? name.substring(0, name.length() - 1) : name;
        URL url = classLoader.getResource(lookup);
        if (url == null) {
            if (looksLikeDirectory) {
                throw new IllegalStateException("classpath rule directory not found: " + name);
            }
            // Let ClasspathDefinitionSource produce the "resource not found" failure, with its own wording.
            return null;
        }
        if (!"file".equals(url.getProtocol())) {
            if (looksLikeDirectory) {
                throw new IllegalStateException("classpath rule directory '" + name + "' resolves to " + url
                        + ", which cannot be listed; name each resource explicitly, e.g. classpath:rules/order.yaml");
            }
            return null;
        }
        Path path = toPath(url, name);
        if (!Files.isDirectory(path)) {
            if (looksLikeDirectory) {
                throw new IllegalStateException("classpath rule directory '" + name + "' is not a directory: " + path);
            }
            return null;
        }
        return path;
    }

    private static Path toPath(URL url, String name) {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot resolve classpath rule location '" + name + "' (" + url + ")", e);
        }
    }

    private static String trimLeadingSlash(String name) {
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
