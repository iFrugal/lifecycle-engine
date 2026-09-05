package com.github.ifrugal.lifecycle.starter;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import com.github.ifrugal.lifecycle.rules.yaml.ClasspathDefinitionSource;
import com.github.ifrugal.lifecycle.rules.yaml.FileDefinitionSource;
import com.github.ifrugal.lifecycle.rules.yaml.YamlRuleSetParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the {@code lifecycle.rules.files} strings mean, including the classpath-directory concession. */
class RuleFileSourcesTest {

    private static final ClassLoader LOADER = RuleFileSourcesTest.class.getClassLoader();

    private static List<DefinitionSource> build(String... locations) {
        return RuleFileSources.build(List.of(locations), LOADER, new YamlRuleSetParser());
    }

    @Test
    void named_classpath_resources_become_one_classpath_source() {
        List<DefinitionSource> sources = build("classpath:rules/order.yaml", "classpath:rules/shipment.yaml");

        assertThat(sources).singleElement().isInstanceOf(ClasspathDefinitionSource.class);
        assertThat(sources.getFirst().load()).extracting(RuleSetDocument::entityType)
                .containsExactlyInAnyOrder("order", "shipment");
    }

    @Test
    void a_classpath_directory_on_an_exploded_classpath_is_walked() {
        List<DefinitionSource> sources = build("classpath:rules/");

        assertThat(sources).singleElement().isInstanceOf(FileDefinitionSource.class);
        assertThat(sources.getFirst().load()).extracting(RuleSetDocument::entityType)
                .containsExactlyInAnyOrder("order", "shipment");
    }

    @Test
    void a_missing_classpath_directory_says_so_rather_than_loading_nothing() {
        assertThatThrownBy(() -> build("classpath:no-such-directory/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-directory/");
    }

    @Test
    void a_bare_path_is_treated_as_a_file_location() {
        List<DefinitionSource> sources = build("src/test/resources/rules/order.yaml", "file:src/test/resources/rules");

        assertThat(sources).hasSize(2).allMatch(FileDefinitionSource.class::isInstance);
        assertThat(sources.getFirst().load()).extracting(RuleSetDocument::entityType).containsExactly("order");
        assertThat(sources.get(1).load()).extracting(RuleSetDocument::entityType)
                .containsExactlyInAnyOrder("order", "shipment");
    }

    @Test
    void blank_entries_are_ignored() {
        assertThat(build("", "   ")).isEmpty();
    }
}
