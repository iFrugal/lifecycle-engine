package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.CompiledEmit;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.MachineKey;
import com.github.ifrugal.lifecycle.core.rules.Snapshot;
import com.github.ifrugal.lifecycle.core.rules.StateName;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * H8: loaded rules cannot be mutated by anyone. Every collection reachable from a snapshot is a copy that
 * refuses writes, the payload of an event is deeply frozen, and a reload swaps a new snapshot in rather than
 * editing the one a caller already holds.
 */
class H8ImmutabilityTest {

    private static void refusesMutation(String what, Runnable mutation) {
        assertThatThrownBy(mutation::run).as(what).isInstanceOf(UnsupportedOperationException.class);
    }

    private static DefinitionRegistry registry(InMemoryDefinitionSource source) {
        var registry = new DefinitionRegistry(source, GuardRegistry.of(new RefundWindowOpen()), new InMemoryStateStore());
        registry.reloadOrThrow();
        return registry;
    }

    private static CompiledTransition transition(Machine m, String id) {
        return m.transitions().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theSnapshotsMachineMapIsUnmodifiable() {
        Snapshot snap = registry(new InMemoryDefinitionSource(SampleRules.all())).snapshot();
        Map<MachineKey, Machine> machines = snap.machines();
        assertThat(machines).isNotEmpty();

        Machine any = machines.values().iterator().next();
        refusesMutation("snapshot.machines().put", () -> machines.put(MachineKey.base("intruder"), any));
        refusesMutation("snapshot.machines().remove", () -> machines.remove(MachineKey.base(SampleRules.ORDER)));
        refusesMutation("snapshot.machines().clear", machines::clear);
    }

    @Test
    void aMachinesCollectionsAreUnmodifiable() {
        Machine order = registry(new InMemoryDefinitionSource(SampleRules.all())).machine(null, SampleRules.ORDER).orElseThrow();

        List<CompiledTransition> transitions = order.transitions();
        CompiledTransition first = transitions.get(0);
        refusesMutation("machine.transitions().add", () -> transitions.add(first));
        refusesMutation("machine.transitions().remove", () -> transitions.remove(0));
        refusesMutation("machine.transitions().set", () -> transitions.set(0, first));

        var states = order.states();
        refusesMutation("machine.states().add", () -> states.add(StateName.of("INTRUDER")));
        refusesMutation("machine.states().remove", () -> states.remove(StateName.of("PAID")));

        Map<String, List<CompiledTransition>> byAction = order.byAction();
        refusesMutation("machine.byAction().put", () -> byAction.put("INTRUDE", List.of()));
        refusesMutation("machine.byAction().remove", () -> byAction.remove("PAY"));

        List<CompiledTransition> onPay = byAction.get("PAY");
        assertThat(onPay).isNotEmpty();
        refusesMutation("machine.byAction().get(..).add", () -> onPay.add(first));

        refusesMutation("machine.forAction(..).add", () -> order.forAction("PAY").add(first));
        refusesMutation("machine.forAction(unknown).add", () -> order.forAction("NOT_AN_ACTION").add(first));
    }

    @Test
    void aCompiledTransitionsCollectionsAreUnmodifiable() {
        Machine order = registry(new InMemoryDefinitionSource(SampleRules.all())).machine(null, SampleRules.ORDER).orElseThrow();

        CompiledTransition pay = transition(order, "order.pay");
        assertThat(pay.roles()).isNotEmpty();
        assertThat(pay.when()).isNotEmpty();
        assertThat(pay.emit()).isNotEmpty();

        var roles = pay.roles();
        refusesMutation("transition.roles().add", () -> roles.add("intruder"));
        refusesMutation("transition.roles().remove", () -> roles.remove("customer"));

        Map<String, Object> when = pay.when();
        refusesMutation("transition.when().put", () -> when.put("payment.status", "ANYTHING"));
        refusesMutation("transition.when().remove", () -> when.remove("payment.status"));

        List<CompiledEmit> emit = pay.emit();
        CompiledEmit firstEmit = emit.get(0);
        refusesMutation("transition.emit().add", () -> emit.add(firstEmit));
        refusesMutation("transition.emit().clear", emit::clear);

        CompiledTransition cancel = transition(order, "order.cancel");
        assertThat(cancel.except()).isNotEmpty();
        var except = cancel.except();
        refusesMutation("transition.except().add", () -> except.add(StateName.of("PAID")));
        refusesMutation("transition.except().remove", () -> except.remove(StateName.of("COMPLETED")));

        // Even the empty ones refuse: an absent optional is an immutable empty, not a mutable one (H4 + H8).
        CompiledTransition complete = transition(order, "order.complete");
        refusesMutation("empty transition.roles().add", () -> complete.roles().add("intruder"));
        refusesMutation("empty transition.when().put", () -> complete.when().put("k", "v"));
        refusesMutation("empty transition.emit().add", () -> complete.emit().add(firstEmit));
        refusesMutation("empty transition.except().add", () -> complete.except().add(StateName.of("PAID")));
    }

    @Test
    void aTransitionViewsCollectionsAreUnmodifiable() {
        Machine order = registry(new InMemoryDefinitionSource(SampleRules.all())).machine(null, SampleRules.ORDER).orElseThrow();

        TransitionView pay = transition(order, "order.pay").view();
        refusesMutation("view.roles().add", () -> pay.roles().add("intruder"));
        refusesMutation("view.when().put", () -> pay.when().put("k", "v"));
        refusesMutation("view.except().add", () -> pay.except().add("PAID"));

        TransitionView cancel = transition(order, "order.cancel").view();
        assertThat(cancel.except()).isNotEmpty();
        refusesMutation("view.except().remove", () -> cancel.except().remove(0));
    }

    @Test
    void anEventsPayloadIsDeeplyImmutable() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("status", "AUTHORISED");
        nested.put("amount", 120);

        List<Object> lines = new ArrayList<>();
        lines.add(new LinkedHashMap<>(Map.of("sku", "abc")));
        lines.add("plain");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("payment", nested);
        source.put("lines", lines);

        LifecycleEvent event = LifecycleEvent.builder()
                .entity(new EntityRef(null, SampleRules.ORDER, "o-1"))
                .action("PAY")
                .actor(Actor.of("u1", "customer"))
                .payload(source)
                .build();

        Map<String, Object> payload = event.payload();
        refusesMutation("payload.put", () -> payload.put("intruder", 1));
        refusesMutation("payload.remove", () -> payload.remove("payment"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) payload.get("payment");
        refusesMutation("payload nested map put", () -> payment.put("status", "TAMPERED"));
        refusesMutation("payload nested map remove", () -> payment.remove("amount"));

        @SuppressWarnings("unchecked")
        List<Object> payloadLines = (List<Object>) payload.get("lines");
        refusesMutation("payload nested list add", () -> payloadLines.add("intruder"));
        refusesMutation("payload nested list set", () -> payloadLines.set(0, "intruder"));

        @SuppressWarnings("unchecked")
        Map<String, Object> line = (Map<String, Object>) payloadLines.get(0);
        refusesMutation("payload map inside list put", () -> line.put("sku", "tampered"));

        // The event holds a copy: mutating the map that was handed in changes nothing.
        nested.put("status", "TAMPERED");
        lines.add("late");
        source.put("extra", true);
        assertThat(payment).containsEntry("status", "AUTHORISED");
        assertThat(payloadLines).hasSize(2);
        assertThat(payload).doesNotContainKey("extra");

        // The actor's roles are a copy too.
        refusesMutation("actor.roles().add", () -> event.actor().roles().add("intruder"));
    }

    @Test
    void reloadingLeavesAPreviouslyObtainedSnapshotUntouched() {
        var source = new InMemoryDefinitionSource(SampleRules.all());
        var registry = registry(source);

        Snapshot before = registry.snapshot();
        String versionBefore = before.version();
        int machinesBefore = before.machines().size();
        Machine orderBefore = before.machine(null, SampleRules.ORDER).orElseThrow();
        int transitionsBefore = orderBefore.transitions().size();

        // A reviewed data change: a new machine appears.
        source.add(new RuleSetDocument(null, "coupon", "ISSUED", null,
                List.of("ISSUED", "REDEEMED"),
                List.of(TransitionDocument.of("coupon.redeem", "ISSUED", "REDEEM", "REDEEMED"))));
        var result = registry.reload();
        assertThat(result.problems()).isEmpty();
        assertThat(result.applied()).isTrue();

        Snapshot after = registry.snapshot();
        assertThat(after).isNotSameAs(before);
        assertThat(after.version()).isNotEqualTo(versionBefore);
        assertThat(after.machines()).hasSize(machinesBefore + 1);
        assertThat(after.machine(null, "coupon")).isPresent();

        // The reference a caller was already holding still describes exactly what it described.
        assertThat(before.version()).isEqualTo(versionBefore);
        assertThat(before.machines()).hasSize(machinesBefore);
        assertThat(before.machine(null, "coupon")).isEmpty();
        assertThat(before.machine(null, SampleRules.ORDER).orElseThrow()).isSameAs(orderBefore);
        assertThat(orderBefore.transitions()).hasSize(transitionsBefore);
    }
}
