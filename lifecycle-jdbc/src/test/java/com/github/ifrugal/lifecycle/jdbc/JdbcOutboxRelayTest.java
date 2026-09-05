package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DD-07: a crash between commit and publish leaves rows with no {@code sent_at}, and the relay is what repairs
 * it. Draining twice must publish once.
 */
class JdbcOutboxRelayTest {

    private static final DataSource DS = TestDatabases.h2();

    private JdbcStateStore store;
    private InMemoryTransport transport;
    private final List<LifecycleEvent> delivered = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        SchemaInstaller.install(DS, Dialect.H2);
        TestDatabases.truncateAll(DS);
        store = new JdbcStateStore(DS, Dialect.H2);
        transport = new InMemoryTransport();
        transport.subscribe(delivered::add);
        delivered.clear();
    }

    @AfterEach
    void tearDown() {
        transport.close();
    }

    @Test
    void theRelayPublishesEveryUnsentEventOnceAndMarksItSent() {
        EntityRef ref = EntityRef.of("order", "o-1");
        commitWithOutbox(ref, "emit-1", "emit-2", "emit-3");
        assertThat(store.outbox().orElseThrow().unsent(10)).hasSize(3);

        JdbcOutboxRelay relay = new JdbcOutboxRelay(store.outbox().orElseThrow(), transport, 2);
        relay.run();

        await().atMost(Duration.ofSeconds(5)).until(() -> delivered.size() == 3);
        assertThat(delivered).extracting(LifecycleEvent::eventId).containsExactly("emit-1", "emit-2", "emit-3");
        assertThat(delivered).allSatisfy(e -> assertThat(e.entity()).isEqualTo(ref));

        assertThat(store.outbox().orElseThrow().unsent(10)).as("everything is marked sent").isEmpty();
        assertThat(TestDatabases.scalar(DS, "select count(*) from lifecycle_outbox where sent_at is not null")).isEqualTo(3);

        assertThat(relay.drainOnce()).as("a second run has nothing left to do").isZero();
        relay.run();
        assertThat(delivered).as("nothing is published twice").hasSize(3);
    }

    @Test
    void theRelayIsAlsoWhatDrainsAnOutboxNobodyEverPublished() {
        EntityRef ref = EntityRef.of("order", "o-2");
        commitWithOutbox(ref, "a", "b", "c", "d", "e");

        JdbcOutboxRelay relay = new JdbcOutboxRelay(store.outbox().orElseThrow(), transport, 2);
        assertThat(relay.drainOnce()).isEqualTo(2);
        assertThat(relay.drainOnce()).isEqualTo(2);
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(relay.drainOnce()).isZero();

        await().atMost(Duration.ofSeconds(5)).until(() -> delivered.size() == 5);
        assertThat(delivered).extracting(LifecycleEvent::eventId).containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void anEmptyOutboxIsNotAnError() {
        JdbcOutboxRelay relay = new JdbcOutboxRelay(store.outbox().orElseThrow(), transport, 10);
        relay.run();
        assertThat(relay.drainOnce()).isZero();
        assertThat(delivered).isEmpty();
    }

    private void commitWithOutbox(EntityRef ref, String... eventIds) {
        List<LifecycleEvent> emitted = java.util.Arrays.stream(eventIds).map(id -> signal(id, ref)).toList();
        AuditRecord audit = new AuditRecord("audit-" + ref.id(), "cause-" + ref.id(), ref, "PAY", Actor.of("u1"),
                "NEW", "PAID", "order.pay", AuditOutcome.APPLIED, null, null, "rs-1", Instant.now(),
                Causation.root("cause-" + ref.id()));
        store.commit(new Commit(ref, 0L, "PAID", "cause-" + ref.id(), audit, emitted));
    }

    private static LifecycleEvent signal(String eventId, EntityRef ref) {
        return LifecycleEvent.builder()
                .eventId(eventId)
                .kind(EventKind.SIGNAL)
                .entity(ref)
                .action("PREPARE")
                .actor(Actor.service("lifecycle-engine"))
                .payload(Map.of("orderId", ref.id()))
                .occurredAt(Instant.parse("2026-01-01T09:00:00Z"))
                .build();
    }
}
