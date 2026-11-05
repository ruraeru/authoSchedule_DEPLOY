package com.example.autoschedule.service;

import java.time.LocalTime;

/**
 * 시각 <-> "자정 기준 분" 변환. DB 에는 시계 시각만 저장하므로, 종료가 시작보다 이르거나 같으면 다음 날로 보고
 * 1440 을 더한다(예: 22:00~02:00 -> 1320~1560, 24:00 종료 -> 1440). TimeCodec 과 같은 규칙이다.
 */
public final class Minutes {

    public static final int DAY = 24 * 60;

    private Minutes() {}

    public static int ofStart(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }

    public static int ofEnd(LocalTime start, LocalTime end) {
        int e = ofStart(end);
        return start != null && e <= ofStart(start) ? e + DAY : e;
    }

    /** 다음 날 시각(1440 이상)은 시계 시각으로 돌려준다. */
    public static LocalTime toTime(int minutes) {
        return LocalTime.of(minutes / 60 % 24, minutes % 60);
    }

    /** 1440 이상은 "26:00" 처럼 24시를 넘겨 표기한다. */
    public static String format(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
