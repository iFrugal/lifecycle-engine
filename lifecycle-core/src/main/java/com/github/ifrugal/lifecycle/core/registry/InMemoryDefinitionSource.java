package com.github.ifrugal.lifecycle.core.registry;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Documents held in memory; for tests and for embedding rules built in code. */
public final class InMemoryDefinitionSource implements DefinitionSource {

    private volatile List<RuleSetDocument> docs;
    private final AtomicLong revision = new AtomicLong();

    public InMemoryDefinitionSource(Collection<RuleSetDocument> docs) {
        this.docs = List.copyOf(docs);
    }

    public InMemoryDefinitionSource(RuleSetDocument... docs) {
        this(List.of(docs));
    }

    public void set(Collection<RuleSetDocument> docs) {
        this.docs = List.copyOf(docs);
        revision.incrementAndGet();
    }

    public void add(RuleSetDocument doc) {
        List<RuleSetDocument> next = new ArrayList<>(docs);
        next.add(doc);
        set(next);
    }

    @Override
    public Collection<RuleSetDocument> load() {
        return docs;
    }

    @Override
    public String fingerprint() {
        return "mem-" + revision.get();
    }
}
