package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.rules.RuleSetValidationException;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.starter.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code lifecycle.rules.reload.fail-fast} (DD-13). The rule file under {@code rules-bad} moves an order to a
 * state it never declares, which the compiler reports as a problem rather than tolerating.
 *
 * <p>These start their own application rather than using {@code @SpringBootTest}, because half the point is that
 * one of them must never produce a usable context at all.
 */
class FailFastTest {

    private static SpringApplicationBuilder application(boolean failFast) {
        return new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "lifecycle.rules.files=classpath:rules-bad/order.yaml",
                        "lifecycle.rules.reload.poll=0",
                        "lifecycle.rules.reload.fail-fast=" + failFast);
    }

    @Test
    void refuses_to_start_when_the_first_load_has_problems() {
        assertThatThrownBy(() -> application(true).run().close())
                .satisfies(thrown -> assertThat(causeChain(thrown))
                        .anySatisfy(cause -> assertThat(cause).isInstanceOf(RuleSetValidationException.class)));
    }

    @Test
    void starts_and_reports_health_down_when_fail_fast_is_off() {
        try (ConfigurableApplicationContext context = application(false).run()) {
            DefinitionRegistry registry = context.getBean(DefinitionRegistry.class);
            assertThat(registry.isLoaded()).isFalse();

            Health health = context.getBean(LifecycleRulesHealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("loaded", false);
            assertThat((List<?>) health.getDetails().get("problems"))
                    .isNotEmpty()
                    .anySatisfy(problem -> assertThat(String.valueOf(problem)).contains("NOT_A_DECLARED_STATE"));

            // The endpoint reports the same failure without pretending a snapshot exists.
            assertThat(context.getBean(LifecycleRulesEndpoint.class).rules())
                    .containsEntry("version", null)
                    .containsEntry("machines", List.of());
        }
    }

    private static List<Throwable> causeChain(Throwable thrown) {
        List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
