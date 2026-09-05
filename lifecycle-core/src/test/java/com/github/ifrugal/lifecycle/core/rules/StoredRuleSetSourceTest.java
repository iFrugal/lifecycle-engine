package com.github.ifrugal.lifecycle.core.rules;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;
import com.github.ifrugal.lifecycle.core.registry.StoredRuleSetSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DD-05: a stored rule set is parsed with the shared {@link RuleSetParser}; the row is authoritative for tenant/type. */
class StoredRuleSetSourceTest {

    /** Ignores tenant/type in the body; the row overrides them per {@link StoredRuleSetSource#load()}. */
    private static final RuleSetParser STUB_PARSER = (body, format) ->
            new RuleSetDocument(null, "ignored-by-row", "NEW", null, List.of("NEW", "DONE"), List.of());

    private static final class TestSource extends StoredRuleSetSource {
        private final List<StoredRuleSet> rows;

        TestSource(List<StoredRuleSet> rows) {
            super(STUB_PARSER);
            this.rows = rows;
        }

        @Override
        protected List<StoredRuleSet> fetchActive() {
            return rows;
        }
    }

    @Test
    void loadOverridesTenantAndTypeFromTheRowNotTheParsedBody() {
        StoredRuleSetSource.StoredRuleSet row = new StoredRuleSetSource.StoredRuleSet("acme", "order", 3, "yaml", "body-content");
        TestSource source = new TestSource(List.of(row));

        Collection<RuleSetDocument> docs = source.load();
        assertThat(docs).hasSize(1);
        RuleSetDocument doc = docs.iterator().next();
        assertThat(doc.tenantId()).isEqualTo("acme");
        assertThat(doc.entityType()).isEqualTo("order");
        // everything else comes from the parsed body
        assertThat(doc.initial()).isEqualTo("NEW");
        assertThat(doc.states()).containsExactly("NEW", "DONE");
    }

    @Test
    void loadOfBaseRowHasNullTenant() {
        StoredRuleSetSource.StoredRuleSet row = new StoredRuleSetSource.StoredRuleSet(null, "shipment", 1, "yaml", "body");
        TestSource source = new TestSource(List.of(row));
        RuleSetDocument doc = source.load().iterator().next();
        assertThat(doc.tenantId()).isNull();
        assertThat(doc.entityType()).isEqualTo("shipment");
    }

    @Test
    void fingerprintIsStableRegardlessOfRowOrder() {
        StoredRuleSetSource.StoredRuleSet a = new StoredRuleSetSource.StoredRuleSet(null, "order", 1, "yaml", "a-body");
        StoredRuleSetSource.StoredRuleSet b = new StoredRuleSetSource.StoredRuleSet("acme", "order", 2, "yaml", "b-body");

        TestSource inOrder = new TestSource(List.of(a, b));
        TestSource reversed = new TestSource(List.of(b, a));

        assertThat(inOrder.fingerprint()).isEqualTo(reversed.fingerprint());
    }

    @Test
    void fingerprintChangesWhenAVersionChanges() {
        StoredRuleSetSource.StoredRuleSet v1 = new StoredRuleSetSource.StoredRuleSet(null, "order", 1, "yaml", "body");
        StoredRuleSetSource.StoredRuleSet v2 = new StoredRuleSetSource.StoredRuleSet(null, "order", 2, "yaml", "body");

        TestSource before = new TestSource(new ArrayList<>(List.of(v1)));
        TestSource after = new TestSource(new ArrayList<>(List.of(v2)));

        assertThat(before.fingerprint()).isNotEqualTo(after.fingerprint());
    }

    @Test
    void fingerprintIsUnaffectedByBodyContentAlone() {
        // fingerprint is derived from tenant/type/version only, not the body - a version bump is the
        // signal that something changed, matching the documented "changes iff load() would" contract
        // at the granularity the store's ACTIVE-row versioning provides.
        StoredRuleSetSource.StoredRuleSet a = new StoredRuleSetSource.StoredRuleSet(null, "order", 1, "yaml", "body-a");
        StoredRuleSetSource.StoredRuleSet b = new StoredRuleSetSource.StoredRuleSet(null, "order", 1, "yaml", "body-b");
        assertThat(new TestSource(List.of(a)).fingerprint()).isEqualTo(new TestSource(List.of(b)).fingerprint());
    }
}
