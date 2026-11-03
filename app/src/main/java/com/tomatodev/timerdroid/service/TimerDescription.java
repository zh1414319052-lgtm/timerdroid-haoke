package com.tomatodev.timerdroid.service;

public final class TimerDescription {
    private final String name;
    private final long time;
    private final String tone;

    public TimerDescription(String name, long time) { this(name, time, ""); }

    public TimerDescription(String name, long time, String tone) {
        this.name = name;
        this.time = time;
        this.tone = tone == null ? "" : tone;
    }

    public String getName() { return name; }
    public long getTime() { return time; }
    public String getTone() { return tone; }
}
