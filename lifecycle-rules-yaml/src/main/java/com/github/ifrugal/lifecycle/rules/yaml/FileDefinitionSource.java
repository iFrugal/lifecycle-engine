package com.github.ifrugal.lifecycle.rules.yaml;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Rule documents read from files or directories (DD-05). Each root may be a single file or a directory walked
 * recursively for {@code .yaml}/{@code .yml}/{@code .json} files. Files are processed in a deterministic order
 * (sorted by path relative to their root) so {@link #load()} and {@link #fingerprint()} are reproducible.
 */
public final class FileDefinitionSource implements DefinitionSource {

    private final List<Path> roots;
    private final RuleSetParser parser;

    public FileDefinitionSource(List<Path> roots) {
        this(roots, new YamlRuleSetParser());
    }

    public FileDefinitionSource(List<Path> roots, RuleSetParser parser) {
        this.roots = List.copyOf(roots);
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public static FileDefinitionSource of(Path... roots) {
        return new FileDefinitionSource(List.of(roots));
    }

    @Override
    public Collection<RuleSetDocument> load() {
        List<RuleSetDocument> out = new ArrayList<>();
        for (Map.Entry<String, Path> entry : resolve().entrySet()) {
            String body = readFile(entry.getValue());
            try {
                out.add(parser.parse(body, RuleFiles.extensionOf(entry.getKey())));
            } catch (RuleSetParseException e) {
                throw new RuleSetParseException("failed to parse " + entry.getValue() + ": " + e.getMessage(), e);
            }
        }
        return out;
    }

    @Override
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Path> entry : resolve().entrySet()) {
            sb.append(entry.getKey()).append('\n').append(readFile(entry.getValue())).append('\n');
        }
        return RuleFiles.sha256Hex(sb.toString());
    }

    /** Relative path (sorted, slash-normalised) -> file, deduplicated across roots. */
    private Map<String, Path> resolve() {
        Map<String, Path> byRelativePath = new TreeMap<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                throw new RuleSetParseException("rule source root does not exist: " + root);
            }
            if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> RuleFiles.hasRuleExtension(p.getFileName().toString()))
                            .forEach(p -> byRelativePath.put(RuleFiles.normalizeSeparators(root.relativize(p).toString()), p));
                } catch (IOException e) {
                    throw new RuleSetParseException("failed to walk rule source root: " + root, e);
                }
            } else if (RuleFiles.hasRuleExtension(root.getFileName().toString())) {
                byRelativePath.put(RuleFiles.normalizeSeparators(root.getFileName().toString()), root);
            }
        }
        return byRelativePath;
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuleSetParseException("failed to read rule set file: " + path, e);
        }
    }
}
