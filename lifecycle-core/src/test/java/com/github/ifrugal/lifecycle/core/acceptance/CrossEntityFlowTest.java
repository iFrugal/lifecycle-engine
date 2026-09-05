package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance: "same rules over in-memory and a broker" (brief §9). The order and shipment machines signal
 * each other across the transport (DD-09); the causation chain ties the whole flow together (DD-02, DD-06).
 */
class CrossEntityFlowTest {

    @Test
    void orderAndShipmentCascadeAcrossTheTransportToCompletion() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();

        try (var transport = new InMemoryTransport()) {
            var engine = new DefaultLifecycleEngine(registry, store, transport, guards);
            new Dispatcher(engine, 3).attachTo(transport);

            EntityRef order = EntityRef.of("order", "o-1");
            EntityRef shipment = EntityRef.of("shipment", "s-1");

            LifecycleEvent pay = LifecycleEvent.builder()
                    .entity(order).action("PAY").actor(Actor.of("u1", "customer"))
                    .payload(Map.of("payment", Map.of("status", "AUTHORISED", "amount", 120), "shipmentId", "s-1"))
                    .build();

            Outcome payOutcome = engine.handle(pay);
            assertThat(payOutcome).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) payOutcome).to()).isEqualTo("PAID");

            assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();

            // shipment.prepare (PENDING -> PREPARED) then order.on-shipment-prepared (PAID -> FULFILLING)
            assertThat(store.find(shipment).map(r -> r.state())).contains("PREPARED");
            assertThat(store.find(order).map(r -> r.state())).contains("FULFILLING");

            LifecycleEvent deliver = LifecycleEvent.builder()
                    .entity(shipment).action("DELIVER").actor(Actor.of("c1", "courier"))
                    .payload(Map.of("orderId", "o-1"))
                    .build();
            Outcome deliverOutcome = engine.handle(deliver);
            assertThat(deliverOutcome).isInstanceOf(Outcome.Applied.class);
            assertThat(((Outcome.Applied) deliverOutcome).to()).isEqualTo("DELIVERED");

            assertThat(transport.awaitIdle(Duration.ofSeconds(5))).isTrue();

            assertThat(store.find(shipment).map(r -> r.state())).contains("DELIVERED");
            assertThat(store.find(order).map(r -> r.state())).contains("COMPLETED");

            // the whole PAY-triggered chain shares one correlation id, with strictly increasing hops
            List<AuditRecord> chain = store.byCorrelation(pay.eventId());
            assertThat(chain).hasSizeGreaterThanOrEqualTo(3);
            List<Integer> hops = chain.stream().map(a -> a.causation().hop()).toList();
            assertThat(hops).isSorted();
            assertThat(hops).contains(0, 1, 2);

            // shipment.prepare fired by the engine actor
            assertThat(chain).anySatisfy(a -> {
                assertThat(a.action()).isEqualTo("PREPARE");
                assertThat(a.actor().roles()).contains(SampleRules.ENGINE_ROLE);
            });

            // the notification emitted by order.pay was collected
            assertThat(transport.notifications()).anySatisfy(n -> assertThat(n.action()).isEqualTo("ReceiptRequested"));
        }
    }
}
