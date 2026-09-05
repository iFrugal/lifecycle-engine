package com.github.ifrugal.lifecycle.rules.yaml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurationsTest {

    @ParameterizedTest
    @CsvSource({
            "72h,    PT72H",
            "30m,    PT30M",
            "10s,    PT10S",
            "2d,     PT48H",
            "1500ms, PT1.5S",
            "1h30m,  PT1H30M",
            "PT72H,  PT72H",
            "pt10s,  PT10S",
    })
    void parsesEveryAcceptedFormat(String text, String expectedIso) {
        assertThat(Durations.parse(text)).isEqualTo(Duration.parse(expectedIso));
    }

    @Test
    void rejectsMalformedText() {
        assertThatThrownBy(() -> Durations.parse("72"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Durations.parse("72x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Durations.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Durations.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Durations.parse("h72"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
