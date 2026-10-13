package com.bimd.fiscalpipeline.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IsoDatesTest {

    @Test
    void should_keepThreeMillisecondDigits_when_instantHasNoFraction() {
        assertThat(IsoDates.format(Instant.parse("2026-10-13T13:20:00Z"))).isEqualTo("2026-10-13T13:20:00.000Z");
    }

    @Test
    void should_truncateToMilliseconds_when_instantHasNanoseconds() {
        assertThat(IsoDates.format(Instant.parse("2026-10-13T13:20:00.123456789Z")))
                .isEqualTo("2026-10-13T13:20:00.123Z");
    }

    @Test
    void should_roundTrip_when_parsingAFormattedDate() {
        Instant instant = Instant.parse("2026-10-13T13:20:00.250Z");

        assertThat(IsoDates.parse(IsoDates.format(instant))).isEqualTo(instant);
    }

    @Test
    void should_measureElapsedMillis_between_storedDateAndInstant() {
        assertThat(IsoDates.millisBetween("2026-10-13T13:20:00.000Z", Instant.parse("2026-10-13T13:20:03.500Z")))
                .isEqualTo(3500);
    }
}
