package com.example.autoschedule.dto;

import java.time.LocalTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * api-spec 시각 문자열("HH:MM") <-> LocalTime 변환. 자정을 넘긴 종료 시각은 "24:00"~"47:59"(예: 새벽 2시 = "26:00")로
 * 주고받고, DB 에는 시계 시각(02:00)으로 저장한다.
 */
public final class TimeText {

    private static final Pattern NEXT_DAY = Pattern.compile("(2[4-9]|3\\d|4[0-7]):(\\d\\d)");

    private TimeText() {}

    public static LocalTime parse(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = NEXT_DAY.matcher(text);
        if (m.matches()) return LocalTime.parse(String.format("%02d:%s", Integer.parseInt(m.group(1)) - 24, m.group(2)));
        return LocalTime.parse(text);
    }

    public static String format(LocalTime time) {
        return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    /** 종료 시각용: 시작보다 이르거나 같으면 다음 날로 보고 24시간을 더해 내려준다(00:00 -> "24:00", 02:00 -> "26:00"). */
    public static String formatEnd(LocalTime start, LocalTime end) {
        if (end == null) return null;
        int e = end.getHour() * 60 + end.getMinute();
        if (start == null) return e == 0 ? "24:00" : format(end);
        if (e <= start.getHour() * 60 + start.getMinute()) e += 24 * 60;
        return String.format("%02d:%02d", e / 60, e % 60);
    }
}
