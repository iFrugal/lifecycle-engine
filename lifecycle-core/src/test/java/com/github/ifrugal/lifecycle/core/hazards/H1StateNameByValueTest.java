package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.StateRecord;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.EngineConfig;
import com.github.ifrugal.lifecycle.core.engine.TransitionResolver;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.StateName;
import com.github.ifrugal.lifecycle.core.rules.StatePattern;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H1: identity comparison. A state name must be compared by value and nothing else, so a name that arrives as a
 * fresh {@link String} (from a store, a broker, a concatenation) still matches the compiled rules.
 */
class H1StateNameByValueTest {

    /** A "PAID" that is provably not the interned literal. */
    private static String freshCopy(String s) {
        return new String(s.toCharArray());
    }

    @Test
    void stateNameBuiltByConcatenationEqualsTheLiteralOne() {
        String concatenated = new String("PA") + "ID";
        assertThat(concatenated).isNotSameAs("PAID").isEqualTo("PAID");

        StateName fromConcat = StateName.of(concatenated);
        StateName fromLiteral = StateName.of("PAID");

        assertThat(fromConcat).isEqualTo(fromLiteral).hasSameHashCodeAs(fromLiteral);
        assertThat(fromLiteral).isEqualTo(fromConcat);
        assertThat(fromConcat.compareTo(fromLiteral)).isZero();
        assertThat(List.of(fromLiteral)).contains(fromConcat);
        assertThat(java.util.Set.of(fromLiteral)).contains(fromConcat);
    }

    @Test
    void compiledPatternsMatchAConcatenatedStateName() {
        StateName paid = StateName.of(new String("PA") + "ID");

        assertThat(StatePattern.parse("PAID").matches(paid)).isTrue();
        assertThat(StatePattern.parse(StatePattern.WILDCARD).matches(paid)).isTrue();

        StateName suspended = StateName.of(new String("ACTIVE") + "." + freshCopy("SUSPENDED"));
        assertThat(suspended.under("ACTIVE")).isTrue();
        assertThat(StatePattern.parse("ACTIVE.*").matches(suspended)).isTrue();
        assertThat(StatePattern.parse("ACTIVE.SUSPENDED").matches(suspended)).isTrue();
    }

    @Test
    void compiledMachineResolvesAnEdgeFromAConcatenatedStateName() {
        Machine order = registry().machine(null, SampleRules.ORDER).orElseThrow();
        StateName paid = StateName.of(new String("PA") + "ID");

        List<CompiledTransition> onPrepared = order.forAction("SHIPMENT_PREPARED").stream()
                .filter(t -> t.appliesTo(paid))
                .toList();

        assertThat(onPrepared).extracting(CompiledTransition::id).containsExactly("order.on-shipment-prepared");
    }

    @Test
    void engineRoundTripStateStringIsMatchedByValueNotIdentity() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            transport.subscribe(e -> { /* drain: this test only cares about the order entity */ });
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            EntityRef order = new EntityRef(null, SampleRules.ORDER, "o-1");

            Outcome paid = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("PAY").actor(Actor.of("u1", "customer"))
                    .payload(SampleRules.map("payment", SampleRules.map("status", "AUTHORISED", "amount", 120), "shipmentId", "s-9"))
                    .build());
            assertThat(paid).isInstanceOf(Outcome.Applied.class);

            StateRecord stored = store.find(order).orElseThrow();
            assertThat(stored.state()).isEqualTo("PAID");

            // The state as it would come back off a wire or a JDBC ResultSet: equal, never identical.
            String fromTheWire = freshCopy(stored.state());
            assertThat(fromTheWire).isNotSameAs(stored.state()).isEqualTo(stored.state());

            StateRecord asIfRead = new StateRecord(order, fromTheWire, stored.version(), stored.updatedAt(),
                    stored.lastEventId(), stored.ruleSetVersion());

            var resolver = new TransitionResolver(guards, EngineConfig.defaults().engineActor(), Clock.systemUTC());
            Decision decision = resolver.decide(
                    registry.machine(null, SampleRules.ORDER).orElseThrow(),
                    asIfRead,
                    LifecycleEvent.builder()
                            .entity(order).action("SHIPMENT_PREPARED")
                            .actor(Actor.service("shipping", EngineConfig.ENGINE_ACTOR_ID))
                            .build());

            assertThat(decision).isInstanceOfSatisfying(Decision.Match.class, m -> {
                assertThat(m.from()).isEqualTo("PAID");
                assertThat(m.to()).isEqualTo("FULFILLING");
                assertThat(m.transition().id()).isEqualTo("order.on-shipment-prepared");
            });

            // And the same through the engine, which reads the record from the store itself.
            Outcome fulfilling = engine.handle(LifecycleEvent.builder()
                    .entity(order).action("SHIPMENT_PREPARED")
                    .actor(Actor.service("shipping", EngineConfig.ENGINE_ACTOR_ID))
                    .build());
            assertThat(fulfilling).isInstanceOfSatisfying(Outcome.Applied.class, a -> {
                assertThat(a.from()).isEqualTo("PAID");
                assertThat(a.to()).isEqualTo("FULFILLING");
            });
        }
    }

    private static DefinitionRegistry registry() {
        var store = new InMemoryStateStore();
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), GuardRegistry.of(new RefundWindowOpen()), store);
        registry.reloadOrThrow();
        return registry;
    }
}
