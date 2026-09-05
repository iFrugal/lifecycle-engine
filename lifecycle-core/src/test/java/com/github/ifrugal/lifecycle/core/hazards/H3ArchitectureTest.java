package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.api.spi.AuditQuery;
import com.github.ifrugal.lifecycle.api.spi.Commit;
import com.github.ifrugal.lifecycle.api.spi.CommitResult;
import com.github.ifrugal.lifecycle.api.spi.Outbox;
import com.github.ifrugal.lifecycle.api.spi.StateStore;
import com.github.ifrugal.lifecycle.api.spi.Transport;
import com.github.ifrugal.lifecycle.core.engine.DefaultLifecycleEngine;
import com.github.ifrugal.lifecycle.core.engine.Dispatcher;
import com.github.ifrugal.lifecycle.core.engine.TransitionResolver;
import com.github.ifrugal.lifecycle.core.registry.DefinitionRegistry;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * H3, structural half: the decide path cannot reach commit or publish, because the types that could are not on
 * its classpath-visible dependency graph at all. A dry run is not a flag someone can forget to honour.
 */
class H3ArchitectureTest {

    private static final String ROOT = "com.github.ifrugal.lifecycle";
    private static final String SPI = "..api.spi..";

    private static JavaClasses classes;

    @BeforeAll
    static void importProduction() {
        // Jars are included on purpose: lifecycle-api arrives as one, and its packages are part of the rule.
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        assertThat(classes).as("production classes were found on the classpath").isNotEmpty();
        assertThat(classes.contain(TransitionResolver.class)).isTrue();
        assertThat(classes.contain(StateStore.class)).as("lifecycle-api classes were imported too").isTrue();
    }

    /**
     * Positive control: the same predicates that must find nothing above do find the classes that legitimately
     * depend on the seam. Without this, a typo in a package pattern would make every rule pass vacuously.
     */
    @Test
    void theDependencyPredicatesActuallyDetectDependencies() {
        classes().that().haveFullyQualifiedName(DefaultLifecycleEngine.class.getName())
                .should().dependOnClassesThat().belongToAnyOf(StateStore.class)
                .andShould().dependOnClassesThat().belongToAnyOf(Transport.class)
                .because("the engine is the one class that commits and publishes; if this fails the rules above prove nothing")
                .check(classes);

        classes().that().haveFullyQualifiedName(Dispatcher.class.getName())
                .should().dependOnClassesThat().belongToAnyOf(Transport.class)
                .because("the dispatcher subscribes to the transport")
                .check(classes);

        classes().that().haveFullyQualifiedName(DefinitionRegistry.class.getName())
                .should().dependOnClassesThat().resideInAPackage(SPI)
                .because("the registry legitimately reads the definition source and the store; the package pattern must match it")
                .check(classes);
    }

    @Test
    void theRuleModelNeverSeesTheStorageOrTransportSeam() {
        noClasses().that().resideInAPackage("..core.rules..")
                .should().dependOnClassesThat().resideInAPackage(SPI)
                .because("the compiled rule model is pure data; nothing in it may reach a store or a transport (H3)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void theResolverNeverSeesTheStorageOrTransportSeam() {
        noClasses().that().haveFullyQualifiedName(TransitionResolver.class.getName())
                .should().dependOnClassesThat().resideInAPackage(SPI)
                .because("decide() is a pure function of (state, event, definition); commit and publish are absent from its call path (H3, DD-07)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheEngineTouchesTheStore() {
        noClasses().that().resideInAPackage("..core.engine..")
                .and().doNotHaveFullyQualifiedName(DefaultLifecycleEngine.class.getName())
                .should().dependOnClassesThat().belongToAnyOf(StateStore.class, Commit.class, CommitResult.class, Outbox.class, AuditQuery.class)
                .because("step 3 of DD-07 belongs to the engine alone; the resolver and the dispatcher must not be able to write")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyTheEngineAndTheDispatcherTouchTheTransport() {
        noClasses().that().resideInAPackage("..core.engine..")
                .and().doNotHaveFullyQualifiedName(DefaultLifecycleEngine.class.getName())
                .and().doNotHaveFullyQualifiedName(Dispatcher.class.getName())
                .should().dependOnClassesThat().belongToAnyOf(Transport.class)
                .because("the engine publishes (step 4) and the dispatcher subscribes; nothing else in core.engine may (H3)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void theRuleModelNeverSeesTheEngineOrTheReferenceImplementations() {
        noClasses().that().resideInAPackage("..core.rules..")
                .should().dependOnClassesThat().resideInAnyPackage("..core.engine..", "..core.inmemory..", "..core.registry..")
                .because("rules are compiled data; they must not know who evaluates or stores them (H3, H8)")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void theApiModelNeverDependsOnTheCore() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..core..")
                .because("the api module is the contract; the core implements it, never the other way round")
                .allowEmptyShould(true)
                .check(classes);
    }
}
