package com.example.autoschedule.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BreakCalculatorTest {

    @Test
    void legalMinimumFollowsWorkHoursAfterBreak() {
        assertThat(BreakCalculator.legalMinimum(239)).isZero();
        assertThat(BreakCalculator.legalMinimum(240)).isEqualTo(30);
        assertThat(BreakCalculator.legalMinimum(480)).isEqualTo(30); // 7.5h work + 30m
        assertThat(BreakCalculator.legalMinimum(510)).isEqualTo(60); // 8h work would need 60
    }

    @Test
    void resolveTakesLargestOfLegalManualAndPart() {
        assertThat(BreakCalculator.resolve(480, 0, 45)).isEqualTo(45);
        assertThat(BreakCalculator.resolve(480, 10, null)).isEqualTo(30);
        assertThat(BreakCalculator.resolve(480, 90, 45)).isEqualTo(90);
    }

    @Test
    void placeBreakStartUsesPreferredInRangeElseMiddle() {
        assertThat(BreakCalculator.placeBreakStart(540, 1080, 60, null)).isEqualTo(780);
        assertThat(BreakCalculator.placeBreakStart(540, 1080, 60, 600)).isEqualTo(600);
        assertThat(BreakCalculator.placeBreakStart(540, 1080, 60, 1000)).isEqualTo(780);
        assertThat(BreakCalculator.placeBreakStart(540, 1080, 0, null)).isNull();
    }
}
