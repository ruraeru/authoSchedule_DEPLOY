package com.example.autoschedule.service;

import com.example.autoschedule.service.LaborCostCalculator.PayBlock;
import com.example.autoschedule.service.LaborCostCalculator.Result;
import com.example.autoschedule.service.LaborCostCalculator.WorkerInfo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LaborCostCalculatorTest {

    static final YearMonth NOV = YearMonth.of(2026, 11);
    static final LocalDate MON = LocalDate.of(2026, 11, 2);
    static final WorkerInfo PART_TIMER = new WorkerInfo(1, "a", false, 10_000);

    static PayBlock block(LocalDate date, int start, int end, int brk) {
        return new PayBlock(1, date, start, end, brk, null, false);
    }

    static Result calc(boolean premiums, List<PayBlock> blocks) {
        return LaborCostCalculator.calculate(NOV, premiums, List.of(PART_TIMER), blocks);
    }

    @Test
    void basePayExcludesBreak() {
        Result r = calc(false, List.of(block(MON, 540, 1080, 60)));
        assertThat(r.basePay()).isEqualTo(80_000);
        assertThat(r.weeklyHolidayPay()).isZero();
        assertThat(r.holidayPayTargetCount()).isZero();
    }

    @Test
    void weeklyHolidayPayFrom15Hours() {
        List<PayBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 5; i++) blocks.add(block(MON.plusDays(i), 540, 780, 0)); // 4h x 5 = 20h
        Result r = calc(false, blocks);
        assertThat(r.basePay()).isEqualTo(200_000);
        assertThat(r.weeklyHolidayPay()).isEqualTo(40_000); // 10000 * 20/40 * 8
        assertThat(r.holidayPayTargetCount()).isEqualTo(1);
    }

    @Test
    void premiumsOnlyWhenApplied() {
        assertThat(calc(true, List.of(block(MON, 1200, 1440, 0))).nightPremium()).isEqualTo(10_000); // 2h night
        assertThat(calc(true, List.of(block(MON, 540, 1140, 0))).overtimePremium()).isEqualTo(10_000); // 2h over 8h
        assertThat(calc(false, List.of(block(MON, 540, 1140, 0))).overtimePremium()).isZero();
    }

    @Test
    void overnightNightPremiumCountsAfterMidnight() {
        // 21:00~27:00 = 6h, night 22:00~27:00 = 5h
        assertThat(calc(true, List.of(block(MON, 1260, 1620, 0))).nightPremium()).isEqualTo(25_000);
    }
}
