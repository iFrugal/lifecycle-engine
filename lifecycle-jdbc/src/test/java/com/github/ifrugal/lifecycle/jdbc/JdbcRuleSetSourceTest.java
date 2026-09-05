package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.core.rules.Machine;
import com.github.ifrugal.lifecycle.rules.yaml.YamlRuleSetParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DD-05: rules stored as documents, read as ACTIVE rows, parsed by the same parser that reads files. A stored
 * rule set and a file rule set are the same bytes.
 */
class JdbcRuleSetSourceTest {

    private static final String BASE = """
            entityType: widget
            initial: NEW
            states: [NEW, DONE]
            transitions:
              - id: widget.finish
                from: NEW
                on: FINISH
                roles: [ops]
                to: DONE
            """;

    private static final String BASE_V2 = """
            entityType: widget
            initial: NEW
            states: [NEW, DONE, ARCHIVED]
            transitions:
              - id: widget.finish
                from: NEW
                on: FINISH
                roles: [ops]
                to: DONE
              - id: widget.archive
                from: DONE
                on: ARCHIVE
                roles: [ops]
                to: ARCHIVED
            """;

    private static final String ACME_OVERLAY = """
            entityType: widget
            tenant: acme
            states: [ON_HOLD]
            transitions:
              - id: widget.hold
                from: NEW
                on: HOLD
                roles: [store-manager]
                to: ON_HOLD
            """;

    private static final DataSource DS = TestDatabases.h2();

    private JdbcRuleSetAdmin admin;
    private JdbcRuleSetSource source;
    private JdbcStateStore store;

    @BeforeEach
    void setUp() {
        SchemaInstaller.install(DS, Dialect.H2);
        TestDatabases.truncateAll(DS);
        admin = new JdbcRuleSetAdmin(DS);
        source = new JdbcRuleSetSource(DS, new YamlRuleSetParser());
        store = new JdbcStateStore(DS, Dialect.H2);
    }

    @Test
    void draftsAreInvisibleUntilTheyAreActivated() {
        long id = admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice");

        assertThat(source.load()).as("the engine reads ACTIVE rows only").isEmpty();

        admin.activate(id);
        assertThat(source.load()).hasSize(1);
        assertThat(TestDatabases.scalar(DS, "select count(*) from lifecycle_rule_set where activated_at is not null")).isEqualTo(1);
    }

    @Test
    void aBaseAndATenantOverlayCompileIntoTwoMachines() {
        admin.activate(admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice"));
        admin.activate(admin.insertDraft("acme", "widget", 1, "yaml", ACME_OVERLAY, "alice"));

        DefinitionRegistry registry = new DefinitionRegistry(source, GuardRegistry.empty(), store);
        DefinitionRegistry.ReloadResult result = registry.reload();

        assertThat(result.applied()).as("problems: %s", result.problems()).isTrue();
        assertThat(registry.snapshot().machines()).hasSize(2);

        Machine base = registry.machine(null, "widget").orElseThrow();
        assertThat(base.transitions()).extracting(t -> t.id()).containsExactly("widget.finish");

        Machine acme = registry.machine("acme", "widget").orElseThrow();
        assertThat(acme.transitions()).extracting(t -> t.id()).containsExactlyInAnyOrder("widget.finish", "widget.hold");
        assertThat(acme.states()).extracting(s -> s.value()).contains("ON_HOLD");
        assertThat(acme.initial().value()).as("the overlay inherits the base initial state").isEqualTo("NEW");

        // A tenant with no overlay falls back to the base (DD-05).
        assertThat(registry.machine("globex", "widget").orElseThrow().transitions()).hasSize(1);
    }

    @Test
    void theRowIsAuthoritativeForTenantAndType() {
        // The overlay body says `tenant: acme`; here the row says globex and the row wins.
        admin.activate(admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice"));
        admin.activate(admin.insertDraft("globex", "widget", 1, "yaml", ACME_OVERLAY, "alice"));

        assertThat(source.load()).extracting(d -> d.tenantId()).containsExactlyInAnyOrder(null, "globex");
    }

    @Test
    void theFingerprintChangesWhenANewVersionIsActivated() {
        long v1 = admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice");
        admin.activate(v1);
        String before = source.fingerprint();

        long v2 = admin.insertDraft(null, "widget", 2, "yaml", BASE_V2, "bob");
        assertThat(source.fingerprint()).as("a draft changes nothing").isEqualTo(before);

        admin.activate(v2);
        String after = source.fingerprint();
        assertThat(after).isNotEqualTo(before);
        assertThat(source.load()).as("activating v2 retired v1").hasSize(1);

        DefinitionRegistry registry = new DefinitionRegistry(source, GuardRegistry.empty(), store);
        assertThat(registry.reload().applied()).isTrue();
        assertThat(registry.machine(null, "widget").orElseThrow().transitions()).hasSize(2);
    }

    @Test
    void rollbackIsJustActivatingThePreviousVersion() {
        long v1 = admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice");
        long v2 = admin.insertDraft(null, "widget", 2, "yaml", BASE_V2, "bob");
        admin.activate(v1);
        admin.activate(v2);
        assertThat(activeVersion()).isEqualTo(2);

        admin.activate(v1);

        assertThat(activeVersion()).isEqualTo(1);
        assertThat(source.load()).hasSize(1);
    }

    @Test
    void retiringTheOnlyActiveRowLeavesNothingToLoad() {
        long id = admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice");
        admin.activate(id);

        admin.retire(id);

        assertThat(source.load()).isEmpty();
    }

    @Test
    void theSchemaItselfRefusesASecondActiveRowForTheSameTenantAndType() {
        admin.activate(admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice"));
        long second = admin.insertDraft(null, "widget", 2, "yaml", BASE_V2, "bob");

        // Bypassing activate(), which retires the incumbent first: the unique index is the real guarantee.
        assertThatThrownBy(() -> forceStatus(second, "ACTIVE"))
                .isInstanceOf(SQLException.class);

        assertThat(source.load()).hasSize(1);
        assertThat(activeVersion()).isEqualTo(1);
    }

    @Test
    void oneActivePerTenantIsAllowedForTheSameEntityType() {
        admin.activate(admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice"));
        admin.activate(admin.insertDraft("acme", "widget", 1, "yaml", ACME_OVERLAY, "alice"));
        admin.activate(admin.insertDraft("globex", "widget", 1, "yaml", ACME_OVERLAY, "alice"));

        assertThat(source.load()).hasSize(3);
    }

    @Test
    void activatingAnIdThatDoesNotExistIsRejected() {
        assertThatThrownBy(() -> admin.activate(4242L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEngineCanBeBuiltStraightFromTheTable() {
        admin.activate(admin.insertDraft(null, "widget", 1, "yaml", BASE, "alice"));
        DefinitionRegistry registry = new DefinitionRegistry(new JdbcRuleSetSource(DS, new YamlRuleSetParser()),
                GuardRegistry.empty(), store);

        assertThat(registry.reload().applied()).isTrue();
        Optional<Machine> machine = registry.machine(null, "widget");
        assertThat(machine).isPresent();
        assertThat(machine.orElseThrow().forAction("FINISH")).isNotEmpty();
    }

    private long activeVersion() {
        return TestDatabases.scalar(DS, "select version from lifecycle_rule_set where status = 'ACTIVE' and tenant_id is null");
    }

    private void forceStatus(long id, String status) throws SQLException {
        try (Connection cn = DS.getConnection();
             PreparedStatement ps = cn.prepareStatement("update lifecycle_rule_set set status = ? where id = ?")) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }
}
