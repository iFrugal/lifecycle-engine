package com.github.ifrugal.lifecycle.core.render;

import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.testdomain.RefundWindowOpen;
import com.github.ifrugal.lifecycle.core.testdomain.SampleRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidRendererTest {

    @Test
    void renderOrderMachine() {
        // Setup registry with sample rules
        var registry = new DefinitionRegistry(
                new InMemoryDefinitionSource(SampleRules.all()),
                GuardRegistry.of(new RefundWindowOpen()),
                new InMemoryStateStore());
        registry.reloadOrThrow();

        // Get the order machine for the default tenant
        Machine machine = registry.machine(null, SampleRules.ORDER)
                .orElseThrow(() -> new AssertionError("order machine not found"));

        // Render the machine
        String diagram = MermaidRenderer.render(machine);

        // Print the diagram for human inspection
        System.out.println("=== Order Machine Diagram ===");
        System.out.println(diagram);
        System.out.println();

        // Assertions
        assertThat(diagram)
                .startsWith("stateDiagram-v2")
                .contains("[*] --> NEW")
                .contains("NEW --> PAID : PAY [")
                .contains("NEW --> CANCELLED : CANCEL [")
                .contains("PAID --> CANCELLED : CANCEL [")
                .contains("FULFILLING --> CANCELLED : CANCEL [")
                .contains("REFUND_PENDING --> CANCELLED : CANCEL [")
                .doesNotContain("COMPLETED --> CANCELLED : CANCEL")
                .doesNotContain("REFUNDED --> CANCELLED : CANCEL")
                .doesNotContain("CANCELLED --> CANCELLED : CANCEL")
                .contains("COMPLETED --> REFUND_PENDING : REQUEST_REFUND")
                .contains("guard-refund-window-open")
                .contains("task-approve-refund")
                .contains("CANCELLED --> [*]");
    }

    @Test
    void renderAcmeOverlayMachine() {
        // Setup registry with sample rules
        var registry = new DefinitionRegistry(
                new InMemoryDefinitionSource(SampleRules.all()),
                GuardRegistry.of(new RefundWindowOpen()),
                new InMemoryStateStore());
        registry.reloadOrThrow();

        // Get the acme overlay order machine
        Machine machine = registry.machine("acme", SampleRules.ORDER)
                .orElseThrow(() -> new AssertionError("acme order machine not found"));

        // Render the machine
        String diagram = MermaidRenderer.render(machine);

        // Print the diagram for human inspection
        System.out.println("=== Acme Overlay Order Machine Diagram ===");
        System.out.println(diagram);
        System.out.println();

        // Assertions
        assertThat(diagram)
                .contains("ON_HOLD")
                .contains("PAID --> ON_HOLD : HOLD [")
                .doesNotContain(": CANCEL");
    }
}
