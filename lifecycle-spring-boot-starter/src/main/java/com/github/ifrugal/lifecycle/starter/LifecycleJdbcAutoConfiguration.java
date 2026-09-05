package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.jdbc.Dialect;
import com.github.ifrugal.lifecycle.jdbc.JdbcOutboxRelay;
import com.github.ifrugal.lifecycle.jdbc.JdbcRuleSetSource;
import com.github.ifrugal.lifecycle.jdbc.JdbcStateStore;
import com.github.ifrugal.lifecycle.jdbc.JdbcTaskStore;
import com.github.ifrugal.lifecycle.jdbc.SchemaInstaller;
import com.github.ifrugal.lifecycle.tasks.TaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * The JDBC backend (DD-13, {@code lifecycle.store=jdbc} and {@code lifecycle.rules.jdbc=true}). Takes the
 * application's own {@code DataSource} bean: the starter does not create or configure one, and does not depend
 * on spring-jdbc.
 *
 * <p>Runs {@code before} {@link LifecycleCoreAutoConfiguration} so that its {@code JdbcStateStore} is registered
 * before the in-memory default is considered.
 */
@AutoConfiguration(before = LifecycleCoreAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "lifecycle.enabled", matchIfMissing = true)
@ConditionalOnClass({JdbcStateStore.class, DataSource.class})
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleJdbcAutoConfiguration {

    /**
     * Marker proving the DDL has run. Everything that touches a lifecycle table takes it as an
     * {@code ObjectProvider} so installation is ordered ahead of the first query without a {@code @DependsOn}
     * that would break when installation is switched off.
     */
    public static final class SchemaInstalled {
        private SchemaInstalled() {}
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "lifecycle.jdbc.install-schema")
    public SchemaInstalled lifecycleJdbcSchema(DataSource dataSource, LifecycleProperties properties) {
        SchemaInstaller.install(dataSource, dialect(properties));
        return new SchemaInstalled();
    }

    static Dialect dialect(LifecycleProperties properties) {
        return Dialect.valueOf(properties.getJdbc().getDialect().name());
    }

    /** {@code lifecycle.store=jdbc}: the store, the outbox relay, and the task store. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "lifecycle", name = "store", havingValue = "jdbc")
    public static class StoreConfiguration {

        /** Bean 3. Concrete type, so the same singleton satisfies {@code AuditQuery} injection points. */
        @Bean
        @ConditionalOnMissingBean(StateStore.class)
        public JdbcStateStore lifecycleStateStore(DataSource dataSource, LifecycleProperties properties,
                                                  ObjectProvider<SchemaInstalled> schema) {
            schema.getIfAvailable();
            return new JdbcStateStore(dataSource, dialect(properties), properties.getJdbc().getInboxRetention());
        }

        /** Bean 8. */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = "lifecycle.jdbc.outbox-relay.enabled", matchIfMissing = true)
        public OutboxRelayScheduler lifecycleOutboxRelayScheduler(JdbcStateStore store, Transport transport,
                                                                  LifecycleProperties properties) {
            LifecycleProperties.OutboxRelay relay = properties.getJdbc().getOutboxRelay();
            return new OutboxRelayScheduler(
                    new JdbcOutboxRelay(store.jdbcOutbox(), transport, relay.getBatchSize()),
                    relay.getPeriod(), "lifecycle-jdbc-outbox-relay");
        }

        /** Bean 9's storage half, when {@code lifecycle.tasks.enabled=true}. */
        @Bean
        @ConditionalOnClass(TaskStore.class)
        @ConditionalOnMissingBean(TaskStore.class)
        @ConditionalOnBooleanProperty(name = "lifecycle.tasks.enabled")
        public JdbcTaskStore lifecycleTaskStore(DataSource dataSource, ObjectProvider<SchemaInstalled> schema) {
            schema.getIfAvailable();
            return new JdbcTaskStore(dataSource);
        }
    }

    /** {@code lifecycle.rules.jdbc=true}: tenant overlays (or whole rule sets) read from the rule set table. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "lifecycle.rules.jdbc")
    public static class RulesConfiguration {

        @Bean
        public LifecycleRuleSource lifecycleJdbcRuleSource(DataSource dataSource, RuleSetParser parser,
                                                           ObjectProvider<SchemaInstalled> schema) {
            schema.getIfAvailable();
            return new LifecycleRuleSource(LifecycleRuleSource.JDBC_ORDER, new JdbcRuleSetSource(dataSource, parser));
        }
    }
}
