package com.github.ifrugal.lifecycle.core.registry;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Rules stored as documents in a database (DD-05). Extend and implement {@link #fetchActive()}; bodies are parsed
 * with the same {@link RuleSetParser} as files, so a stored rule set is byte-for-byte a file rule set.
 */
public abstract class StoredRuleSetSource implements DefinitionSource {

    /** One ACTIVE row. {@code tenantId} null means a base rule set. */
    public record StoredRuleSet(String tenantId, String entityType, int version, String format, String body) {
        public StoredRuleSet {
            Objects.requireNonNull(entityType, "entityType");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(body, "body");
        }
    }

    private final RuleSetParser parser;

    protected StoredRuleSetSource(RuleSetParser parser) {
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    /** Every row whose status is ACTIVE. At most one per (tenantId, entityType); the store enforces it. */
    protected abstract List<StoredRuleSet> fetchActive();

    @Override
    public Collection<RuleSetDocument> load() {
        List<RuleSetDocument> out = new ArrayList<>();
        for (StoredRuleSet row : fetchActive()) {
            RuleSetDocument parsed = parser.parse(row.body(), row.format());
            // The row is authoritative for tenant and type; the body must agree or the compiler will say so.
            out.add(new RuleSetDocument(row.tenantId(), row.entityType(), parsed.initial(), parsed.maxHops(), parsed.states(), parsed.transitions()));
        }
        return out;
    }

    @Override
    public String fingerprint() {
        List<StoredRuleSet> rows = new ArrayList<>(fetchActive());
        rows.sort(Comparator.comparing((StoredRuleSet r) -> r.tenantId() == null ? "" : r.tenantId())
                .thenComparing(StoredRuleSet::entityType));
        StringBuilder sb = new StringBuilder();
        for (StoredRuleSet r : rows) {
            sb.append(r.tenantId() == null ? "" : r.tenantId()).append('|').append(r.entityType()).append('|').append(r.version()).append('\n');
        }
        return sha256(sb.toString());
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
