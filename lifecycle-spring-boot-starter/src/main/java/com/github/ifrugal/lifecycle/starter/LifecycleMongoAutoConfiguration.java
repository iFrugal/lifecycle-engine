package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.mongo.MongoOutboxRelay;
import com.github.ifrugal.lifecycle.mongo.MongoRuleSetSource;
import com.github.ifrugal.lifecycle.mongo.MongoSchema;
import com.github.ifrugal.lifecycle.mongo.MongoStateStore;
import com.github.ifrugal.lifecycle.mongo.MongoTaskStore;
import com.github.ifrugal.lifecycle.tasks.TaskStore;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * The MongoDB backend (DD-13, {@code lifecycle.store=mongo} and {@code lifecycle.rules.mongo=true}). Takes the
 * application's own {@code MongoClient} bean and the database named by {@code lifecycle.mongo.database}. The
 * {@code MongoDatabase} handle is resolved per bean rather than published as a bean of its own, so nothing here
 * can collide with Spring Data MongoDB's own beans.
 */
@AutoConfiguration(before = LifecycleCoreAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "lifecycle.enabled", matchIfMissing = true)
@ConditionalOnClass({MongoStateStore.class, MongoClient.class})
@ConditionalOnBean(MongoClient.class)
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleMongoAutoConfiguration {

    /** Marker proving {@code MongoSchema.ensureIndexes} has run. Same ordering trick as the JDBC schema. */
    public static final class IndexesEnsured {
        private IndexesEnsured() {}
    }

    /**
     * True when Mongo is actually used for something. An application that merely has a {@code MongoClient} bean
     * must not have collections and indexes created in a {@code lifecycle} database it never asked for.
     */
    static final class MongoIsUsed extends AnyNestedCondition {

        MongoIsUsed() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "lifecycle", name = "store", havingValue = "mongo")
        static final class AsTheStore {}

        @ConditionalOnBooleanProperty(name = "lifecycle.rules.mongo")
        static final class AsARuleSource {}
    }

    static MongoDatabase database(MongoClient client, LifecycleProperties properties) {
        return client.getDatabase(properties.getMongo().getDatabase());
    }

    @Bean
    @ConditionalOnMissingBean
    @Conditional(MongoIsUsed.class)
    public IndexesEnsured lifecycleMongoIndexes(MongoClient client, LifecycleProperties properties) {
        MongoSchema.ensureIndexes(database(client, properties));
        return new IndexesEnsured();
    }

    /** {@code lifecycle.store=mongo}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "lifecycle", name = "store", havingValue = "mongo")
    public static class StoreConfiguration {

        /** Bean 3. Concrete type, so the same singleton satisfies {@code AuditQuery} injection points. */
        @Bean
        @ConditionalOnMissingBean(StateStore.class)
        public MongoStateStore lifecycleStateStore(MongoClient client, LifecycleProperties properties,
                                                   ObjectProvider<IndexesEnsured> indexes) {
            indexes.getIfAvailable();
            return new MongoStateStore(client, database(client, properties),
                    properties.getMongo().getInboxRetention(), properties.getMongo().isTransactions());
        }

        /** Bean 8. */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = "lifecycle.mongo.outbox-relay.enabled", matchIfMissing = true)
        public OutboxRelayScheduler lifecycleOutboxRelayScheduler(MongoStateStore store, Transport transport,
                                                                  LifecycleProperties properties) {
            LifecycleProperties.OutboxRelay relay = properties.getMongo().getOutboxRelay();
            Outbox outbox = store.outbox().orElseThrow(
                    () -> new IllegalStateException("the Mongo state store exposes no outbox to relay"));
            return new OutboxRelayScheduler(new MongoOutboxRelay(outbox, transport, relay.getBatchSize()),
                    relay.getPeriod(), "lifecycle-mongo-outbox-relay");
        }

        /** Bean 9's storage half, when {@code lifecycle.tasks.enabled=true}. */
        @Bean
        @ConditionalOnClass(TaskStore.class)
        @ConditionalOnMissingBean(TaskStore.class)
        @ConditionalOnBooleanProperty(name = "lifecycle.tasks.enabled")
        public MongoTaskStore lifecycleTaskStore(MongoClient client, LifecycleProperties properties,
                                                 ObjectProvider<IndexesEnsured> indexes) {
            indexes.getIfAvailable();
            return new MongoTaskStore(database(client, properties));
        }
    }

    /** {@code lifecycle.rules.mongo=true}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "lifecycle.rules.mongo")
    public static class RulesConfiguration {

        @Bean
        public LifecycleRuleSource lifecycleMongoRuleSource(MongoClient client, LifecycleProperties properties,
                                                            RuleSetParser parser, ObjectProvider<IndexesEnsured> indexes) {
            indexes.getIfAvailable();
            return new LifecycleRuleSource(LifecycleRuleSource.MONGO_ORDER,
                    new MongoRuleSetSource(database(client, properties), parser));
        }
    }
}
