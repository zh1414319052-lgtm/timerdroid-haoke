package com.tomatodev.timerdroid.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.NotificationCompat;

import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.Utilities;
import com.tomatodev.timerdroid.activities.HomeActivity;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

final class CustomNotificationManager {
    static final int NOTIFICATION_ID_RUNNING = 1;
    private static final String RUNNING = "timerdroid_channel_running";
    private static final String FINISHED = "timerdroid_channel_finished";
    private final TimerService service;
    private final NotificationManager manager;
    private Set<Integer> secondary = new HashSet<>();

    CustomNotificationManager(TimerService service) {
        this.service = service;
        manager = (NotificationManager) service.getSystemService(TimerService.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(RUNNING,
                    service.getString(R.string.notification_channel_running_timers_title),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
    }

    private NotificationCompat.Builder builder(String channel) {
        Intent open = new Intent(service, HomeActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(service, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(service, channel)
                .setSmallIcon(R.drawable.ic_stat_timer).setContentIntent(content)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
    }

    private PendingIntent action(String action, int id) {
        Intent intent = new Intent(service, TimerService.class).setAction(action)
                .setData(Uri.parse("timerdroid://session/" + service.token() + "/" + id + "/" + action))
                .putExtra(TimerService.TOKEN, service.token()).putExtra(TimerService.TIMER_ID, id);
        return PendingIntent.getService(service, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    Notification running(Collection<TimerService.CountDown> timers) {
        TimerService.CountDown primary = null;
        for (TimerService.CountDown timer : timers) {
            if (timer.isFinished()) continue;
            if (primary == null || (timer.isStarted() && (!primary.isStarted()
                    || timer.getTimeLeft() < primary.getTimeLeft()))) primary = timer;
        }
        Set<Integer> current = new HashSet<>();
        for (TimerService.CountDown timer : timers) {
            if (timer == primary || timer.isFinished()) continue;
            int id = 1000 + timer.id * 2;
            current.add(id);
            manager.notify(id, session(timer));
        }
        for (int id : secondary) if (!current.contains(id)) manager.cancel(id);
        secondary = current;
        return primary == null ? builder(RUNNING).build() : session(primary);
    }

    void clearRunning() {
        for (int id : secondary) manager.cancel(id);
        secondary.clear();
    }

    private Notification session(TimerService.CountDown timer) {
        long left = timer.getTimeLeft();
        NotificationCompat.Builder notification = builder(RUNNING).setContentTitle(timer.getName())
                .setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);
        if (timer.isWaiting()) {
            notification.setContentText(service.getString(R.string.timer_waiting))
                    .addAction(0, service.getString(R.string.timer_next), action("next", timer.id));
        } else {
            notification.setContentText(service.getString(timer.isPaused() ? R.string.timer_paused_left
                    : R.string.timer_remaining, Utilities.formatTime(left)));
            if (timer.isStarted() && Build.VERSION.SDK_INT >= 24) {
                notification.setWhen(System.currentTimeMillis() + left)
                        .setShowWhen(true).setUsesChronometer(true);
            }
            notification.addAction(0, service.getString(R.string.timer_add_minute), action("add", timer.id));
        }
        Notification result = notification.addAction(0, service.getString(R.string.timer_stop),
                action("stop", timer.id)).build();
        if (timer.isStarted() && Build.VERSION.SDK_INT >= 24)
            result.extras.putBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, true);
        return result;
    }

    void finished(int id, String name, String tone, boolean waiting) {
        String channelId = tone.isEmpty() ? FINISHED : FINISHED + "_" + tone;
        Uri sound = AlarmTones.uri(service, tone);
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(service);
        if (tone.isEmpty() && Build.VERSION.SDK_INT < 26) {
            String custom = prefs.getString("ringtone", "");
            if (!custom.isEmpty()) sound = Uri.parse(custom);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(channelId,
                    service.getString(R.string.notification_channel_finished_timers_title)
                            + " · " + AlarmTones.label(service, tone), NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
        }
        NotificationCompat.Builder notification = builder(channelId).setContentTitle(name)
                .setContentText(service.getString(waiting ? R.string.timer_waiting : R.string.service_finished_label))
                .setCategory(NotificationCompat.CATEGORY_ALARM).setSound(sound).setAutoCancel(true);
        if (waiting) notification.addAction(0, service.getString(R.string.timer_next), action("next", id));
        Notification completed = notification.build();
        if (prefs.getBoolean("insistent_alarm", true)) completed.flags |= Notification.FLAG_INSISTENT;
        manager.notify(1001 + id * 2, completed);
    }

    void dismissFinished(int id) { manager.cancel(1001 + id * 2); }
}
