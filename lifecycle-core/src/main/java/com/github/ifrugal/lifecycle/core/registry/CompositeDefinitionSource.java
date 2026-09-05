package com.github.ifrugal.lifecycle.core.registry;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;

import java.util.ArrayList;
import java.util.Collection;
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

    @Override
    public String fingerprint() {
        return sources.stream().map(DefinitionSource::fingerprint).collect(Collectors.joining("+"));
    }
}
