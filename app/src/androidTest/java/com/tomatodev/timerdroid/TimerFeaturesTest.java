package com.tomatodev.timerdroid;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;

import com.tomatodev.timerdroid.activities.HomeActivity;
import com.tomatodev.timerdroid.persistence.TimerLibrary;
import com.tomatodev.timerdroid.service.TimerDescription;
import com.tomatodev.timerdroid.service.TimerService;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TimerFeaturesTest {
    @Rule public ActivityTestRule<HomeActivity> activity =
            new ActivityTestRule<>(HomeActivity.class, false, false);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = InstrumentationRegistry.getTargetContext();

    @Test public void pausedTimerAndConfirmedWorkflowRemainIndependent() throws Exception {
        activity.launchActivity(new Intent());
        TimerService[] holder = new TimerService[1];
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                holder[0] = ((TimerService.LocalBinder) binder).getService();
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        assertTrue(context.bindService(new Intent(context, TimerService.class), connection, Context.BIND_AUTO_CREATE));
        boolean screenOff = "true".equals(InstrumentationRegistry.getArguments().getString("lock_screen"));
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            TimerService service = holder[0];
            int[] ids = new int[2];
            long[] paused = new long[1];
            instrumentation.runOnMainSync(() -> {
                ids[0] = service.startTimer("QA pause", 30000);
                ids[1] = service.startWorkflow(Arrays.asList(
                        new TimerDescription("QA first", screenOff ? 2500 : 300, "bell"),
                        new TimerDescription("QA next", 30000, "chime")), true);
                service.pauseTimer(ids[0]);
                paused[0] = service.getTimers().get(ids[0]).getTimeLeft();
                service.addMinute(ids[0]);
            });
            if (screenOff) {
                shell("input keyevent KEYCODE_SLEEP");
                android.os.PowerManager power = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                long limit = SystemClock.elapsedRealtime() + 1800;
                while (power.isInteractive() && SystemClock.elapsedRealtime() < limit) SystemClock.sleep(40);
                assertFalse("The device must be non-interactive before expiry", power.isInteractive());
                android.app.NotificationManager manager = (android.app.NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                // The waiting session also uses this title. Inspect the alarm channel explicitly.
                long deadline = SystemClock.elapsedRealtime() + 5000;
                android.app.NotificationChannel channel = null;
                while (channel == null && SystemClock.elapsedRealtime() < deadline) {
                    for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                        android.app.Notification notification = item.getNotification();
                        if ("QA first".contentEquals(notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE, ""))
                                && android.app.Notification.CATEGORY_ALARM.equals(notification.category)) {
                            channel = manager.getNotificationChannel(notification.getChannelId());
                            break;
                        }
                    }
                    if (channel == null) SystemClock.sleep(50);
                }
                assertNotNull("Expiry must post an alarm while the screen is off", channel);
                assertTrue(channel.getId().endsWith("_bell"));
                assertFalse("The screen should still be off at alarm delivery", power.isInteractive());
            } else {
                SystemClock.sleep(800);
            }
            instrumentation.runOnMainSync(() -> {
                TimerService.CountDown first = service.getTimers().get(ids[0]);
                TimerService.CountDown flow = service.getTimers().get(ids[1]);
                assertTrue(first.isPaused());
                assertEquals(paused[0] + 60000, first.getTimeLeft());
                assertTrue(flow.isWaiting());
                service.nextStep(ids[1]);
                assertEquals("QA next", flow.getName());
                assertTrue(flow.isStarted());
                service.resumeTimer(ids[0]);
                service.deleteTimer(ids[1]);
                assertTrue(first.isStarted());
                assertFalse(service.getTimers().containsKey(ids[1]));
                service.deleteTimer(ids[0]);
            });
        } finally {
            if (screenOff) shell("input keyevent KEYCODE_WAKEUP");
            context.unbindService(connection);
        }
    }

    @Test public void notificationActionsUpdateAndStopTheCorrectTimer() throws Exception {
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 24);
        android.app.NotificationManager manager = (android.app.NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        org.junit.Assume.assumeTrue(manager.areNotificationsEnabled());
        activity.launchActivity(new Intent());
        TimerService[] holder = new TimerService[1];
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                holder[0] = ((TimerService.LocalBinder) binder).getService();
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        assertTrue(context.bindService(new Intent(context, TimerService.class), connection, Context.BIND_AUTO_CREATE));
        int[] ids = {-1, -1};
        try {
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> {
                ids[0] = holder[0].startTimer("QA notification", 30000);
                ids[1] = holder[0].startTimer("QA independent", 120000);
            });
            android.app.Notification original = awaitNotification(manager, "QA notification");
            assertTrue(original.extras.getBoolean(android.app.Notification.EXTRA_CHRONOMETER_COUNT_DOWN));
            assertEquals(android.app.Notification.VISIBILITY_PUBLIC, original.visibility);
            assertEquals(2, original.actions.length);
            // Give the OS notification rate limiter time between setup and the simulated tap.
            SystemClock.sleep(1500);
            original.actions[0].actionIntent.send();
            long until = SystemClock.elapsedRealtime() + 5000;
            boolean extended = false;
            while (SystemClock.elapsedRealtime() < until) {
                android.app.Notification current = awaitNotification(manager, "QA notification");
                if (current.when > original.when + 55000) { extended = true; break; }
                SystemClock.sleep(50);
            }
            assertTrue("Add minute should update the notification deadline", extended);
            awaitNotification(manager, "QA notification").actions[1].actionIntent.send();
            until = SystemClock.elapsedRealtime() + 5000;
            boolean[] removed = new boolean[1];
            while (SystemClock.elapsedRealtime() < until && !removed[0]) {
                instrumentation.runOnMainSync(() -> removed[0] = !holder[0].getTimers().containsKey(ids[0]));
                if (!removed[0]) SystemClock.sleep(50);
            }
            assertTrue("Stop should remove the selected timer", removed[0]);
            awaitNotification(manager, "QA independent");
        } finally {
            if (holder[0] != null) instrumentation.runOnMainSync(() -> {
                for (int id : ids) if (id >= 0) holder[0].deleteTimer(id);
            });
            context.unbindService(connection);
        }
    }

    private void shell(String command) throws Exception {
        try (android.os.ParcelFileDescriptor.AutoCloseInputStream stream =
                     new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                             instrumentation.getUiAutomation().executeShellCommand(command))) {
            while (stream.read() != -1) { /* Wait for the shell command to complete. */ }
        }
    }

    private android.app.Notification awaitNotification(android.app.NotificationManager manager, String title) {
        long until = SystemClock.elapsedRealtime() + 5000;
        while (SystemClock.elapsedRealtime() < until) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                android.app.Notification notification = item.getNotification();
                if (title.contentEquals(notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE, "")))
                    return notification;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError("Missing notification " + title);
    }

    @Test public void backupRoundTripsWorkflowAndRejectsBrokenImportAtomically() throws Exception {
        TimerLibrary library = new TimerLibrary(context);
        String title = "QA backup " + System.nanoTime();
        library.save(new TimerLibrary.Workflow(-1, title, true, Arrays.asList(
                new TimerDescription("Second", 55000, "chime"),
                new TimerDescription("First", 15000, "bell"))));
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            library.exportTo(bytes);
            for (TimerLibrary.Workflow flow : library.workflows()) if (title.equals(flow.name)) library.delete(flow.id);
            library.importFrom(new ByteArrayInputStream(bytes.toByteArray()));
            library.importFrom(new ByteArrayInputStream(bytes.toByteArray()));
            ByteArrayOutputStream merged = new ByteArrayOutputStream();
            library.exportTo(merged);
            JSONObject beforeMerge = new JSONObject(bytes.toString("UTF-8"));
            JSONObject afterMerge = new JSONObject(merged.toString("UTF-8"));
            for (String table : new String[]{"categories", "timers", "workflows"}) {
                assertEquals(table, beforeMerge.getJSONArray(table).length(), afterMerge.getJSONArray(table).length());
            }
            int matches = 0;
            for (TimerLibrary.Workflow flow : library.workflows()) if (title.equals(flow.name)) {
                matches++;
                assertTrue(flow.confirmNext);
                assertEquals("Second", flow.steps.get(0).getName());
                assertEquals("bell", flow.steps.get(1).getTone());
            }
            assertEquals(1, matches);
            Charset utf8 = Charset.forName("UTF-8");
            JSONObject invalid = new JSONObject(bytes.toString("UTF-8"));
            invalid.getJSONArray("categories").getJSONObject(0).put("name", title);
            invalid.getJSONArray("timers").getJSONObject(0).put("time", -1);
            try {
                library.importFrom(new ByteArrayInputStream(invalid.toString().getBytes(utf8)));
                fail("Invalid duration must reject the import");
            } catch (org.json.JSONException expected) {
                ByteArrayOutputStream after = new ByteArrayOutputStream();
                library.exportTo(after);
                org.json.JSONArray rows = new JSONObject(after.toString("UTF-8")).getJSONArray("categories");
                for (int i = 0; i < rows.length(); i++) assertNotEquals(title, rows.getJSONObject(i).getString("name"));
            }
        } finally {
            for (TimerLibrary.Workflow flow : library.workflows()) if (title.equals(flow.name)) library.delete(flow.id);
        }
    }
}
