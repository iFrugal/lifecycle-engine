package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.rules.Problem;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.rules.Snapshot;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.List;
import java.util.Objects;

/**
 * Reports whether a rule snapshot is live (DD-13, bean 10). UP means the engine can answer; DOWN means no
 * snapshot was ever loaded and every event will be refused with {@code NO_MACHINE} until a reload succeeds.
 *
 * <p>A <em>failed</em> reload after a good one is still UP: the previous snapshot stays live by design (DD-05),
 * so the application is serving correctly. The failure is visible in {@code lastReload.problems} either way,
 * which is what an operator needs to see.
 */
public class LifecycleRulesHealthIndicator implements HealthIndicator {

    private final LifecycleRuleReloader reloader;

    public LifecycleRulesHealthIndicator(LifecycleRuleReloader reloader) {
        this.reloader = Objects.requireNonNull(reloader, "reloader");
    }

    @Override
    public Health health() {
        DefinitionRegistry registry = reloader.registry();
        List<String> problems = reloader.lastReload()
                .map(r -> r.problems().stream().map(Problem::toString).toList())
                .orElse(List.of());
        List<String> warnings = reloader.lastReload()
                .map(r -> List.copyOf(r.warnings()))
                .orElse(List.of());

        if (!registry.isLoaded()) {
            return Health.down()
                    .withDetail("loaded", false)
                    .withDetail("problems", problems.isEmpty() ? List.of("no rule snapshot has been loaded yet") : problems)
                    .withDetail("warnings", warnings)
                    .build();
        }
        Snapshot snapshot = registry.snapshot();
        return Health.up()
                .withDetail("loaded", true)
                .withDetail("version", snapshot.version())
                .withDetail("machines", snapshot.machines().size())
                .withDetail("lastReloadApplied", reloader.lastReload().map(DefinitionRegistry.ReloadResult::applied).orElse(false))
                .withDetail("problems", problems)
                .withDetail("warnings", warnings)
                .build();
    }
}
