package com.tomatodev.timerdroid.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import com.tomatodev.timerdroid.LanguagePolicy;
import com.tomatodev.timerdroid.R;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-thread owner of all running sessions. Presets are copied into a session on start. */
public class TimerService extends Service {
    public static final long MAX_DURATION = 36003599000L;
    public static final String START_WIDGET = "start_widget";
    static final String TOKEN = "session_token";
    static final String TIMER_ID = "timer_id";
    private final String token = UUID.randomUUID().toString();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, CountDown> timers = new LinkedHashMap<>();
    private final List<ITimerUpdatedHandler> listeners = new ArrayList<>();
    private int nextId;
    private boolean foreground;
    private PowerManager.WakeLock wakeLock;
    private CustomNotificationManager notifications;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguagePolicy.wrap(base));
    }

    @Override public void onCreate() {
        super.onCreate();
        notifications = new CustomNotificationManager(this);
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        LanguagePolicy.refresh(getResources());
        refresh();
    }

    public class LocalBinder extends Binder {
        public TimerService getService() { return TimerService.this; }
    }

    private final LocalBinder binder = new LocalBinder();
    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && START_WIDGET.equals(intent.getAction())) {
            start(new TimerDescription(intent.getStringExtra("timerName"),
                    intent.getLongExtra("timerTime", 0), intent.getStringExtra("tone")), null, false, false);
        } else if (intent != null && token.equals(intent.getStringExtra(TOKEN))) {
            int id = intent.getIntExtra(TIMER_ID, -1);
            String action = intent.getAction();
            if ("add".equals(action)) addMinute(id);
            if ("stop".equals(action)) deleteTimer(id);
            if ("next".equals(action)) nextStep(id);
        }
        if (!hasSession()) stopSelf(startId);
        return START_NOT_STICKY;
    }

    String token() { return token; }

    public void registerListener(ITimerUpdatedHandler listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void deregisterListener(ITimerUpdatedHandler listener) { listeners.remove(listener); }

    public Integer startTimer(String name, long duration) {
        return startTimer(name, duration, null);
    }

    public Integer startTimer(String name, long duration, List<TimerDescription> queue) {
        return start(new TimerDescription(name, duration), queue, false);
    }

    public Integer startPreset(String name, long duration, String tone) {
        return start(new TimerDescription(name, duration, tone), null, false);
    }

    public Integer startWorkflow(List<TimerDescription> steps, boolean confirmNext) {
        if (steps.isEmpty()) return -1;
        return start(steps.get(0), steps.subList(1, steps.size()), confirmNext);
    }

    private Integer start(TimerDescription first, List<TimerDescription> queue, boolean confirmNext) {
        return start(first, queue, confirmNext, true);
    }

    private Integer start(TimerDescription first, List<TimerDescription> queue,
                          boolean confirmNext, boolean keepStarted) {
        if (first.getTime() <= 0 || first.getTime() > MAX_DURATION) return -1;
        // Keep the active session alive after the last activity unbinds.
        if (keepStarted && !hasSession()) startService(new Intent(this, TimerService.class));
        int id = nextId++;
        CountDown timer = new CountDown(id, queue, confirmNext);
        timers.put(id, timer);
        timer.begin(first);
        refresh();
        return id;
    }

    public void deleteTimer(Integer id) {
        CountDown timer = timers.remove(id);
        if (timer == null) return;
        handler.removeCallbacks(timer.expire);
        notifications.dismissFinished(id);
        refresh();
    }

    public void pauseTimer(Integer id) {
        CountDown timer = timers.get(id);
        if (timer == null || !timer.isStarted()) return;
        long remaining = timer.getTimeLeft();
        if (remaining == 0) {
            timer.finish();
            return;
        }
        timer.remaining = remaining;
        timer.state = State.PAUSED;
        handler.removeCallbacks(timer.expire);
        refresh();
    }

    public void resumeTimer(Integer id) {
        CountDown timer = timers.get(id);
        if (timer == null || !timer.isPaused()) return;
        timer.schedule(timer.remaining);
        refresh();
    }

    public void addMinute(Integer id) {
        CountDown timer = timers.get(id);
        if (timer == null || (!timer.isStarted() && !timer.isPaused())) return;
        long remaining = timer.getTimeLeft();
        if (timer.isStarted() && remaining == 0) {
            timer.finish();
            return;
        }
        long extended = Math.min(MAX_DURATION, remaining + 60000L);
        timer.duration += extended - remaining;
        if (timer.isPaused()) timer.remaining = extended;
        else timer.schedule(extended);
        refresh();
    }

    public void nextStep(int id) {
        CountDown timer = timers.get(id);
        if (timer == null || !timer.isWaiting()) return;
        notifications.dismissFinished(id);
        timer.begin(timer.queue.removeFirst());
        refresh();
    }

    public Map<Integer, CountDown> getTimers() { return Collections.unmodifiableMap(timers); }

    private boolean hasSession() {
        for (CountDown timer : timers.values()) {
            if (!timer.isFinished()) return true;
        }
        return false;
    }

    private void refresh() {
        boolean running = false;
        for (CountDown timer : timers.values()) running |= timer.isStarted();
        if (running && wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            boolean keepScreen = PreferenceManager.getDefaultSharedPreferences(this)
                    .getBoolean("screen_unlocked", false);
            wakeLock = power.newWakeLock(keepScreen ? PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    : PowerManager.PARTIAL_WAKE_LOCK, "TimerDroid:Countdown");
            wakeLock.acquire();
        } else if (!running) {
            releaseWakeLock();
        }

        if (hasSession()) {
            startForeground(CustomNotificationManager.NOTIFICATION_ID_RUNNING,
                    notifications.running(timers.values()));
            foreground = true;
        } else if (foreground) {
            notifications.clearRunning();
            stopForeground(true);
            stopSelf();
            foreground = false;
        }
        for (ITimerUpdatedHandler listener : new ArrayList<>(listeners)) listener.onTimersChanged();
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock = null;
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseWakeLock();
        super.onDestroy();
    }

    private enum State { RUNNING, PAUSED, WAITING, FINISHED }

    public final class CountDown {
        public final int id;
        private final ArrayDeque<TimerDescription> queue = new ArrayDeque<>();
        private final boolean confirmNext;
        private final Runnable expire = this::finish;
        private State state;
        private long deadline;
        private long remaining;
        private long duration;
        private String name;
        private String tone;

        CountDown(int id, List<TimerDescription> steps, boolean confirmNext) {
            this.id = id;
            this.confirmNext = confirmNext;
            if (steps != null) for (TimerDescription step : steps) {
                if (step.getTime() > 0 && step.getTime() <= MAX_DURATION)
                    queue.add(new TimerDescription(step.getName(), step.getTime(), step.getTone()));
            }
        }

        void begin(TimerDescription step) {
            name = step.getName() == null || step.getName().isEmpty()
                    ? getString(R.string.cr_timer_custom) : step.getName();
            tone = step.getTone();
            duration = step.getTime();
            schedule(duration);
        }

        void schedule(long duration) {
            handler.removeCallbacks(expire);
            state = State.RUNNING;
            deadline = SystemClock.elapsedRealtime() + duration;
            handler.postDelayed(expire, duration);
        }

        void finish() {
            if (!isStarted()) return;
            long left = getTimeLeft();
            if (left > 0) {
                handler.postDelayed(expire, left);
                return;
            }
            String completedName = name;
            String completedTone = tone;
            if (queue.isEmpty()) state = State.FINISHED;
            else if (confirmNext) state = State.WAITING;
            else begin(queue.removeFirst());
            notifications.finished(id, completedName, completedTone, isWaiting());
            refresh();
        }

        public String getName() { return name; }
        public boolean isStarted() { return state == State.RUNNING; }
        public boolean isPaused() { return state == State.PAUSED; }
        public boolean isWaiting() { return state == State.WAITING; }
        public boolean isFinished() { return state == State.FINISHED; }
        public long getDuration() { return duration; }
        public long getTimeLeft() {
            if (isStarted()) return Math.max(0, deadline - SystemClock.elapsedRealtime());
            return isPaused() ? remaining : 0;
        }
    }
}
