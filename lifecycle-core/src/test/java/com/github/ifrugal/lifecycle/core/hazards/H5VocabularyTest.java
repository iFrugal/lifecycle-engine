package com.github.ifrugal.lifecycle.core.hazards;

import com.github.ifrugal.lifecycle.core.rules.RuleCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H5: no business vocabulary in the engine. The core and the api are scanned for string literals that look like
 * a business state or action name (SCREAMING_SNAKE, optionally dotted). The allowlist is empty: the only action
 * vocabulary the engine may own is the reserved {@code lifecycle.} prefix, and the only keywords are {@code *}
 * and {@code self}.
 */
class H5VocabularyTest {

    /** Java string literal, honouring backslash escapes. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Looks like a business state or action name: PAID, REQUEST_REFUND, ACTIVE.SUSPENDED, ACTIVE.* */
    private static final Pattern BUSINESS_NAME = Pattern.compile("^[A-Z][A-Z0-9_]+(\\.[A-Z0-9_*]+)*$");

    /** Deliberately empty: the engine owns no business names at all (DD-12). */
    private static final Set<String> ALLOWED = Set.of();

    /** Surefire runs with the module directory as the working directory. */
    private static final List<Path> SOURCE_ROOTS = List.of(
            Paths.get("src/main/java"),
            Paths.get("../lifecycle-api/src/main/java"));

    private record Literal(Path file, int line, String value) {
        @Override
        public String toString() {
            return file + ":" + line + "  \"" + value + "\"";
        }
    }

    private static List<Path> javaFiles() {
        List<Path> files = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            assertThat(Files.isDirectory(root))
                    .as("source root %s must exist (working directory is %s)", root.toAbsolutePath(), Paths.get("").toAbsolutePath())
                    .isTrue();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static List<Literal> literals() {
        List<Literal> out = new ArrayList<>();
        for (Path file : javaFiles()) {
            List<String> lines;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = STRING_LITERAL.matcher(lines.get(i));
                while (m.find()) {
                    out.add(new Literal(file, i + 1, m.group(1)));
                }
            }
        }
        return out;
    }

    /** The scanner must be able to fail: a guard against a silently vacuous H5. */
    @Test
    void theDetectorRecognisesBusinessNamesAndIgnoresEngineVocabulary() {
        for (String name : List.of("PAID", "NEW", "REQUEST_REFUND", "ACTIVE.SUSPENDED", "ACTIVE.*", "AUTHORISED", "ON_HOLD")) {
            assertThat(BUSINESS_NAME.matcher(name).matches()).as("'%s' looks like a business name", name).isTrue();
        }
        for (String ok : List.of("lifecycle.", "lifecycle.task.create", "lifecycle-engine", "*", ".*", "$", "$payload",
                "$entity.id", "exists", "self", "value", "yaml", "id", "", "A", "no transition from state ")) {
            assertThat(BUSINESS_NAME.matcher(ok).matches()).as("'%s' is engine vocabulary, not a business name", ok).isFalse();
        }

        // The literal extractor sees escapes and multiple literals on one line.
        Matcher m = STRING_LITERAL.matcher("String a = \"PAID\"; String b = \"say \\\"NEW\\\"\"; char c = '\"';");
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).contains("PAID");
        assertThat(found).anyMatch(s -> s.contains("NEW"));
    }

    @Test
    void bothSourceRootsAreActuallyScanned() {
        List<Path> files = javaFiles();
        assertThat(files).as("java files under %s", SOURCE_ROOTS).hasSizeGreaterThan(30);
        assertThat(files).anyMatch(p -> p.toString().contains("TransitionResolver"));
        assertThat(files).anyMatch(p -> p.toString().contains("LifecycleEvent"));
        assertThat(literals()).as("string literals found").hasSizeGreaterThan(50);
    }

    @Test
    void noBusinessStateOrActionNamesInCoreOrApiSources() {
        List<Literal> offenders = literals().stream()
                .filter(l -> BUSINESS_NAME.matcher(l.value()).matches())
                .filter(l -> !ALLOWED.contains(l.value()))
                .toList();

        if (!offenders.isEmpty()) {
            System.out.println("H5: business-looking literals in engine sources:");
            offenders.forEach(o -> System.out.println("  " + o));
        }

        assertThat(offenders)
                .as("the engine must own no business state or action names (H5); allowlist is %s", ALLOWED)
                .isEmpty();
    }

    @Test
    void theOnlyReservedActionVocabularyIsLifecycleTaskCreate() {
        Set<String> reserved = new TreeSet<>();
        List<Literal> offenders = new ArrayList<>();
        for (Literal l : literals()) {
            if (!l.value().startsWith(RuleCompiler.RESERVED_PREFIX)) {
                continue;
            }
            reserved.add(l.value());
            // The bare prefix constant itself, and the one action built from it, are the whole vocabulary.
            if (!l.value().equals(RuleCompiler.RESERVED_PREFIX) && !l.value().equals(RuleCompiler.TASK_CREATE_ACTION)) {
                offenders.add(l);
            }
        }

        if (!offenders.isEmpty()) {
            System.out.println("H5: unexpected reserved-prefix literals in engine sources:");
            offenders.forEach(o -> System.out.println("  " + o));
        }

        assertThat(offenders).as("only lifecycle.task.create may exist under the reserved prefix (DD-10, H5)").isEmpty();
        assertThat(reserved).as("literals under the reserved prefix").isNotEmpty();
        assertThat(RuleCompiler.RESERVED_PREFIX).isEqualTo("lifecycle.");
        assertThat(RuleCompiler.TASK_CREATE_ACTION).isEqualTo("lifecycle.task.create");
    }

    @Test
    void theKeywordsAreExactlyStarAndSelf() {
        // `*` is the wildcard state pattern; `self` is a field of TargetDocument, not a magic string.
        assertThat(com.github.ifrugal.lifecycle.core.rules.StatePattern.WILDCARD).isEqualTo("*");
        assertThat(com.github.ifrugal.lifecycle.core.rules.StatePattern.PREFIX_SUFFIX).isEqualTo(".*");
        assertThat(com.github.ifrugal.lifecycle.api.rules.TargetDocument.toSelf().self()).isTrue();
        assertThat(com.github.ifrugal.lifecycle.api.rules.TargetDocument.toSelf().type()).isNull();
    }
}
