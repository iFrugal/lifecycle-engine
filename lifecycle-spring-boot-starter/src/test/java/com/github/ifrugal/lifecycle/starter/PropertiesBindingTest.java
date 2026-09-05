package com.github.ifrugal.lifecycle.starter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every key in the DD-13 property tree binds, and binds to the default the document states. This is the
 * regression test for the document itself: a rename here is a breaking change for every application.
 */
class PropertiesBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LifecycleProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void defaults_match_the_documented_tree() {
        runner.run(context -> {
            LifecycleProperties p = context.getBean(LifecycleProperties.class);

            assertThat(p.isEnabled()).isTrue();
            assertThat(p.getRules().getFiles()).isEmpty();
            assertThat(p.getRules().isJdbc()).isFalse();
            assertThat(p.getRules().isMongo()).isFalse();
            assertThat(p.getRules().getReload().getPoll()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.getRules().getReload().isFailFast()).isTrue();

            assertThat(p.getStore()).isEqualTo(LifecycleProperties.Store.MEMORY);

            assertThat(p.getJdbc().getDialect()).isEqualTo(LifecycleProperties.SqlDialect.POSTGRESQL);
            assertThat(p.getJdbc().isInstallSchema()).isFalse();
            assertThat(p.getJdbc().getInboxRetention()).isEqualTo(Duration.ofDays(7));
            assertThat(p.getJdbc().getOutboxRelay().isEnabled()).isTrue();
            assertThat(p.getJdbc().getOutboxRelay().getPeriod()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.getJdbc().getOutboxRelay().getBatchSize()).isEqualTo(100);

            assertThat(p.getMongo().getDatabase()).isEqualTo("lifecycle");
            assertThat(p.getMongo().isTransactions()).isTrue();
            assertThat(p.getMongo().getInboxRetention()).isEqualTo(Duration.ofDays(7));
            assertThat(p.getMongo().getOutboxRelay().isEnabled()).isTrue();
            assertThat(p.getMongo().getOutboxRelay().getPeriod()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.getMongo().getOutboxRelay().getBatchSize()).isEqualTo(100);

            assertThat(p.getTransport()).isEqualTo(LifecycleProperties.TransportKind.MEMORY);

            assertThat(p.getKafka().getBootstrapServers()).isNull();
            assertThat(p.getKafka().getSignalsTopic()).isEqualTo("lifecycle.signals");
            assertThat(p.getKafka().getNotificationsTopic()).isEqualTo("lifecycle.notifications");
            assertThat(p.getKafka().getDeadLetterTopic()).isEqualTo("lifecycle.signals.dlq");
            assertThat(p.getKafka().getConsumerGroup()).isEqualTo("lifecycle-engine");
            assertThat(p.getKafka().getMaxDeliveryAttempts()).isEqualTo(5);
            assertThat(p.getKafka().getRetryBackoff()).isEqualTo(Duration.ofMillis(200));

            assertThat(p.getDispatcher().getConflictRetries()).isEqualTo(3);
            assertThat(p.getTasks().isEnabled()).isFalse();
            assertThat(p.getEngine().getActorId()).isEqualTo("lifecycle-engine");

            assertThat(p.getManagement().isReloadEndpoint()).isTrue();
            assertThat(p.getManagement().isHealth()).isTrue();
            assertThat(p.getManagement().isMetrics()).isTrue();
        });
    }

    @Test
    void every_documented_key_binds() {
        runner.withPropertyValues(
                "lifecycle.enabled=false",
                "lifecycle.rules.files=classpath:rules/order.yaml,file:/etc/app/rules/",
                "lifecycle.rules.jdbc=true",
                "lifecycle.rules.mongo=true",
                "lifecycle.rules.reload.poll=45s",
                "lifecycle.rules.reload.fail-fast=false",
                "lifecycle.store=jdbc",
                "lifecycle.jdbc.dialect=h2",
                "lifecycle.jdbc.install-schema=true",
                "lifecycle.jdbc.inbox-retention=3d",
                "lifecycle.jdbc.outbox-relay.enabled=false",
                "lifecycle.jdbc.outbox-relay.period=2s",
                "lifecycle.jdbc.outbox-relay.batch-size=25",
                "lifecycle.mongo.database=orders",
                "lifecycle.mongo.transactions=false",
                "lifecycle.mongo.inbox-retention=1d",
                "lifecycle.mongo.outbox-relay.enabled=false",
                "lifecycle.mongo.outbox-relay.period=7s",
                "lifecycle.mongo.outbox-relay.batch-size=50",
                "lifecycle.transport=kafka",
                "lifecycle.kafka.bootstrap-servers=broker:9092",
                "lifecycle.kafka.signals-topic=sig",
                "lifecycle.kafka.notifications-topic=note",
                "lifecycle.kafka.dead-letter-topic=dlq",
                "lifecycle.kafka.consumer-group=group",
                "lifecycle.kafka.max-delivery-attempts=9",
                "lifecycle.kafka.retry-backoff=750ms",
                "lifecycle.dispatcher.conflict-retries=7",
                "lifecycle.tasks.enabled=true",
                "lifecycle.engine.actor-id=my-engine",
                "lifecycle.management.reload-endpoint=false",
                "lifecycle.management.health=false",
                "lifecycle.management.metrics=false"
        ).run(context -> {
            LifecycleProperties p = context.getBean(LifecycleProperties.class);

            assertThat(p.isEnabled()).isFalse();
            assertThat(p.getRules().getFiles())
                    .isEqualTo(List.of("classpath:rules/order.yaml", "file:/etc/app/rules/"));
            assertThat(p.getRules().isJdbc()).isTrue();
            assertThat(p.getRules().isMongo()).isTrue();
            assertThat(p.getRules().getReload().getPoll()).isEqualTo(Duration.ofSeconds(45));
            assertThat(p.getRules().getReload().isFailFast()).isFalse();

            assertThat(p.getStore()).isEqualTo(LifecycleProperties.Store.JDBC);
            assertThat(p.getJdbc().getDialect()).isEqualTo(LifecycleProperties.SqlDialect.H2);
            assertThat(p.getJdbc().isInstallSchema()).isTrue();
            assertThat(p.getJdbc().getInboxRetention()).isEqualTo(Duration.ofDays(3));
            assertThat(p.getJdbc().getOutboxRelay().isEnabled()).isFalse();
            assertThat(p.getJdbc().getOutboxRelay().getPeriod()).isEqualTo(Duration.ofSeconds(2));
            assertThat(p.getJdbc().getOutboxRelay().getBatchSize()).isEqualTo(25);

            assertThat(p.getMongo().getDatabase()).isEqualTo("orders");
            assertThat(p.getMongo().isTransactions()).isFalse();
            assertThat(p.getMongo().getInboxRetention()).isEqualTo(Duration.ofDays(1));
            assertThat(p.getMongo().getOutboxRelay().isEnabled()).isFalse();
            assertThat(p.getMongo().getOutboxRelay().getPeriod()).isEqualTo(Duration.ofSeconds(7));
            assertThat(p.getMongo().getOutboxRelay().getBatchSize()).isEqualTo(50);

            assertThat(p.getTransport()).isEqualTo(LifecycleProperties.TransportKind.KAFKA);
            assertThat(p.getKafka().getBootstrapServers()).isEqualTo("broker:9092");
            assertThat(p.getKafka().getSignalsTopic()).isEqualTo("sig");
            assertThat(p.getKafka().getNotificationsTopic()).isEqualTo("note");
            assertThat(p.getKafka().getDeadLetterTopic()).isEqualTo("dlq");
            assertThat(p.getKafka().getConsumerGroup()).isEqualTo("group");
            assertThat(p.getKafka().getMaxDeliveryAttempts()).isEqualTo(9);
            assertThat(p.getKafka().getRetryBackoff()).isEqualTo(Duration.ofMillis(750));

            assertThat(p.getDispatcher().getConflictRetries()).isEqualTo(7);
            assertThat(p.getTasks().isEnabled()).isTrue();
            assertThat(p.getEngine().getActorId()).isEqualTo("my-engine");

            assertThat(p.getManagement().isReloadEndpoint()).isFalse();
            assertThat(p.getManagement().isHealth()).isFalse();
            assertThat(p.getManagement().isMetrics()).isFalse();
        });
    }

    /** {@code lifecycle.enabled=false} must leave the application with no lifecycle bean at all. */
    @Test
    void disabling_the_starter_creates_nothing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LifecycleCoreAutoConfiguration.class,
                        LifecycleManagementAutoConfiguration.class))
                .withPropertyValues("lifecycle.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(com.github.ifrugal.lifecycle.api.LifecycleEngine.class)
                        .doesNotHaveBean(LifecycleRuleReloader.class)
                        .doesNotHaveBean(LifecycleRulesEndpoint.class));
    }
}
