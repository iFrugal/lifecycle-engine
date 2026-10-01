package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.spi.AuditQuery;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.starter.testapp.Events;
import com.github.ifrugal.lifecycle.starter.testapp.TestApplication;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The zero-infrastructure slice: no {@code lifecycle.store}, no {@code lifecycle.transport}, just two rule files
 * on the classpath and one guard bean. Proves the DD-13 defaults wire an engine that actually runs, and that the
 * whole management surface (endpoint, health, meters) reports on it.
 */
@SpringBootTest(
        classes = {TestApplication.class, MemoryDefaultsIntegrationTest.Guards.class},
        properties = {
                "lifecycle.rules.files=classpath:rules/order.yaml,classpath:rules/shipment.yaml",
                "lifecycle.rules.reload.poll=0"
        })
class MemoryDefaultsIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    static class Guards {

        /** The D1 escape hatch, discovered as a bean (DD-13 bean 1). Always open, so the edge is reachable. */
        @Bean
        GuardPredicate refundWindowOpen() {
            return new GuardPredicate() {
                @Override
                public String name() {
                    return "refund-window-open";
                }

                @Override
                public boolean test(GuardContext context) {
                    return true;
                }
            };
        }
    }

    @Autowired
    ApplicationContext context;
    @Autowired
    LifecycleEngine engine;
    @Autowired
    StateStore store;
    @Autowired
    DefinitionRegistry registry;
    @Autowired
    GuardRegistry guards;
    @Autowired
    InMemoryTransport transport;
    @Autowired
    LifecycleRulesEndpoint endpoint;
    @Autowired
    LifecycleRulesHealthIndicator health;
    @Autowired
    MeterRegistry meters;

    @Test
    void wires_the_documented_default_beans() {
        assertThat(engine).isNotNull();
        assertThat(context.getBean(Dispatcher.class)).isNotNull();
        assertThat(store).isInstanceOf(InMemoryStateStore.class);
        // The concrete store bean is its own AuditQuery: one singleton, two injection points (DD-13 bean 3).
        assertThat(context.getBean(AuditQuery.class)).isSameAs(store);
        assertThat(transport.supportsDelay()).isTrue();
        assertThat(context.getBeansOfType(LifecycleEngine.class)).hasSize(1);
    }

    @Test
    void discovers_the_guard_bean_and_compiles_the_guarded_edge() {
        assertThat(guards.names()).contains("refund-window-open");

        Machine order = registry.machine(null, "order").orElseThrow();
        assertThat(order.transitions())
                .filteredOn(t -> "order.request-refund".equals(t.id()))
                .extracting(CompiledTransition::guard)
                .containsExactly("refund-window-open");
    }

    @Test
    void handles_an_event_end_to_end() {
        Outcome outcome = engine.handle(Events.pay("o-handle", "s-handle"));

        assertThat(outcome).isInstanceOf(Outcome.Applied.class);
        Outcome.Applied applied = (Outcome.Applied) outcome;
        assertThat(applied.transitionId()).isEqualTo("order.pay");
        assertThat(applied.to()).isEqualTo("PAID");
        // order.pay signals the shipment over the transport and the shipment signals back, so the stored state
        // is PAID only until that cascade lands. Wait for it, then assert the deterministic end state.
        assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(store.find(EntityRef.of("order", "o-handle")).orElseThrow().state()).isEqualTo("FULFILLING");
    }

    @Test
    void completes_a_cross_entity_cascade_over_the_transport() {
        // order.pay signals the shipment; shipment.prepare signals the order back. Both hops cross the
        // transport, because a cascade to another entity is never inline (DD-09).
        engine.handle(Events.pay("o-cascade", "s-cascade"));

        assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(store.find(EntityRef.of("shipment", "s-cascade")).orElseThrow().state()).isEqualTo("PREPARED");
            assertThat(store.find(EntityRef.of("order", "o-cascade")).orElseThrow().state()).isEqualTo("FULFILLING");
        });
        assertThat(transport.deadLetters()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reports_and_reloads_through_the_actuator_endpoint() {
        Map<String, Object> before = endpoint.rules();
        assertThat(before.get("version")).isEqualTo(registry.snapshot().version());
        assertThat((List<Map<String, Object>>) before.get("machines"))
                .extracting(m -> m.get("key"))
                .containsExactly("order", "shipment");
        assertThat((Map<String, Object>) before.get("lastReload")).containsEntry("applied", true);

        Map<String, Object> reloaded = endpoint.reload();
        assertThat(reloaded).containsEntry("applied", true);
        assertThat(reloaded.get("version")).isEqualTo(before.get("version"));
        assertThat(reloaded.get("problems")).isEqualTo(List.of());
    }

    @Test
    void renders_one_machine_as_mermaid() {
        assertThat(endpoint.diagram("order")).startsWith("stateDiagram-v2").contains("NEW --> PAID");
        assertThat(endpoint.diagram("no-such-machine")).isNull();
    }

    @Test
    void reports_health_up_with_the_snapshot_version() {
        Health report = health.health();

        assertThat(report.getStatus()).isEqualTo(Status.UP);
        assertThat(report.getDetails())
                .containsEntry("loaded", true)
                .containsEntry("version", registry.snapshot().version())
                .containsEntry("machines", 2)
                .containsEntry("problems", List.of());
    }

    @Test
    void counts_outcomes_reloads_and_the_outbox_backlog() {
        engine.handle(Events.pay("o-metered", "s-metered"));
        // Let the cross-entity cascade settle first; a PAY racing the cascade's commit would be a version
        // conflict, not a refusal. From FULFILLING a second PAY cannot match, so it is refused with its reason.
        assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();
        engine.handle(Events.action("order", "o-metered", "PAY", "customer",
                Map.of("payment", Map.of("status", "AUTHORISED"))));

        assertThat(engine).isInstanceOf(MeteredLifecycleEngine.class);
        // RequiredSearch accumulates its criteria, so each assertion needs its own search.
        assertThat(meters.get(MeteredLifecycleEngine.OUTCOMES)
                .tags("outcome", "APPLIED", "reason", "none").counter().count()).isGreaterThanOrEqualTo(1.0);
        assertThat(meters.get(MeteredLifecycleEngine.OUTCOMES)
                .tags("outcome", "REFUSED", "reason", "NO_MATCH").counter().count()).isGreaterThanOrEqualTo(1.0);

        assertThat(meters.get(LifecycleMeterBinder.RELOADS).tag("applied", "true").functionCounter().count())
                .isGreaterThanOrEqualTo(1.0);
        assertThat(meters.get(LifecycleMeterBinder.OUTBOX_PENDING).gauge()).isNotNull();
    }
}
