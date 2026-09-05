package com.github.ifrugal.lifecycle.core.acceptance;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Acceptance: "what can this actor do?" (brief §9). {@code available()} runs steps 1-2 only (R9, H3). */
class AvailableTest {

    private InMemoryTransport transport;

    private DefaultLifecycleEngine engine() {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.of(new RefundWindowOpen());
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), guards, store);
        registry.reloadOrThrow();
        transport = new InMemoryTransport();
        return new DefaultLifecycleEngine(registry, store, transport, guards);
    }

    @AfterEach
    void closeTransport() {
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    void customerOnANewOrderCanPayOrCancel() {
        DefaultLifecycleEngine engine = engine();
        EntityRef order = EntityRef.of("order", "never-seen-1");
        List<TransitionView> available = engine.available(order, Actor.of("c", "customer"));
        assertThat(available).extracting(TransitionView::id).containsExactly("order.pay", "order.cancel");
    }

    @Test
    void supportOnANewOrderCanOnlyCancel() {
        DefaultLifecycleEngine engine = engine();
        EntityRef order = EntityRef.of("order", "never-seen-2");
        List<TransitionView> available = engine.available(order, Actor.of("s", "support"));
        assertThat(available).extracting(TransitionView::id).containsExactly("order.cancel");
    }

    @Test
    void actorWithNoRolesCanDoNothingFromNew() {
        DefaultLifecycleEngine engine = engine();
        EntityRef order = EntityRef.of("order", "never-seen-3");
        List<TransitionView> available = engine.available(order, Actor.of("nobody"));
        assertThat(available).isEmpty();
    }

    @Test
    void anEntityNeverSeenIsTreatedAsSittingInTheInitialState() {
        DefaultLifecycleEngine engine = engine();
        EntityRef order = EntityRef.of("order", "totally-fresh");
        // no event was ever handled for "totally-fresh"; available() must still reflect state NEW
        List<TransitionView> available = engine.available(order, Actor.of("c", "customer"));
        assertThat(available).extracting(TransitionView::id).containsExactlyInAnyOrder("order.pay", "order.cancel");
    }
}
