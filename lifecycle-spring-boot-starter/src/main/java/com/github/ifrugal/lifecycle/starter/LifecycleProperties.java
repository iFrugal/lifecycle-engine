package com.github.ifrugal.lifecycle.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code lifecycle.*} property tree of DD-13. Plain mutable nested types rather than records so that a
 * partially specified block keeps the defaults of the keys it does not mention, which is what an application
 * expects from {@code application.yaml}.
 *
 * <p>The two selectors, {@link #getStore()} and {@link #getTransport()}, are explicit on purpose: nothing is
 * auto-selected from the classpath, because two adapters present would pick one silently (DD-13, Rejected).
 */
@ConfigurationProperties("lifecycle")
public class LifecycleProperties {

    /** Master switch. When false no lifecycle bean is created at all. */
    private boolean enabled = true;

    private final Rules rules = new Rules();

    /** Which {@code StateStore} to wire. */
    private Store store = Store.MEMORY;

    private final Jdbc jdbc = new Jdbc();

    private final Mongo mongo = new Mongo();

    /** Which {@code Transport} to wire. */
    private TransportKind transport = TransportKind.MEMORY;

    private final Kafka kafka = new Kafka();

    private final Dispatcher dispatcher = new Dispatcher();

    private final Tasks tasks = new Tasks();

    private final Engine engine = new Engine();

    private final Management management = new Management();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Rules getRules() {
        return rules;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public Mongo getMongo() {
        return mongo;
    }

    public TransportKind getTransport() {
        return transport;
    }

    public void setTransport(TransportKind transport) {
        this.transport = transport;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    public Tasks getTasks() {
        return tasks;
    }

    public Engine getEngine() {
        return engine;
    }

    public Management getManagement() {
        return management;
    }

    /** {@code lifecycle.store} */
    public enum Store { MEMORY, JDBC, MONGO }

    /** {@code lifecycle.transport} */
    public enum TransportKind { MEMORY, KAFKA }

    /**
     * {@code lifecycle.jdbc.dialect}. A local mirror of {@code com.github.ifrugal.lifecycle.jdbc.Dialect}: this
     * properties class is bound even when lifecycle-jdbc is absent from the classpath, so it may not name a type
     * from an optional module.
     */
    public enum SqlDialect { POSTGRESQL, MYSQL, H2 }

    /** {@code lifecycle.rules} */
    public static class Rules {

        /**
         * Where rule documents come from, in order. Each entry is {@code classpath:<resource>},
         * {@code file:<path>}, or a bare filesystem path (treated as {@code file:}).
         *
         * <p>A {@code classpath:} entry normally names one resource exactly ({@code classpath:rules/order.yaml}):
         * a classpath "directory" cannot be listed in the general case, so nothing is scanned. As a convenience
         * for the exploded-classes case, an entry that resolves to a {@code file:} URL pointing at a real
         * directory (typical in an IDE or a plain {@code mvn test} run, not inside a jar) is walked like a
         * filesystem directory. A {@code classpath:} directory that resolves inside a jar is refused with a
         * message telling you to list the resources.
         */
        private List<String> files = new ArrayList<>();

        /** Add a {@code JdbcRuleSetSource} to the composite. Needs a {@code DataSource} bean. */
        private boolean jdbc = false;

        /** Add a {@code MongoRuleSetSource} to the composite. Needs a {@code MongoClient} bean. */
        private boolean mongo = false;

        private final Reload reload = new Reload();

        public List<String> getFiles() {
            return files;
        }

        public void setFiles(List<String> files) {
            this.files = files == null ? new ArrayList<>() : files;
        }

        public boolean isJdbc() {
            return jdbc;
        }

        public void setJdbc(boolean jdbc) {
            this.jdbc = jdbc;
        }

        public boolean isMongo() {
            return mongo;
        }

        public void setMongo(boolean mongo) {
            this.mongo = mongo;
        }

        public Reload getReload() {
            return reload;
        }
    }

    /** {@code lifecycle.rules.reload} */
    public static class Reload {

        /** Poll interval for the rule source fingerprint. Zero or negative disables polling. */
        private Duration poll = Duration.ofSeconds(30);

        /** Refuse to start when the first load has problems. */
        private boolean failFast = true;

        public Duration getPoll() {
            return poll;
        }

        public void setPoll(Duration poll) {
            this.poll = poll;
        }

        public boolean isFailFast() {
            return failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }
    }

    /** {@code lifecycle.jdbc} */
    public static class Jdbc {

        private SqlDialect dialect = SqlDialect.POSTGRESQL;

        /** Run {@code SchemaInstaller} at startup. Development convenience; use Flyway or Liquibase for real. */
        private boolean installSchema = false;

        /** How long an inbox row survives, after which natural idempotence takes over (DD-07). */
        private Duration inboxRetention = Duration.ofDays(7);

        private final OutboxRelay outboxRelay = new OutboxRelay();

        public SqlDialect getDialect() {
            return dialect;
        }

        public void setDialect(SqlDialect dialect) {
            this.dialect = dialect;
        }

        public boolean isInstallSchema() {
            return installSchema;
        }

        public void setInstallSchema(boolean installSchema) {
            this.installSchema = installSchema;
        }

        public Duration getInboxRetention() {
            return inboxRetention;
        }

        public void setInboxRetention(Duration inboxRetention) {
            this.inboxRetention = inboxRetention;
        }

        public OutboxRelay getOutboxRelay() {
            return outboxRelay;
        }
    }

    /** {@code lifecycle.mongo} */
    public static class Mongo {

        /** Database name on the application's {@code MongoClient} bean. */
        private String database = "lifecycle";

        /** Use a multi-document transaction per commit. Requires a replica set. */
        private boolean transactions = true;

        private Duration inboxRetention = Duration.ofDays(7);

        private final OutboxRelay outboxRelay = new OutboxRelay();

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public boolean isTransactions() {
            return transactions;
        }

        public void setTransactions(boolean transactions) {
            this.transactions = transactions;
        }

        public Duration getInboxRetention() {
            return inboxRetention;
        }

        public void setInboxRetention(Duration inboxRetention) {
            this.inboxRetention = inboxRetention;
        }

        public OutboxRelay getOutboxRelay() {
            return outboxRelay;
        }
    }

    /** {@code lifecycle.<store>.outbox-relay} */
    public static class OutboxRelay {

        private boolean enabled = true;

        private Duration period = Duration.ofSeconds(5);

        private int batchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPeriod() {
            return period;
        }

        public void setPeriod(Duration period) {
            this.period = period;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /** {@code lifecycle.kafka} */
    public static class Kafka {

        /** Kafka {@code bootstrap.servers}. Required when {@code lifecycle.transport=kafka}. */
        private String bootstrapServers;

        private String signalsTopic = "lifecycle.signals";

        private String notificationsTopic = "lifecycle.notifications";

        private String deadLetterTopic = "lifecycle.signals.dlq";

        private String consumerGroup = "lifecycle-engine";

        private int maxDeliveryAttempts = 5;

        private Duration retryBackoff = Duration.ofMillis(200);

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getSignalsTopic() {
            return signalsTopic;
        }

        public void setSignalsTopic(String signalsTopic) {
            this.signalsTopic = signalsTopic;
        }

        public String getNotificationsTopic() {
            return notificationsTopic;
        }

        public void setNotificationsTopic(String notificationsTopic) {
            this.notificationsTopic = notificationsTopic;
        }

        public String getDeadLetterTopic() {
            return deadLetterTopic;
        }

        public void setDeadLetterTopic(String deadLetterTopic) {
            this.deadLetterTopic = deadLetterTopic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public int getMaxDeliveryAttempts() {
            return maxDeliveryAttempts;
        }

        public void setMaxDeliveryAttempts(int maxDeliveryAttempts) {
            this.maxDeliveryAttempts = maxDeliveryAttempts;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }
    }

    /** {@code lifecycle.dispatcher} */
    public static class Dispatcher {

        /** How often a {@code Conflicted} outcome is retried from a fresh read before redelivery is asked for. */
        private int conflictRetries = 3;

        public int getConflictRetries() {
            return conflictRetries;
        }

        public void setConflictRetries(int conflictRetries) {
            this.conflictRetries = conflictRetries;
        }
    }

    /** {@code lifecycle.tasks} */
    public static class Tasks {

        /** Wire a {@code TaskService}, a {@code TaskStore} matching {@code lifecycle.store}, and a consumer. */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** {@code lifecycle.engine} */
    public static class Engine {

        /** Actor id and role stamped on every event the engine itself emits. */
        private String actorId = "lifecycle-engine";

        public String getActorId() {
            return actorId;
        }

        public void setActorId(String actorId) {
            this.actorId = actorId;
        }
    }

    /** {@code lifecycle.management} */
    public static class Management {

        /** Actuator endpoint {@code lifecyclerules}: read the snapshot, write to reload it. */
        private boolean reloadEndpoint = true;

        /** {@code lifecycleRules} health indicator. */
        private boolean health = true;

        /** Micrometer meters: {@code lifecycle.outcomes}, {@code lifecycle.reloads}, {@code lifecycle.outbox.pending}. */
        private boolean metrics = true;

        public boolean isReloadEndpoint() {
            return reloadEndpoint;
        }

        public void setReloadEndpoint(boolean reloadEndpoint) {
            this.reloadEndpoint = reloadEndpoint;
        }

        public boolean isHealth() {
            return health;
        }

        public void setHealth(boolean health) {
            this.health = health;
        }

        public boolean isMetrics() {
            return metrics;
        }

        public void setMetrics(boolean metrics) {
            this.metrics = metrics;
        }
    }
}
