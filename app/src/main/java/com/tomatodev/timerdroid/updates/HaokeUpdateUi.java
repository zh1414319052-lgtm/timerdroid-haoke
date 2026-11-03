package com.tomatodev.timerdroid.updates;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.LanguagePolicy;

/** Own this only from the idle color screen, never while generating, exporting, selecting, or editing. */
public final class HaokeUpdateUi {
    interface Backend {
        void check(boolean manual, HaokeUpdateController.Listener listener);
        void cancel();
        void close();
        boolean isFresh(HaokeUpdateService.Result result);
        boolean present(HaokeUpdateService.Result result, boolean manual, Runnable open);
        boolean snooze();
        boolean ignore(UpdateManifestValidator.Release release);
    }
    public interface Host {
        boolean isSafeForUpdates(boolean manual);
        void onDialogShown(AlertDialog dialog);
    }
    private final Activity activity;
    private final Host host;
    private final Backend updates;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ForegroundRetryPolicy retries = new ForegroundRetryPolicy();
    private AlertDialog dialog;
    private boolean resumed;
    private long generation;

    public HaokeUpdateUi(Activity activity, Host host) {
        this(activity, host, new HaokeUpdateController(activity));
    }
    HaokeUpdateUi(Activity activity, Host host, Backend updates) {
        this.activity = activity;
        this.host = host;
        this.updates = updates;
    }
    public void resume() { resumed = true; retries.reset(); refresh(); }
    /** Call when drawers/menus/modal state changes; only the settled route is eligible. */
    public void refresh() {
        handler.removeCallbacksAndMessages(null);
        updates.cancel();
        ++generation;
        handler.postDelayed(() -> check(false), 1000);
    }
    public void pause() {
        resumed = false;
        ++generation;
        handler.removeCallbacksAndMessages(null);
        updates.cancel();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
    public void close() { pause(); updates.close(); }
    public void manual() {
        handler.removeCallbacksAndMessages(null);
        if (safe(true)) check(true);
    }
    private boolean safe(boolean manual) {
        return resumed && !activity.isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !activity.isDestroyed())
                && dialog == null && host.isSafeForUpdates(manual);
    }
    private void check(boolean manual) {
        if (!safe(manual)) return;
        final long requestGeneration = ++generation;
        updates.check(manual, result -> {
            if (requestGeneration != generation || !safe(manual)) return;
            if (result.status == HaokeUpdateService.Status.AVAILABLE) {
                // Activity resources already use the application's shared primary-language selector.
                String language = LanguagePolicy.systemLanguage().getLanguage();
                String notes = "zh".equals(language) ? result.release.notesZh : result.release.notesEn;
                AlertDialog candidate = new AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.updates_available, result.release.versionName))
                        .setMessage(notes)
                        .setPositiveButton(R.string.updates_view, (d, which) -> {
                            if (!updates.isFresh(result)) return;
                            try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(result.release.pageUrl))); }
                            catch (ActivityNotFoundException unavailable) { message(R.string.updates_browser_unavailable); }
                        })
                        .setNegativeButton(R.string.updates_later, (d, which) -> {
                            if (!updates.snooze()) message(R.string.updates_storage_failed);
                        })
                        .setNeutralButton(R.string.updates_ignore, (d, which) -> {
                            if (!updates.ignore(result.release)) message(R.string.updates_storage_failed);
                        }).create();
                candidate.setOnDismissListener(d -> { if (dialog == candidate) dialog = null; });
                if (!updates.present(result, manual, () -> {
                    if (!safe(manual)) throw new IllegalStateException("Update host no longer ready");
                    dialog = candidate;
                    try { candidate.show(); host.onDialogShown(candidate); }
                    catch (RuntimeException unavailable) {
                        candidate.dismiss(); dialog = null; throw unavailable;
                    }
                }) && manual) message(R.string.updates_storage_failed);
            } else if (manual) {
                switch (result.status) {
                    case CURRENT: case INCOMPATIBLE: message(R.string.updates_current); break;
                    case UNREGISTERED: case UNSUPPORTED: message(R.string.updates_unregistered); break;
                    case CANCELLED: break;
                    case STORAGE_FAILURE: message(R.string.updates_storage_failed); break;
                    default: message(R.string.updates_unavailable);
                }
            } else if (result.status != HaokeUpdateService.Status.CURRENT
                    && result.status != HaokeUpdateService.Status.INCOMPATIBLE
                    && result.status != HaokeUpdateService.Status.SKIPPED
                    && result.status != HaokeUpdateService.Status.CANCELLED) {
                long delay = retries.nextDelay();
                if (delay >= 0) handler.postDelayed(() -> {
                    if (generation == requestGeneration) check(false);
                }, delay);
            }
        });
    }
    private void message(int resource) {
        if (!resumed || activity.isFinishing()
                || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
        // Button callbacks run before the original dialog dismisses. Replace it safely.
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        AlertDialog notice = new AlertDialog.Builder(activity)
                .setTitle(R.string.updates_title).setMessage(resource)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog = notice;
        notice.setOnDismissListener(d -> { if (dialog == notice) dialog = null; });
        try { notice.show(); host.onDialogShown(notice); }
        catch (RuntimeException unavailable) { notice.dismiss(); dialog = null; }
    }
}
