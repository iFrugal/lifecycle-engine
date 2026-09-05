package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import org.springframework.core.Ordered;

import java.util.Objects;

/**
 * One contribution to the composite {@code DefinitionSource} the starter builds (DD-05). Deliberately <em>not</em>
 * a {@code DefinitionSource} itself: a contribution must not satisfy the {@code @ConditionalOnMissingBean} that
 * lets an application replace the whole composite with a {@code DefinitionSource} bean of its own.
 *
 * <p>Register one of these from your own configuration to add a source without giving up the ones the starter
 * wires. Lower {@link #getOrder()} loads first.
 */
public final class LifecycleRuleSource implements Ordered {

    /** Files and classpath resources named by {@code lifecycle.rules.files}. */
    public static final int FILES_ORDER = 0;

    /** {@code JdbcRuleSetSource}, enabled by {@code lifecycle.rules.jdbc}. */
    public static final int JDBC_ORDER = 100;

    /** {@code MongoRuleSetSource}, enabled by {@code lifecycle.rules.mongo}. */
    public static final int MONGO_ORDER = 200;

    private final int order;
    private final DefinitionSource source;

    public LifecycleRuleSource(int order, DefinitionSource source) {
        this.order = order;
        this.source = Objects.requireNonNull(source, "source");
    }

    public DefinitionSource source() {
        return source;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public String toString() {
        return "LifecycleRuleSource[" + order + ", " + source.getClass().getSimpleName() + "]";
    }
}
