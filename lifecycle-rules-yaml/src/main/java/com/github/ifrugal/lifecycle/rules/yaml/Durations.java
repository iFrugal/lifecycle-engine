package com.github.ifrugal.lifecycle.rules.yaml;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the {@code after} duration text used by DD-04: either a plain ISO-8601 duration ({@code PT72H}) or a
 * compact "shorthand" made of one or more {@code <number><unit>} tokens run together, e.g. {@code 72h},
 * {@code 30m}, {@code 1h30m}. Supported units: {@code d} (days), {@code h} (hours), {@code m} (minutes),
 * {@code s} (seconds), {@code ms} (milliseconds).
 */
public final class Durations {

    // "ms" must be tried before "m" so "1500ms" is not read as "1500m" + trailing "s".
    private static final Pattern TOKEN = Pattern.compile("(\\d+)(d|h|ms|m|s)");

    private Durations() {}

    public static Duration parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("duration text must not be blank");
        }
        String trimmed = text.trim();
        if (trimmed.length() > 1 && (trimmed.charAt(0) == 'P' || trimmed.charAt(0) == 'p')) {
            try {
                return Duration.parse(trimmed.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("invalid ISO-8601 duration: '" + text + "'", e);
            }
        }
        return parseShorthand(trimmed, text);
    }

    private static Duration parseShorthand(String trimmed, String original) {
        Matcher matcher = TOKEN.matcher(trimmed);
        Duration total = Duration.ZERO;
        int consumedUpTo = 0;
        boolean matchedAny = false;
        while (matcher.find()) {
            if (matcher.start() != consumedUpTo) {
                throw new IllegalArgumentException("invalid duration: '" + original + "'");
            }
            long value = Long.parseLong(matcher.group(1));
            total = total.plus(unit(matcher.group(2), value, original));
            consumedUpTo = matcher.end();
            matchedAny = true;
        }
        if (!matchedAny || consumedUpTo != trimmed.length()) {
            throw new IllegalArgumentException("invalid duration: '" + original + "'");
        }
        return total;
    }

    private static Duration unit(String unit, long value, String original) {
        return switch (unit) {
            case "d" -> Duration.ofDays(value);
            case "h" -> Duration.ofHours(value);
            case "m" -> Duration.ofMinutes(value);
            case "s" -> Duration.ofSeconds(value);
            case "ms" -> Duration.ofMillis(value);
            default -> throw new IllegalArgumentException("invalid duration: '" + original + "'");
        };
    }
}
