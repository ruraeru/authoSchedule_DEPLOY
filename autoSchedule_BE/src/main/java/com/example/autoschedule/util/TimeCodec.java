package com.example.autoschedule.util;

import com.example.autoschedule.exception.ApiException;

import java.time.LocalTime;

/**
 * 프론트는 24시를 넘기는 시각을 "26:00"처럼 보낸다(새벽 2시 = 26:00). DB(TIME)에는 시계 시각(02:00)으로 저장하고,
 * 종료 시각이 시작 시각보다 이르거나 같으면 다음 날로 보고 읽을 때 24시간을 더해 돌려준다. 같으면 정확히 24시간이다.
 * 시작 시각은 00:00~23:59 만 받는다(자정 이후에 시작하는 구간은 아직 지원하지 않는다).
 */
public final class TimeCodec {

    private static final int DAY = 24 * 60;

    private TimeCodec() {}

    public static LocalTime start(String hhmm) {
        int minutes = minutes(hhmm);
        if (minutes >= DAY) {
            throw ApiException.badRequest("시작 시각은 00:00~23:59 사이여야 합니다.");
        }
        return LocalTime.ofSecondOfDay(minutes * 60L);
    }

    /** 종료는 시작보다 늦고 24시간 이내여야 한다. 예: 18:00 시작이면 02:00 대신 26:00 으로 보낸다. */
    public static LocalTime end(LocalTime start, String hhmm) {
        int minutes = minutes(hhmm);
        int span = minutes - start.toSecondOfDay() / 60;
        if (span <= 0 || span > DAY) {
            throw ApiException.badRequest("종료 시각은 시작 시각보다 늦고 24시간 이내여야 합니다.");
        }
        return LocalTime.ofSecondOfDay((minutes % DAY) * 60L);
    }

    public static String formatStart(LocalTime start) {
        return format(start.toSecondOfDay() / 60);
    }

    public static String formatEnd(LocalTime start, LocalTime end) {
        int s = start.toSecondOfDay() / 60;
        int e = end.toSecondOfDay() / 60;
        return format(e <= s ? e + DAY : e);
    }

    private static int minutes(String hhmm) {
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private static String format(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }
}
