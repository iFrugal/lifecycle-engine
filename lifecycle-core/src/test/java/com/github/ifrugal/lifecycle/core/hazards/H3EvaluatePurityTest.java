package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H3: a dry run must have no consequences. {@code evaluate} and {@code available} are not "handle with a flag
 * that skips the write": commit and publish are simply absent from their call path. Spying decorators around the
 * store and the transport count every write and every publish; both counts must stay at zero.
 */
class H3EvaluatePurityTest {

    private static final int ROUNDS = 200;

    /** Counts every mutating call; reads are delegated untouched. */
    static final class SpyStore implements StateStore {
        private final StateStore delegate;
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger detached = new AtomicInteger();
        final AtomicInteger finds = new AtomicInteger();

        SpyStore(StateStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<StateRecord> find(EntityRef ref) {
            finds.incrementAndGet();
            return delegate.find(ref);
        }

        @Override
        public CommitResult commit(Commit commit) {
            commits.incrementAndGet();
            return delegate.commit(commit);
        }

        @Override
        public void appendDetached(AuditRecord record) {
            detached.incrementAndGet();
            delegate.appendDetached(record);
        }

        @Override
        public OptionalLong countInState(String tenantId, String entityType, String state) {
            return delegate.countInState(tenantId, entityType, state);
        }

        @Override
        public Optional<Outbox> outbox() {
            return delegate.outbox();
        }

        int writes() {
            return commits.get() + detached.get();
        }
    }

    /** Counts every publish; subscription and capability queries are delegated. */
    static final class SpyTransport implements Transport {
        private final Transport delegate;
        final AtomicInteger publishes = new AtomicInteger();

        SpyTransport(Transport delegate) {
            this.delegate = delegate;
        }

        @Override
        public void publish(LifecycleEvent event) {
            publishes.incrementAndGet();
            delegate.publish(event);
        }

        @Override
        public void subscribe(Consumer<LifecycleEvent> inbound) {
            delegate.subscribe(inbound);
        }

        @Override
        public boolean supportsDelay() {
            return delegate.supportsDelay();
        }
    }

    private static LifecycleEvent matching() {
        return LifecycleEvent.builder()
                .entity(new EntityRef(null, SampleRules.ORDER, "o-1"))
                .action("PAY")
                .actor(Actor.of("u1", "customer"))
                .payload(SampleRules.map("payment", SampleRules.map("status", "AUTHORISED", "amount", 120), "shipmentId", "s-9"))
                .build();
    }

    private static LifecycleEvent refusedNoMatch() {
        return LifecycleEvent.builder()
                .entity(new EntityRef(null, SampleRules.ORDER, "o-2"))
                .action("COMPLETE")
                .actor(Actor.of("u1", "customer"))
                .build();
    }

    private static LifecycleEvent refusedRoleDenied() {
        return LifecycleEvent.builder()
                .entity(new EntityRef(null, SampleRules.ORDER, "o-3"))
                .action("PAY")
                .actor(Actor.of("u1", "stranger"))
                .payload(SampleRules.map("payment", SampleRules.map("status", "AUTHORISED"), "shipmentId", "s-9"))
                .build();
    }

    private static LifecycleEvent refusedNoMachine() {
        return LifecycleEvent.builder()
                .entity(new EntityRef(null, "unicorn", "u-1"))
                .action("PAY")
                .actor(Actor.of("u1", "customer"))
                .build();
    }

    @Test
    void evaluateAndAvailableNeverCommitAndNeverPublish() {
        var backing = new InMemoryStateStore();
        var spyStore = new SpyStore(backing);
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, backing);
        registry.reloadOrThrow();

        try (var inner = new InMemoryTransport()) {
            var spyTransport = new SpyTransport(inner);
            var engine = new DefaultLifecycleEngine(registry, spyStore, spyTransport, guards);

            EntityRef order = new EntityRef(null, SampleRules.ORDER, "o-1");
            Actor customer = Actor.of("u1", "customer");

            for (int i = 0; i < ROUNDS; i++) {
                engine.evaluate(matching());
                engine.evaluate(refusedNoMatch());
                engine.evaluate(refusedRoleDenied());
                engine.evaluate(refusedNoMachine());
                engine.available(order, customer);
                engine.available(new EntityRef("acme", SampleRules.ORDER, "o-9"), customer);
                engine.available(new EntityRef(null, "unicorn", "u-1"), customer);
            }

            assertThat(spyStore.commits.get()).as("commits during a dry run").isZero();
            assertThat(spyStore.detached.get()).as("detached audit rows during a dry run").isZero();
            assertThat(spyStore.writes()).as("any write during a dry run").isZero();
            assertThat(spyTransport.publishes.get()).as("publishes during a dry run").isZero();

            // Nothing reached the backing store at all: no audit, no state, no outbox.
            assertThat(backing.allAudit()).isEmpty();
            assertThat(backing.allStates()).isEmpty();
            assertThat(backing.unsent(100)).isEmpty();
            assertThat(inner.notifications()).isEmpty();
            assertThat(inner.deadLetters()).isEmpty();

            // The dry run did read, which is the only thing it is allowed to do.
            assertThat(spyStore.finds.get()).isPositive();
        }
    }

    @Test
    void evaluateReturnsTheMatchAndItsEmissionsWithoutWriting() {
        var backing = new InMemoryStateStore();
        var spyStore = new SpyStore(backing);
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, backing);
        registry.reloadOrThrow();

        try (var inner = new InMemoryTransport()) {
            var spyTransport = new SpyTransport(inner);
            var engine = new DefaultLifecycleEngine(registry, spyStore, spyTransport, guards);

            Decision decision = engine.evaluate(matching());

            assertThat(decision).isInstanceOfSatisfying(Decision.Match.class, m -> {
                assertThat(m.from()).isEqualTo("NEW");
                assertThat(m.to()).isEqualTo("PAID");
                assertThat(m.transition().id()).isEqualTo("order.pay");
                assertThat(m.emissions()).hasSize(2);
                assertThat(m.emissions()).extracting(e -> e.event().action())
                        .containsExactly("ReceiptRequested", "PREPARE");
            });

            // Evaluating the same event again is idempotent: still a Match, still nothing written.
            assertThat(engine.evaluate(matching())).isInstanceOf(Decision.Match.class);

            assertThat(spyStore.writes()).isZero();
            assertThat(spyTransport.publishes.get()).isZero();
            assertThat(backing.allAudit()).isEmpty();
        }
    }

    @Test
    void evaluateRefusesWithoutWritingAnAuditRow() {
        var backing = new InMemoryStateStore();
        var spyStore = new SpyStore(backing);
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, backing);
        registry.reloadOrThrow();

        try (var inner = new InMemoryTransport()) {
            var spyTransport = new SpyTransport(inner);
            var engine = new DefaultLifecycleEngine(registry, spyStore, spyTransport, guards);

            assertThat(engine.evaluate(refusedNoMatch()))
                    .isInstanceOfSatisfying(Decision.Refuse.class, r -> assertThat(r.reason()).isEqualTo(RefusalReason.NO_MATCH));
            assertThat(engine.evaluate(refusedRoleDenied()))
                    .isInstanceOfSatisfying(Decision.Refuse.class, r -> assertThat(r.reason()).isEqualTo(RefusalReason.ROLE_DENIED));
            assertThat(engine.evaluate(refusedNoMachine()))
                    .isInstanceOfSatisfying(Decision.Refuse.class, r -> assertThat(r.reason()).isEqualTo(RefusalReason.NO_MACHINE));

            // Refusals are audited by handle(), never by evaluate(): H9 owns that, H3 owns the silence here.
            assertThat(spyStore.writes()).isZero();
            assertThat(spyTransport.publishes.get()).isZero();
            assertThat(backing.allAudit()).isEmpty();
        }
    }

    @Test
    void availableIsAReadAndReturnsTheActorsEdges() {
        var backing = new InMemoryStateStore();
        var spyStore = new SpyStore(backing);
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, backing);
        registry.reloadOrThrow();

        try (var inner = new InMemoryTransport()) {
            var spyTransport = new SpyTransport(inner);
            var engine = new DefaultLifecycleEngine(registry, spyStore, spyTransport, guards);

            List<TransitionView> forCustomer = engine.available(new EntityRef(null, SampleRules.ORDER, "o-1"), Actor.of("u1", "customer"));
            assertThat(forCustomer).extracting(TransitionView::id).containsExactlyInAnyOrder("order.pay", "order.cancel");

            assertThat(engine.available(new EntityRef(null, "unicorn", "u-1"), Actor.of("u1", "customer"))).isEmpty();

            assertThat(spyStore.writes()).isZero();
            assertThat(spyTransport.publishes.get()).isZero();
        }
    }
}
