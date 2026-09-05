package com.github.ifrugal.lifecycle.rules.yaml;

import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that YAML rule sets compile through exactly the same pipeline as the in-code {@code SampleRules}: the
 * rule set is loaded from files, merged with its tenant overlay, and compiled into machines with no problems.
 */
class EndToEndCompileTest {

    /** The D1 escape hatch named by rules/order.yaml's order.request-refund transition. */
    private static final class RefundWindowOpen implements GuardPredicate {
        @Override
        public String name() {
            return "refund-window-open";
        }

        @Override
        public boolean test(GuardContext context) {
            return true;
        }
    }

    private static Path rulesRoot() {
        try {
            return Path.of(EndToEndCompileTest.class.getResource("/rules").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void yamlRuleSetsCompileToThreeMachinesWithNoProblems() {
        DefinitionRegistry registry = new DefinitionRegistry(
                FileDefinitionSource.of(rulesRoot()),
                GuardRegistry.of(new RefundWindowOpen()),
                new InMemoryStateStore());

        DefinitionRegistry.ReloadResult result = registry.reload();

        assertThat(result.applied()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(registry.snapshot().machines()).hasSize(3);

        assertThat(registry.machine(null, "order")).isPresent();
        assertThat(registry.machine(null, "shipment")).isPresent();
        assertThat(registry.machine("acme", "order")).isPresent();
        // The overlay falls back to the base for an entity type it does not touch.
        assertThat(registry.machine("acme", "shipment")).isPresent();
    }
}
