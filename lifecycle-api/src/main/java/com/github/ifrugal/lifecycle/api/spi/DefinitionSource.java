package com.github.ifrugal.lifecycle.api.spi;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;

import java.util.Collection;

/** Where rule documents come from (DD-05): files, a table, a collection, memory, or a composite of those. */
public interface DefinitionSource {

    /** Parsed but not compiled documents: bases (no tenant) and tenant overlays. */
    Collection<RuleSetDocument> load();

    /** Changes if and only if {@link #load()} would return something different. Drives cheap polling. */
    String fingerprint();
}
