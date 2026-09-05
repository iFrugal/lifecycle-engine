package com.github.ifrugal.lifecycle.rules.yaml;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Rule documents read from an explicit list of classpath resources (DD-05). No attempt is made to discover
 * resources by scanning a classpath "directory" — the caller must name every resource. Resources are processed
 * in sorted, deduplicated order so {@link #load()} and {@link #fingerprint()} are reproducible.
 */
public final class ClasspathDefinitionSource implements DefinitionSource {

    private final List<String> resources;
    private final ClassLoader classLoader;
    private final RuleSetParser parser;

    public ClasspathDefinitionSource(List<String> resources, ClassLoader classLoader) {
        this(resources, classLoader, new YamlRuleSetParser());
    }

    public ClasspathDefinitionSource(List<String> resources, ClassLoader classLoader, RuleSetParser parser) {
        this.resources = List.copyOf(resources);
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    @Override
    public Collection<RuleSetDocument> load() {
        List<RuleSetDocument> out = new ArrayList<>();
        for (String name : sortedResources()) {
            String body = readResource(name);
            try {
                out.add(parser.parse(body, RuleFiles.extensionOf(name)));
            } catch (RuleSetParseException e) {
                throw new RuleSetParseException("failed to parse classpath resource " + name + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    @Override
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (String name : sortedResources()) {
            sb.append(name).append('\n').append(readResource(name)).append('\n');
        }
        return RuleFiles.sha256Hex(sb.toString());
    }

    private List<String> sortedResources() {
        return new ArrayList<>(new TreeSet<>(resources));
    }

    private String readResource(String name) {
        try (InputStream in = classLoader.getResourceAsStream(name)) {
            if (in == null) {
                throw new RuleSetParseException("classpath resource not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuleSetParseException("failed to read classpath resource: " + name, e);
        }
    }
}
