package com.github.ifrugal.lifecycle.core.registry;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** Concatenates sources, e.g. base rule sets from files and tenant overlays from a table (DD-05). */
public final class CompositeDefinitionSource implements DefinitionSource {

    private final List<DefinitionSource> sources;

    public CompositeDefinitionSource(List<DefinitionSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public CompositeDefinitionSource(DefinitionSource... sources) {
        this(List.of(sources));
    }

    @Override
    public Collection<RuleSetDocument> load() {
        List<RuleSetDocument> all = new ArrayList<>();
        for (DefinitionSource s : sources) {
            all.addAll(s.load());
        }
        return all;
    }

    /** SHA-256 over the member fingerprints, so the result has a fixed length whatever the members return. */
    @Override
    public String fingerprint() {
        String joined = sources.stream().map(DefinitionSource::fingerprint).collect(Collectors.joining("+"));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
