package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.github.ifrugal.lifecycle.mongo.TestSupport.actor;
import static com.github.ifrugal.lifecycle.mongo.TestSupport.applied;
import static com.github.ifrugal.lifecycle.mongo.TestSupport.id;
import static org.assertj.core.api.Assertions.assertThat;

/** DD-07: "a crash between commit and publish is repaired by the backend module's OutboxRelay". */
@Testcontainers(disabledWithoutDocker = true)
class MongoOutboxRelayTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private MongoClient client;
    private MongoDatabase database;
    private MongoStateStore store;

    /** Records every publish; never actually delivers anywhere. */
    private static final class RecordingTransport implements Transport {
        final List<LifecycleEvent> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LifecycleEvent event) {
            published.add(event);
        }

        @Override
        public void subscribe(Consumer<LifecycleEvent> inbound) {
            // not exercised by this test
        }

        @Override
        public boolean supportsDelay() {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        client = MongoClients.create(MONGO.getConnectionString());
        database = client.getDatabase("test_" + id().replace("-", ""));
        MongoSchema.ensureIndexes(database);
        store = new MongoStateStore(client, database, Duration.ofDays(7), true);
    }

    @AfterEach
    void tearDown() {
        database.drop();
        client.close();
    }

    @Test
    void drainOncePublishesUnsentEventsAndMarksThemSent() {
        EntityRef ref = EntityRef.of("widget", "w-relay");
        LifecycleEvent e1 = LifecycleEvent.builder().entity(ref).action("NOTIFY_ONE").actor(actor("engine")).build();
        LifecycleEvent e2 = LifecycleEvent.builder().entity(ref).action("NOTIFY_TWO").actor(actor("engine")).build();
        String commitEventId = id();
        store.commit(new Commit(ref, 0L, "B", commitEventId, applied(id(), commitEventId, ref, "A", "B", "t1"), List.of(e1, e2)));

        Outbox outbox = store.outbox().orElseThrow();
        assertThat(outbox.unsent(10)).hasSize(2);

        RecordingTransport transport = new RecordingTransport();
        MongoOutboxRelay relay = new MongoOutboxRelay(outbox, transport, 10);

        int drained = relay.drainOnce();

        assertThat(drained).isEqualTo(2);
        assertThat(transport.published).extracting(LifecycleEvent::eventId).containsExactlyInAnyOrder(e1.eventId(), e2.eventId());
        assertThat(outbox.unsent(10)).isEmpty();

        // idempotent: nothing left to drain.
        assertThat(relay.drainOnce()).isZero();
    }

    @Test
    void drainOnceRespectsBatchSize() {
        EntityRef ref = EntityRef.of("widget", "w-relay-batch");
        List<LifecycleEvent> emitted = List.of(
                LifecycleEvent.builder().entity(ref).action("N1").actor(actor("engine")).build(),
                LifecycleEvent.builder().entity(ref).action("N2").actor(actor("engine")).build(),
                LifecycleEvent.builder().entity(ref).action("N3").actor(actor("engine")).build());
        String commitEventId = id();
        store.commit(new Commit(ref, 0L, "B", commitEventId, applied(id(), commitEventId, ref, "A", "B", "t1"), emitted));

        RecordingTransport transport = new RecordingTransport();
        MongoOutboxRelay relay = new MongoOutboxRelay(store.outbox().orElseThrow(), transport, 2);

        assertThat(relay.drainOnce()).isEqualTo(2);
        assertThat(relay.drainOnce()).isEqualTo(1);
        assertThat(relay.drainOnce()).isZero();
        assertThat(transport.published).hasSize(3);
    }
}
