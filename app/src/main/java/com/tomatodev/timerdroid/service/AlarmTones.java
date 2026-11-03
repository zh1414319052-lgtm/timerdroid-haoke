package com.tomatodev.timerdroid.service;

import android.content.Context;
import android.media.RingtoneManager;
import android.net.Uri;

import com.tomatodev.timerdroid.R;

public final class AlarmTones {
    private static final String[] KEYS = {"", "bell", "chime", "pulse"};
    private static final int[] LABELS = {R.string.tone_default, R.string.tone_bell,
            R.string.tone_chime, R.string.tone_pulse};
    private static final int[] SOUNDS = {0, R.raw.timer_bell, R.raw.timer_chime, R.raw.timer_pulse};

    private AlarmTones() {}

    public static int index(String key) {
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    public static String key(int index) { return KEYS[index]; }
    public static String normalize(String key) { return KEYS[index(key)]; }
    public static String label(Context context, String key) { return context.getString(LABELS[index(key)]); }
    public static String[] labels(Context context) {
        String[] result = new String[KEYS.length];
        for (int i = 0; i < result.length; i++) result[i] = context.getString(LABELS[i]);
        return result;
    }
    public static Uri uri(Context context, String key) {
        int sound = SOUNDS[index(key)];
        return sound == 0 ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                : Uri.parse("android.resource://" + context.getPackageName() + "/raw/" + context.getResources().getResourceEntryName(sound));
    }
}
