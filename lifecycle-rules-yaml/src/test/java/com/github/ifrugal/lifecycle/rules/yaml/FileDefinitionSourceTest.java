package com.github.ifrugal.lifecycle.rules.yaml;

import com.github.ifrugal.lifecycle.api.rules.RuleSetDocument;
import com.github.ifrugal.lifecycle.api.spi.DefinitionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileDefinitionSourceTest {

    private static final List<String> RESOURCE_NAMES = List.of("rules/order.yaml", "rules/shipment.yaml", "rules/tenants/acme/order.yaml");

    @TempDir
    Path tempDir;

    private Path orderYaml;

    @BeforeEach
    void copyFixturesIntoTempDir() throws IOException {
        for (String resource : RESOURCE_NAMES) {
            Path target = tempDir.resolve(resource);
            Files.createDirectories(target.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        orderYaml = tempDir.resolve("rules/order.yaml");
    }

    @Test
    void loadsAllThreeFilesRecursively() {
        DefinitionSource source = FileDefinitionSource.of(tempDir);
        Collection<RuleSetDocument> docs = source.load();

        assertThat(docs).hasSize(3);
        assertThat(docs).anySatisfy(d -> assertThat(d.entityType()).isEqualTo("shipment"));
        assertThat(docs).filteredOn(d -> "acme".equals(d.tenantId())).hasSize(1);
        assertThat(docs).filteredOn(d -> d.tenantId() == null && "order".equals(d.entityType())).hasSize(1);
    }

    @Test
    void fingerprintIsStableAndChangesOnEdit() throws IOException {
        DefinitionSource source = FileDefinitionSource.of(tempDir);

        String first = source.fingerprint();
        String second = source.fingerprint();
        assertThat(second).isEqualTo(first);

        Files.writeString(orderYaml, Files.readString(orderYaml) + "\n# a harmless comment\n");

        String third = source.fingerprint();
        assertThat(third).isNotEqualTo(first);
    }

    @Test
    void classpathSourceLoadsSameThreeFilesByResourceName() {
        ClasspathDefinitionSource source = new ClasspathDefinitionSource(RESOURCE_NAMES, Thread.currentThread().getContextClassLoader());
        Collection<RuleSetDocument> docs = source.load();

        assertThat(docs).hasSize(3);
        assertThat(docs).filteredOn(d -> "acme".equals(d.tenantId())).hasSize(1);
        assertThat(source.fingerprint()).isEqualTo(source.fingerprint());
    }
}
