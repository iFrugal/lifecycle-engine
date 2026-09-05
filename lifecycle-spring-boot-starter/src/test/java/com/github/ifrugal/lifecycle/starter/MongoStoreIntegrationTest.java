package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.guard.GuardContext;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.api.model.Outcome;
import com.github.ifrugal.lifecycle.api.spi.AuditQuery;
import com.github.ifrugal.lifecycle.mongo.MongoRuleSetAdmin;
import com.github.ifrugal.lifecycle.mongo.MongoStateStore;
import com.github.ifrugal.lifecycle.starter.testapp.Events;
import com.github.ifrugal.lifecycle.starter.testapp.TestApplication;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@code lifecycle.store=mongo} against a real MongoDB (DD-13). Same shape as the JDBC slice: the state document
 * is where the store says it is, the cascade completes, and an overlay activated in the rule set collection
 * shows up on the next reload. The container is a single-node replica set, so {@code lifecycle.mongo.transactions}
 * can stay at its default.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = {TestApplication.class, MongoStoreIntegrationTest.AppConfiguration.class},
        properties = {
                "lifecycle.rules.files=classpath:rules/order.yaml,classpath:rules/shipment.yaml",
                "lifecycle.rules.reload.poll=0",
                "lifecycle.rules.mongo=true",
                "lifecycle.store=mongo",
                "lifecycle.mongo.database=lifecycle-starter-test",
                "lifecycle.mongo.outbox-relay.period=1h"
        })
class MongoStoreIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    @Configuration(proxyBeanMethods = false)
    static class AppConfiguration {

        /** The application's own MongoClient, exactly as DD-13 requires; the starter never builds one. */
        @Bean(destroyMethod = "close")
        MongoClient mongoClient() {
            return MongoClients.create(MONGO.getConnectionString());
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
    MongoStateStore store;
    @Autowired
    MongoClient client;
    @Autowired
    LifecycleRulesEndpoint endpoint;
    @Autowired
    OutboxRelayScheduler relay;

    @Test
    void the_store_bean_is_the_mongo_store_and_its_own_audit_query() {
        assertThat(store).isInstanceOf(AuditQuery.class);
        assertThat(relay).isNotNull();
    }

    @Test
    void a_handled_event_lands_in_the_collection_and_the_cascade_completes() {
        Outcome outcome = engine.handle(Events.pay("o-mongo", "s-mongo"));

        assertThat(outcome).isInstanceOf(Outcome.Applied.class);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(store.find(EntityRef.of("shipment", "s-mongo"))).hasValueSatisfying(
                    record -> assertThat(record.state()).isEqualTo("PREPARED"));
            assertThat(store.find(EntityRef.of("order", "o-mongo"))).hasValueSatisfying(
                    record -> assertThat(record.state()).isEqualTo("FULFILLING"));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_tenant_overlay_activated_in_the_collection_appears_after_a_reload() {
        MongoRuleSetAdmin admin = new MongoRuleSetAdmin(client, client.getDatabase("lifecycle-starter-test"));
        admin.insertDraft("acme", "order", 1, "yaml", resource("rules-tenant/acme-order.yaml"), "test");
        admin.activate("acme", "order", 1);

        Map<String, Object> reloaded = endpoint.reload();

        assertThat(reloaded).containsEntry("applied", true);
        assertThat((List<Map<String, Object>>) reloaded.get("machines"))
                .extracting(m -> m.get("key"))
                .contains("acme:order");
    }

    private static String resource(String name) {
        try (InputStream in = MongoStoreIntegrationTest.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
