package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Delegates to another source but reports a fixed-width fingerprint.
 *
 * <p>The snapshot version <em>is</em> the source fingerprint, and it is written to every audit row. A
 * {@code CompositeDefinitionSource} joins its members' fingerprints with {@code +}, so two SHA-256 sources
 * already produce 129 characters — one more than the 128 that {@code lifecycle_audit.rule_set_version} holds,
 * and the commit fails on the database rather than anywhere useful. Re-hashing keeps the version 64 characters
 * however many sources an application configures.
 *
 * <p>The contract survives: the digest changes if and only if the delegate's fingerprint does, which is exactly
 * when {@code load()} would return something different.
 */
final class HashedFingerprintSource implements DefinitionSource {

    private final DefinitionSource delegate;

    HashedFingerprintSource(DefinitionSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Collection<RuleSetDocument> load() {
        return delegate.load();
    }

    @Override
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(delegate.fingerprint().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "HashedFingerprintSource[" + delegate + "]";
    }
}
