package com.nudge.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReadStatusTest {

    @ParameterizedTest
    @CsvSource({
        "0, 0, false, Not Opened",
        "0, 0, true,  Unconfirmed",
        "1, 0, true,  Opened",
        "0, 1, false, Opened",
        "2, 0, false, Opened Multiple Times"
    })
    void statusReflectsTheStrongestSignal(long opens, long clicks, boolean apple, String expected) {
        assertThat(ReadStatus.of(opens, clicks, apple)).isEqualTo(expected);
    }
}
