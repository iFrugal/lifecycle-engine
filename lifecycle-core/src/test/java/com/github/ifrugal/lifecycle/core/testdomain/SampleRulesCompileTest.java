package com.github.ifrugal.lifecycle.core.testdomain;

import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.registry.InMemoryDefinitionSource;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test: the sample domain compiles cleanly, including the tenant overlay. */
class SampleRulesCompileTest {

    @Test
    void sampleDomainLoadsWithoutProblems() {
        var registry = new DefinitionRegistry(new InMemoryDefinitionSource(SampleRules.all()), GuardRegistry.of(new RefundWindowOpen()), new InMemoryStateStore());
        var result = registry.reload();
        assertThat(result.problems()).isEmpty();
        assertThat(result.applied()).isTrue();
        assertThat(registry.snapshot().machines()).hasSize(3);
        assertThat(registry.machine("acme", SampleRules.ORDER)).isPresent();
        assertThat(registry.machine("acme", SampleRules.ORDER).get().forAction("CANCEL")).isEmpty();
        assertThat(registry.machine("globex", SampleRules.ORDER).get().forAction("CANCEL")).hasSize(1);
        assertThat(registry.requiresDelay()).isTrue();
    }
}
