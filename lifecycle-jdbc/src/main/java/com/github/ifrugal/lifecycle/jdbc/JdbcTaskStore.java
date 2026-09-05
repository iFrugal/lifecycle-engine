package com.github.ifrugal.lifecycle.jdbc;

import com.github.ifrugal.lifecycle.api.model.Causation;
import com.github.ifrugal.lifecycle.api.model.EntityRef;
import com.github.ifrugal.lifecycle.tasks.Task;
import com.github.ifrugal.lifecycle.tasks.TaskStore;
import com.github.ifrugal.lifecycle.json.LifecycleJson;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Tasks in {@code lifecycle_task} (DD-10). {@code create} is idempotent on the primary key and
 * {@link #transition} is a compare-and-set on {@code status}, so two claimants or two completions cannot both
 * win however many processes are racing.
 *
 * <p>{@code assign_to} and {@code payload} are JSON written with the shared {@code LifecycleJson} codec, the
 * same one the transport uses, so a task payload and an event payload are byte-for-byte comparable.
 */
public final class JdbcTaskStore implements TaskStore {

    private final DataSource dataSource;

    public JdbcTaskStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean create(Task task) {
        Objects.requireNonNull(task, "task");
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.INSERT_TASK)) {
            Causation causation = task.causation();
            ps.setString(1, task.taskId());
            ps.setString(2, task.tenantId());
            ps.setString(3, task.name());
            ps.setString(4, Jdbc.writeRoles(task.assignTo()));
            ps.setString(5, task.createdBy().type());
            ps.setString(6, task.createdBy().id());
            ps.setString(7, task.createdByEventId());
            ps.setString(8, task.onComplete().action());
            ps.setString(9, task.onComplete().target().type());
            ps.setString(10, task.onComplete().target().id());
            ps.setString(11, LifecycleJson.writeMap(task.payload()));
            ps.setString(12, task.status().name());
            ps.setString(13, task.claimedBy());
            Jdbc.setInstant(ps, 14, task.createdAt());
            Jdbc.setInstant(ps, 15, task.updatedAt());
            ps.setString(16, causation.correlationId());
            ps.setString(17, causation.causationId());
            ps.setInt(18, causation.hop());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (Jdbc.isUniqueViolation(e)) {
                return false;   // R10: creating the same task twice is a no-op, not an error
            }
            throw new JdbcStoreException("cannot create task " + task.taskId(), e);
        }
    }

    @Override
    public Optional<Task> find(String taskId) {
        Objects.requireNonNull(taskId, "taskId");
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.SELECT_TASK)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTask(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read task " + taskId, e);
        }
    }

    @Override
    public boolean transition(String taskId, Task.Status expectedStatus, Task updated) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        Objects.requireNonNull(updated, "updated");
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.UPDATE_TASK_CAS)) {
            ps.setString(1, updated.name());
            ps.setString(2, Jdbc.writeRoles(updated.assignTo()));
            ps.setString(3, updated.onComplete().action());
            ps.setString(4, updated.onComplete().target().type());
            ps.setString(5, updated.onComplete().target().id());
            ps.setString(6, LifecycleJson.writeMap(updated.payload()));
            ps.setString(7, updated.status().name());
            ps.setString(8, updated.claimedBy());
            Jdbc.setInstant(ps, 9, updated.updatedAt());
            ps.setString(10, taskId);
            ps.setString(11, expectedStatus.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            if (Jdbc.isConcurrentUpdate(e)) {
                return false;   // another claimant held the row; the caller re-reads and decides
            }
            throw new JdbcStoreException("cannot transition task " + taskId, e);
        }
    }

    /**
     * Role matching is done in Java, not SQL: {@code assign_to} is a JSON array and no portable SQL reads inside
     * one. The status/tenant/created_at index does the selective work; the intersection filter runs over open
     * tasks only and stops at {@code limit}.
     */
    @Override
    public List<Task> openFor(String tenantId, Set<String> roles, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Set<String> held = roles == null ? Set.of() : roles;
        String sql = tenantId == null ? Sql.SELECT_OPEN_TASKS : Sql.SELECT_OPEN_TASKS_FOR_TENANT;
        List<Task> out = new ArrayList<>();
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(sql)) {
            if (tenantId != null) {
                ps.setString(1, tenantId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    Task task = readTask(rs);
                    // Same rule as the reference store: an unassigned task is visible to everyone.
                    if (task.assignTo().isEmpty() || !Collections.disjoint(task.assignTo(), held)) {
                        out.add(task);
                    }
                }
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read open tasks", e);
        }
        return List.copyOf(out);
    }

    @Override
    public List<Task> byEntity(EntityRef createdBy) {
        Objects.requireNonNull(createdBy, "createdBy");
        List<Task> out = new ArrayList<>();
        try (Connection cn = dataSource.getConnection(); PreparedStatement ps = cn.prepareStatement(Sql.SELECT_TASKS_BY_ENTITY)) {
            ps.setString(1, createdBy.type());
            ps.setString(2, createdBy.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Task task = readTask(rs);
                    if (task.createdBy().equals(createdBy)) {
                        out.add(task);
                    }
                }
            }
        } catch (SQLException e) {
            throw new JdbcStoreException("cannot read the tasks of " + createdBy.key(), e);
        }
        return List.copyOf(out);
    }

    /**
     * The table stores one tenant per task, so the creating entity and the completion target are rebuilt with
     * the task's tenant. A task never crosses tenants (DD-05), so nothing is lost.
     */
    private static Task readTask(ResultSet rs) throws SQLException {
        String tenantId = rs.getString("tenant_id");
        Map<String, Object> payload = LifecycleJson.readMap(rs.getString("payload"));
        Task.OnComplete onComplete = new Task.OnComplete(
                rs.getString("on_complete_action"),
                new EntityRef(tenantId, rs.getString("target_type"), rs.getString("target_id")));
        return new Task(
                rs.getString("task_id"),
                tenantId,
                rs.getString("name"),
                Jdbc.readRoles(rs.getString("assign_to")),
                new EntityRef(tenantId, rs.getString("created_by_type"), rs.getString("created_by_id")),
                rs.getString("created_by_event_id"),
                onComplete,
                payload,
                Task.Status.valueOf(rs.getString("status")),
                rs.getString("claimed_by"),
                Jdbc.getInstant(rs, "created_at"),
                Jdbc.getInstant(rs, "updated_at"),
                new Causation(rs.getString("correlation_id"), rs.getString("causation_id"), rs.getInt("hop")));
    }
}
