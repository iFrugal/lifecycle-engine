package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.Decision;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.TransitionView;
import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.rules.TaskDocument;
import com.github.ifrugal.lifecycle.api.rules.TransitionDocument;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.CompiledTransition;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H4: absent optionals must not crash. A rule set may declare nothing but {@code from}, {@code on} and
 * {@code to}; an event may carry no payload; an actor may hold no roles. Every optional is normalised to an
 * empty collection at construction, so optionality in the schema is optionality at runtime.
 */
class H4OptionalFieldsTest {

    private static final String TYPE = "ticket";

    /** Every transition is the minimal three-field form: no roles, no when, no guard, no except, no emit, no task. */
    private static RuleSetDocument bareRules() {
        return new RuleSetDocument(null, TYPE, "OPEN", null,
                List.of("OPEN", "TRIAGED", "CLOSED"),
                List.of(
                        TransitionDocument.of("ticket.triage", "OPEN", "TRIAGE", "TRIAGED"),
                        TransitionDocument.of("ticket.close", "TRIAGED", "CLOSE", "CLOSED"),
                        TransitionDocument.of("ticket.reopen", "CLOSED", "REOPEN", "OPEN")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private record Fixture(DefinitionRegistry registry, InMemoryStateStore store, InMemoryTransport transport,
                           DefaultLifecycleEngine engine) {}

    private static Fixture fixture(RuleSetDocument... docs) {
        var store = new InMemoryStateStore();
        var guards = GuardRegistry.empty();
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(List.of(docs)), guards, store);
        registry.reloadOrThrow();
        var transport = new InMemoryTransport();
        transport.subscribe(e -> { /* drain */ });
        return new Fixture(registry, store, transport, new DefaultLifecycleEngine(registry, store, transport, guards));
    }

    @Test
    void aRuleSetWithOnlyTheMandatoryFieldsCompilesToEmptyCollections() {
        var f = fixture(bareRules());
        try (var t = f.transport()) {
            Machine m = f.registry().machine(null, TYPE).orElseThrow();
            assertThat(m.transitions()).hasSize(3);
            for (CompiledTransition ct : m.transitions()) {
                assertThat(ct.roles()).isEmpty();
                assertThat(ct.when()).isEmpty();
                assertThat(ct.except()).isEmpty();
                assertThat(ct.emit()).isEmpty();
                assertThat(ct.guard()).isNull();
                assertThat(ct.task()).isNull();

                TransitionView v = ct.view();
                assertThat(v.roles()).isEmpty();
                assertThat(v.when()).isEmpty();
                assertThat(v.except()).isEmpty();
                assertThat(v.guard()).isNull();
            }
            assertThat(t.deadLetters()).isEmpty();
        }
    }

    @Test
    void anEventWithNoPayloadAndAnActorWithNoRolesIsApplied() {
        var f = fixture(bareRules());
        try (var t = f.transport()) {
            EntityRef ticket = new EntityRef(null, TYPE, "t-1");
            Actor noRoles = Actor.of("nobody"); // no roles at all: a legal actor (H4)
            assertThat(noRoles.roles()).isEmpty();

            LifecycleEvent triage = LifecycleEvent.builder().entity(ticket).action("TRIAGE").actor(noRoles).build();
            assertThat(triage.payload()).isEmpty();
            assertThat(triage.deliverAt()).isNull();
            assertThat(triage.expectedVersion()).isNull();
            assertThat(triage.causation().causationId()).isNull();

            assertThat(f.engine().handle(triage)).isInstanceOfSatisfying(Outcome.Applied.class, a -> {
                assertThat(a.from()).isEqualTo("OPEN");
                assertThat(a.to()).isEqualTo("TRIAGED");
                assertThat(a.version()).isEqualTo(1L);
                assertThat(a.emitted()).isEmpty();
            });

            // Explicit null payload on the builder is the same thing as never calling it.
            LifecycleEvent close = LifecycleEvent.builder().entity(ticket).action("CLOSE").actor(noRoles).payload(null).build();
            assertThat(close.payload()).isEmpty();
            assertThat(f.engine().handle(close)).isInstanceOfSatisfying(Outcome.Applied.class,
                    a -> assertThat(a.to()).isEqualTo("CLOSED"));

            // And the dry-run path copes with the same absences.
            LifecycleEvent reopen = LifecycleEvent.builder().entity(ticket).action("REOPEN").actor(noRoles).build();
            assertThat(f.engine().evaluate(reopen)).isInstanceOfSatisfying(Decision.Match.class, m -> {
                assertThat(m.to()).isEqualTo("OPEN");
                assertThat(m.emissions()).isEmpty();
            });
            assertThat(f.engine().available(ticket, noRoles)).extracting(TransitionView::id).containsExactly("ticket.reopen");

            assertThat(t.deadLetters()).isEmpty();
        }
    }

    @Test
    void anActorWithNoRolesIsRefusedOnlyWhenTheEdgeActuallyRequiresRoles() {
        RuleSetDocument guarded = new RuleSetDocument(null, TYPE, "OPEN", null,
                List.of("OPEN", "TRIAGED"),
                List.of(TransitionDocument.builder("ticket.triage").from("OPEN").on("TRIAGE").roles("agent").to("TRIAGED").build()));
        var f = fixture(guarded);
        try (var t = f.transport()) {
            EntityRef ticket = new EntityRef(null, TYPE, "t-2");
            assertThat(f.engine().handle(LifecycleEvent.builder().entity(ticket).action("TRIAGE").actor(Actor.of("nobody")).build()))
                    .isInstanceOf(Outcome.Refused.class);
            assertThat(f.engine().handle(LifecycleEvent.builder().entity(ticket).action("TRIAGE").actor(Actor.of("a1", "agent")).build()))
                    .isInstanceOf(Outcome.Applied.class);
            assertThat(t.deadLetters()).isEmpty();
        }
    }

    @Test
    void aTaskWithNoPayloadFromAnEventWithNoPayloadCompilesAndEmits() {
        RuleSetDocument withTask = new RuleSetDocument(null, TYPE, "OPEN", null,
                List.of("OPEN", "TRIAGED"),
                List.of(TransitionDocument.builder("ticket.triage")
                        .from("OPEN").on("TRIAGE").to("TRIAGED")
                        // no assignTo, no onComplete target, no payload: every optional of a task absent
                        .task(new TaskDocument("look-at-it", null, "TRIAGE_DONE", null, null))
                        .build()));

        var f = fixture(withTask);
        try (var t = f.transport()) {
            assertThat(f.registry().machine(null, TYPE).orElseThrow().transitions().get(0).task().assignTo()).isEmpty();

            Outcome outcome = f.engine().handle(LifecycleEvent.builder()
                    .entity(new EntityRef(null, TYPE, "t-3")).action("TRIAGE").actor(Actor.of("nobody")).build());

            assertThat(outcome).isInstanceOfSatisfying(Outcome.Applied.class, a -> {
                assertThat(a.emitted()).hasSize(1);
                LifecycleEvent task = a.emitted().get(0);
                assertThat(task.kind()).isEqualTo(EventKind.NOTIFICATION);
                assertThat(task.action()).isEqualTo("lifecycle.task.create");
                assertThat(task.payload()).containsEntry("name", "look-at-it");
                assertThat(task.payload()).containsEntry("assignTo", List.of());
                assertThat(task.payload()).containsEntry("payload", Map.of());
                assertThat(task.payload().get("onComplete")).isInstanceOf(Map.class);
                Map<String, Object> onComplete = asMap(task.payload().get("onComplete"));
                assertThat(onComplete).containsEntry("action", "TRIAGE_DONE");
                assertThat(asMap(onComplete.get("target"))).containsEntry("type", TYPE).containsEntry("id", "t-3");
                assertThat(asMap(task.payload().get("createdBy"))).containsEntry("type", TYPE).containsEntry("id", "t-3");
            });

            assertThat(t.notifications()).extracting(LifecycleEvent::action).containsExactly("lifecycle.task.create");
            assertThat(t.deadLetters()).isEmpty();
        }
    }
}
