package com.github.ifrugal.lifecycle.api.spi;

import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;

import java.util.List;
import java.util.Optional;

/** Read side of the audit (R8). May be eventually consistent with the store's commit. */
public interface AuditQuery {

    List<AuditRecord> byEntity(EntityRef ref);

    Optional<AuditRecord> byEventId(String eventId);

    List<AuditRecord> byCorrelation(String correlationId);
}
