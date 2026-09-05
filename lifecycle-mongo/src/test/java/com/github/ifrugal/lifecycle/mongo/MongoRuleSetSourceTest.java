package com.github.ifrugal.lifecycle.mongo;

import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.core.rules.StateName;
import com.github.ifrugal.lifecycle.rules.yaml.YamlRuleSetParser;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Date;

import static com.github.ifrugal.lifecycle.mongo.TestSupport.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DD-05: rules stored as documents, ACTIVE-only reads, and the governance side ({@link MongoRuleSetAdmin}). */
@Testcontainers(disabledWithoutDocker = true)
class MongoRuleSetSourceTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8");

    private static final String BASE_V1 = """
            entityType: widget
            initial: NEW
            states: [NEW, READY]
            transitions:
              - id: widget.activate
                from: NEW
                on: ACTIVATE
                to: READY
            """;

    private static final String BASE_V2 = """
            entityType: widget
            initial: NEW
            states: [NEW, READY, RETIRED]
            transitions:
              - id: widget.activate
                from: NEW
                on: ACTIVATE
                to: READY
              - id: widget.retire
                from: READY
                on: RETIRE
                to: RETIRED
            """;

    private static final String ACME_OVERLAY_V1 = """
            entityType: widget
            tenant: acme
            states: [ON_HOLD]
            transitions:
              - id: widget.hold
                from: READY
                on: HOLD
                to: ON_HOLD
            """;

    private MongoClient client;
    private MongoDatabase database;
    private MongoRuleSetSource source;
    private MongoRuleSetAdmin admin;

    @BeforeEach
    void setUp() {
        client = MongoClients.create(MONGO.getConnectionString());
        database = client.getDatabase("test_" + id().replace("-", ""));
        MongoSchema.ensureIndexes(database);
        source = new MongoRuleSetSource(database, new YamlRuleSetParser());
        admin = new MongoRuleSetAdmin(client, database);
    }

    @AfterEach
    void tearDown() {
        database.drop();
        client.close();
    }

    @Test
    void baseAndOverlayLoadAndCompileThroughDefinitionRegistry() {
        admin.insertDraft(null, "widget", 1, "yaml", BASE_V1, "tester");
        admin.activate(null, "widget", 1);
        admin.insertDraft("acme", "widget", 1, "yaml", ACME_OVERLAY_V1, "tester");
        admin.activate("acme", "widget", 1);

        DefinitionRegistry registry = new DefinitionRegistry(source, GuardRegistry.empty());
        DefinitionRegistry.ReloadResult result = registry.reload();
        assertThat(result.applied()).as("problems: %s", result.problems()).isTrue();

        Machine base = registry.machine(null, "widget").orElseThrow();
        assertThat(base.states()).contains(StateName.of("NEW"), StateName.of("READY"));

        Machine acme = registry.machine("acme", "widget").orElseThrow();
        assertThat(acme.states()).contains(StateName.of("ON_HOLD"));
        assertThat(acme.forAction("HOLD")).isNotEmpty();
    }

    @Test
    void fingerprintChangesWhenAVersionIsActivated() {
        admin.insertDraft(null, "widget", 1, "yaml", BASE_V1, "tester");
        admin.activate(null, "widget", 1);
        String fingerprintAfterV1 = source.fingerprint();

        admin.insertDraft(null, "widget", 2, "yaml", BASE_V2, "tester");
        admin.activate(null, "widget", 2);
        String fingerprintAfterV2 = source.fingerprint();

        assertThat(fingerprintAfterV2).isNotEqualTo(fingerprintAfterV1);

        // and the retired v1 row is no longer part of fetchActive()
        assertThat(source.load()).hasSize(1);
    }

    @Test
    void activateRetiresThePreviousActiveVersion() {
        admin.insertDraft(null, "widget", 1, "yaml", BASE_V1, "tester");
        admin.activate(null, "widget", 1);
        admin.insertDraft(null, "widget", 2, "yaml", BASE_V2, "tester");
        admin.activate(null, "widget", 2);

        MongoCollection<Document> raw = database.getCollection(MongoCollections.RULE_SET);
        Document v1 = raw.find(org.bson.Document.parse("{tenantId: null, entityType: 'widget', version: 1}")).first();
        Document v2 = raw.find(org.bson.Document.parse("{tenantId: null, entityType: 'widget', version: 2}")).first();
        assertThat(v1.getString("status")).isEqualTo("RETIRED");
        assertThat(v2.getString("status")).isEqualTo("ACTIVE");
    }

    @Test
    void secondActiveRowForTheSameTenantAndTypeIsRejectedByTheIndex() {
        MongoCollection<Document> raw = database.getCollection(MongoCollections.RULE_SET);
        raw.insertOne(new Document("_id", id())
                .append("tenantId", null).append("entityType", "widget").append("version", 1)
                .append("status", "ACTIVE").append("format", "yaml").append("body", BASE_V1)
                .append("createdBy", "tester").append("createdAt", Date.from(Instant.now())).append("activatedAt", Date.from(Instant.now())));

        // Insert a second ACTIVE row directly (bypassing MongoRuleSetAdmin.activate, which itself always
        // retires the current ACTIVE row first): the partial unique index must still refuse it.
        Document secondActive = new Document("_id", id())
                .append("tenantId", null).append("entityType", "widget").append("version", 2)
                .append("status", "ACTIVE").append("format", "yaml").append("body", BASE_V2)
                .append("createdBy", "tester").append("createdAt", Date.from(Instant.now())).append("activatedAt", Date.from(Instant.now()));

        assertThatThrownBy(() -> raw.insertOne(secondActive)).isInstanceOf(MongoException.class);
    }

    @Test
    void retireMarksARowRetiredWithoutActivatingAReplacement() {
        admin.insertDraft(null, "widget", 1, "yaml", BASE_V1, "tester");
        admin.activate(null, "widget", 1);

        admin.retire(null, "widget", 1);

        assertThat(source.load()).isEmpty();
    }
}
