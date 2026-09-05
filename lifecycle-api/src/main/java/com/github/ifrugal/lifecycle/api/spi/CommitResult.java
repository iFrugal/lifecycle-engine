package com.github.ifrugal.lifecycle.api.spi;

public sealed interface CommitResult permits CommitResult.Committed, CommitResult.VersionMismatch, CommitResult.AlreadyApplied {

    /** {@code version} is the record's version after the commit (unchanged when the commit did not advance). */
    record Committed(long version, String auditId) implements CommitResult {}

    record VersionMismatch(long expected, long actual) implements CommitResult {}

    /** The (entity, eventId) pair was already in the inbox. */
    record AlreadyApplied(String firstAuditId) implements CommitResult {}
}
