package com.example.autoschedule.service;

/**
 * 법정 휴게시간 (근로기준법 제54조): 근로시간 4시간 이상 30분, 8시간 이상 1시간.
 * "근로시간" 은 휴게를 뺀 시간이므로 api-spec 8장대로 후보 0/30/60분 중
 * {@code breakMinutes >= requiredBreak(총시간 - breakMinutes)} 를 만족하는 최소값을 쓴다.
 * (예: 8시간 체류 = 7.5시간 근로 + 30분 휴게, 8.5시간 체류 = 7.5시간 근로 + 60분 휴게)
 */
public final class BreakCalculator {

    private static final int[] CANDIDATES = {0, 30, 60};

    private BreakCalculator() {}

    public static int requiredBreak(int workMinutes) {
        if (workMinutes >= 8 * 60) return 60;
        if (workMinutes >= 4 * 60) return 30;
        return 0;
    }

    /** 출근~퇴근 체류시간(분) 기준 법정 최소 휴게(분). */
    public static int legalMinimum(int spanMinutes) {
        for (int b : CANDIDATES) {
            if (b >= requiredBreak(spanMinutes - b)) return b;
        }
        return 60;
    }

    /** 법정 최소, 수동 지정값, 파트에 설정된 휴게 중 가장 큰 값. 수동 지정이 법정 미만이면 법정으로 올린다. */
    public static int resolve(int spanMinutes, int manualMinutes, Integer partMinutes) {
        int result = Math.max(legalMinimum(spanMinutes), manualMinutes);
        if (partMinutes != null) result = Math.max(result, partMinutes);
        return result;
    }

    /**
     * 휴게 시작 시각(분). 휴게가 0 이면 null.
     * 허용 범위는 출근+30분 ~ 퇴근-30분-휴게. 희망값이 범위 안이면 그대로, 아니면 근무 중간을 30분 단위로 내림 스냅.
     */
    public static Integer placeBreakStart(int startMin, int endMin, int breakMinutes, Integer preferred) {
        if (breakMinutes <= 0) return null;
        int lo = startMin + 30;
        int hi = Math.max(lo, endMin - 30 - breakMinutes);
        if (preferred != null && preferred >= lo && preferred <= hi) return preferred;
        int ideal = startMin + (endMin - startMin - breakMinutes) / 2;
        int snapped = (ideal / 30) * 30;
        return Math.min(hi, Math.max(lo, snapped));
    }
}