package com.example.autoschedule.entity;

/** DB ENUM('MON'..'SUN'). ordinal() 이 api-spec 의 요일 인덱스(0=월 … 6=일)와 같다. */
public enum Weekday {
    MON, TUE, WED, THU, FRI, SAT, SUN;

    public static Weekday of(java.time.DayOfWeek d) {
        return values()[d.getValue() - 1];
    }
}