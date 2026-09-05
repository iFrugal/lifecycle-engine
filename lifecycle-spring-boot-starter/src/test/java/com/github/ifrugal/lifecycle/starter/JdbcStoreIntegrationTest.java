package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.model.AuditOutcome;
import com.github.ifrugal.lifecycle.api.model.AuditRecord;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.EventKind;
import com.github.ifrugal.lifecycle.api.model.LifecycleEvent;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.model.RefusalReason;
import com.github.ifrugal.lifecycle.api.spi.AuditQuery;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.jdbc.JdbcRuleSetAdmin;
import com.github.ifrugal.lifecycle.jdbc.JdbcStateStore;
import com.github.ifrugal.lifecycle.starter.testapp.Events;
import com.github.ifrugal.lifecycle.starter.testapp.TestApplication;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code lifecycle.store=jdbc} against a real PostgreSQL (DD-13). Proves the three things only a database can
 * prove: the state row is where the store says it is, a tenant overlay activated in the rule set table shows up
 * on the next reload (DD-05), and the outbox relay republishes what the engine committed but did not publish.
 *
 * <p>The {@code DataSource} is the application's own bean, exactly as DD-13 requires: the starter never builds one.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {TestApplication.class, JdbcStoreIntegrationTest.AppConfiguration.class},
        properties = {
                "lifecycle.rules.files=classpath:rules/order.yaml,classpath:rules/shipment.yaml",
                "lifecycle.rules.reload.poll=0",
                "lifecycle.rules.jdbc=true",
                "lifecycle.store=jdbc",
                "lifecycle.jdbc.dialect=postgresql",
                "lifecycle.jdbc.install-schema=true",
                // The relay is driven by hand below; a five second tick would only make the test flaky.
                "lifecycle.jdbc.outbox-relay.period=1h"
        })
class JdbcStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @Configuration(proxyBeanMethods = false)
    static class AppConfiguration {

        /** The application's own DataSource. A plain driver one: no pool and no spring-jdbc needed. */
        @Bean
        DataSource dataSource() {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setUrl(POSTGRES.getJdbcUrl());
            ds.setUser(POSTGRES.getUsername());
            ds.setPassword(POSTGRES.getPassword());
            return ds;
        }

        @Bean
        GuardPredicate refundWindowOpen() {
            return new GuardPredicate() {
                @Override
                public String name() {
                    return "refund-window-open";
                }

                @Override
                public boolean test(GuardContext context) {
                    return true;
                }
            };
        }
    }

    @Autowired
    LifecycleEngine engine;
    @Autowired
    JdbcStateStore store;
    @Autowired
    DataSource dataSource;
    @Autowired
    LifecycleRulesEndpoint endpoint;
    @Autowired
    OutboxRelayScheduler relay;
    @Autowired
    InMemoryTransport transport;

    @Test
    void the_store_bean_is_the_jdbc_store_and_its_own_audit_query() {
        assertThat(store).isInstanceOf(AuditQuery.class);
    }

    @Test
    void a_handled_event_lands_in_the_database() {
        EntityRef order = EntityRef.of("order", "o-jdbc");

        Outcome outcome = engine.handle(Events.pay("o-jdbc", "s-jdbc"));

        assertThat(outcome).isInstanceOf(Outcome.Applied.class);
        assertThat(store.find(order)).hasValueSatisfying(record -> {
            assertThat(record.state()).isEqualTo("PAID");
            assertThat(record.version()).isEqualTo(1L);
        });
        assertThat(store.byEntity(order)).extracting(AuditRecord::outcome).contains(AuditOutcome.APPLIED);

        // Wait for the cross-entity cascade to settle before the method returns: the container is torn down at
        // the end of the class, and a signal still in flight would fail against a database that is already gone.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(store.find(order)).hasValueSatisfying(record ->
                        assertThat(record.state()).isEqualTo("FULFILLING")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_tenant_overlay_activated_in_the_table_appears_after_a_reload() {
        assertThat(machineKeys()).doesNotContain("acme:order");

        JdbcRuleSetAdmin admin = new JdbcRuleSetAdmin(dataSource);
        long id = admin.insertDraft("acme", "order", 1, "yaml", resource("rules-tenant/acme-order.yaml"), "test");
        admin.activate(id);

        Map<String, Object> reloaded = endpoint.reload();

        assertThat(reloaded).containsEntry("applied", true);
        assertThat((List<Map<String, Object>>) reloaded.get("machines"))
                .extracting(m -> m.get("key"))
                .contains("acme:order");
        // The overlay adds a state and a transition that the base machine does not have (DD-05).
        assertThat(endpoint.diagram("acme:order")).contains("ON_HOLD");
    }

    @Test
    void the_outbox_relay_drains_what_the_engine_did_not_publish() {
        // A commit whose outbox row is never marked sent: exactly the crash-between-commit-and-publish gap the
        // relay exists to repair (DD-07).
        EntityRef order = EntityRef.of("order", "o-relay");
        String eventId = UUID.randomUUID().toString();
        Actor actor = Actor.service("test");
        LifecycleEvent stranded = LifecycleEvent.builder()
                .kind(EventKind.NOTIFICATION)
                .entity(order)
                .action("RelayProbe")
                .actor(actor)
                .payload(Map.of("probe", true))
                .build();
        AuditRecord audit = new AuditRecord(UUID.randomUUID().toString(), eventId, order, "PROBE", actor,
                null, null, null, AuditOutcome.REFUSED, RefusalReason.NO_MATCH, "relay probe", "test",
                Instant.now(), null);

        assertThat(store.commit(new Commit(order, 0L, null, eventId, audit, List.of(stranded))))
                .isInstanceOf(CommitResult.Committed.class);
        assertThat(store.jdbcOutbox().unsent(10)).extracting(LifecycleEvent::eventId).contains(stranded.eventId());

        relay.drainNow();

        assertThat(store.jdbcOutbox().unsent(10)).isEmpty();
        assertThat(transport.notifications()).extracting(LifecycleEvent::eventId).contains(stranded.eventId());
    }

    @SuppressWarnings("unchecked")
    private List<String> machineKeys() {
        return ((List<Map<String, Object>>) endpoint.rules().get("machines")).stream()
                .map(m -> String.valueOf(m.get("key")))
                .toList();
    }

    private static String resource(String name) {
        try (InputStream in = JdbcStoreIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
