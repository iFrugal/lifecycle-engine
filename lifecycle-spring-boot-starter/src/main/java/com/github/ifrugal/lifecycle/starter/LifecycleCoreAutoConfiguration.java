package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.LifecycleEngine;
import com.github.ifrugal.lifecycle.api.guard.GuardPredicate;
import com.github.ifrugal.lifecycle.api.model.Actor;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.api.spi.RuleSetParser;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.engine.EngineConfig;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryStateStore;
import com.github.ifrugal.lifecycle.core.inmemory.InMemoryTransport;
import com.github.ifrugal.lifecycle.core.registry.CompositeDefinitionSource;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.github.ifrugal.lifecycle.core.rules.GuardRegistry;
import com.github.ifrugal.lifecycle.rules.yaml.YamlRuleSetParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The always-on half of the starter (DD-13): guards, the composite rule source, the registry and its reloader,
 * the engine, the dispatcher, and the {@code memory} defaults for the store and the transport.
 *
 * <p>Beans are declared in the DD-13 wiring order. Every one is {@code @ConditionalOnMissingBean}, so an
 * application replaces any single piece by declaring its own bean of the same type; the adapter
 * auto-configurations do exactly that, running {@code before} this class so their store or transport wins.
 *
 * <p>Store and transport bean methods deliberately return the concrete type rather than the SPI: an
 * {@code InMemoryStateStore} is also an {@code AuditQuery} and an {@code Outbox}, and returning the concrete
 * type is what lets those be injected without a second bean holding the same instance (DD-13, bean 3).
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "lifecycle.enabled", matchIfMissing = true)
@EnableConfigurationProperties(LifecycleProperties.class)
public class LifecycleCoreAutoConfiguration {

    /** Bean 1: named guards, discovered as beans (DD-13 rejects SPI discovery). */
    @Bean
    @ConditionalOnMissingBean
    public GuardRegistry lifecycleGuardRegistry(ObjectProvider<GuardPredicate> guards) {
        return GuardRegistry.of(guards.orderedStream().toList());
    }

    @Bean
    @ConditionalOnMissingBean
    public RuleSetParser lifecycleRuleSetParser() {
        return new YamlRuleSetParser();
    }

    /** The {@code lifecycle.rules.files} half of the composite; adapters contribute their own sources. */
    @Bean
    public LifecycleRuleSource lifecycleFileRuleSource(LifecycleProperties properties, RuleSetParser parser) {
        List<DefinitionSource> sources = RuleFileSources.build(
                properties.getRules().getFiles(), getClass().getClassLoader(), parser);
        return new LifecycleRuleSource(LifecycleRuleSource.FILES_ORDER, new CompositeDefinitionSource(sources));
    }

    /**
     * Bean 2: every contributed source, in order. Wrapped so the snapshot version stays 64 characters however
     * many sources are configured — see {@link HashedFingerprintSource} for why that matters.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefinitionSource lifecycleDefinitionSource(ObjectProvider<LifecycleRuleSource> contributions) {
        return new HashedFingerprintSource(new CompositeDefinitionSource(
                contributions.orderedStream().map(LifecycleRuleSource::source).toList()));
    }

    /** Bean 3, {@code memory} default. Also the {@code AuditQuery} and the {@code Outbox}. */
    @Bean
    @ConditionalOnMissingBean(StateStore.class)
    public InMemoryStateStore lifecycleStateStore() {
        return new InMemoryStateStore();
    }

    /** Bean 4. The snapshot is loaded by {@link LifecycleRuleReloader}, not here. */
    @Bean
    @ConditionalOnMissingBean
    public DefinitionRegistry lifecycleDefinitionRegistry(DefinitionSource source, GuardRegistry guards, StateStore store) {
        return new DefinitionRegistry(source, guards, store);
    }

    /** Bean 5, {@code memory} default. Honours {@code deliverAt}, so rule timers work out of the box. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(Transport.class)
    public InMemoryTransport lifecycleTransport() {
        return new InMemoryTransport();
    }

    @Bean
    @ConditionalOnMissingBean
    public EngineConfig lifecycleEngineConfig(LifecycleProperties properties) {
        String actorId = properties.getEngine().getActorId();
        return new EngineConfig(
                new Actor(actorId, Set.of(actorId), Actor.Kind.ENGINE),
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString());
    }

    /** Bean 6. */
    @Bean
    @ConditionalOnMissingBean
    public LifecycleEngine lifecycleEngine(DefinitionRegistry registry, StateStore store, Transport transport,
                                           GuardRegistry guards, EngineConfig config) {
        return new DefaultLifecycleEngine(registry, store, transport, guards, config);
    }

    /** Bean 7: emitted cross-entity signals come back through here. */
    @Bean
    @ConditionalOnMissingBean
    public Dispatcher lifecycleDispatcher(LifecycleEngine engine, Transport transport, LifecycleProperties properties) {
        return new Dispatcher(engine, properties.getDispatcher().getConflictRetries()).attachTo(transport);
    }

    @Bean
    @ConditionalOnMissingBean
    public LifecycleRuleReloader lifecycleRuleReloader(DefinitionRegistry registry, DefinitionSource source,
                                                       Transport transport, LifecycleProperties properties) {
        return new LifecycleRuleReloader(registry, source, transport,
                properties.getRules().getReload().getPoll(),
                properties.getRules().getReload().isFailFast());
    }
}
