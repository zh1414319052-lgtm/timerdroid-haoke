package com.tomatodev.timerdroid.fragments;

import android.support.v7.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.support.v7.widget.AppCompatButton;
import android.support.v7.widget.AppCompatTextView;
import com.tomatodev.timerdroid.activities.WorkflowsActivity;
import android.widget.TextView;
import android.widget.Toast;

import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;

import com.tomatodev.timerdroid.MyApplication;
import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.TimerCursorAdapter;
import com.tomatodev.timerdroid.Utilities;
import com.tomatodev.timerdroid.persistence.TimersProvider.TimerTable;
import com.tomatodev.timerdroid.service.ITimerUpdatedHandler;
import com.tomatodev.timerdroid.service.TimerService;
import com.tomatodev.timerdroid.service.TimerService.CountDown;

import java.util.LinkedHashMap;
import java.util.Map;

public class RunningTimersFragment extends Fragment
        implements LoaderManager.LoaderCallbacks<Cursor>, ITimerUpdatedHandler {
    private TimerService service;
    private boolean bound;
    private TimerCursorAdapter favorites;
    private final Map<CountDown, TextView> clocks = new LinkedHashMap<>();
    private final Map<CountDown, ProgressBar> progress = new LinkedHashMap<>();

    public static RunningTimersFragment favoritesPage() {
        RunningTimersFragment page = new RunningTimersFragment();
        Bundle args = new Bundle();
        args.putBoolean("favorites", true);
        page.setArguments(args);
        return page;
    }

    public boolean canShowUpdate() {
        return service != null && service.getTimers().isEmpty();
    }

    private boolean favoritesOnly() {
        return getArguments() != null && getArguments().getBoolean("favorites");
    }

    private final Handler handler = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            for (Map.Entry<CountDown, TextView> entry : clocks.entrySet()) {
                String time = timeText(entry.getKey());
                if (!time.contentEquals(entry.getValue().getText())) entry.getValue().setText(time);
                progress.get(entry.getKey()).setProgress(remainingPercent(entry.getKey()));
            }
            if (isResumed()) handler.postDelayed(this, 250);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TimerService.LocalBinder) binder).getService();
            favorites.setLocalBinder((TimerService.LocalBinder) binder);
            service.registerListener(RunningTimersFragment.this);
            refreshRunningTimersList();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            favorites.setLocalBinder(null);
            refreshRunningTimersList();
        }
    };

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) {
        return inflater.inflate(R.layout.running_timers, parent, false);
    }

    @Override public void onViewCreated(View view, Bundle state) {
        super.onViewCreated(view, state);
        MyApplication.mainVisible = true;
        favorites = new TimerCursorAdapter(getActivity(), null, getFragmentManager(), null);
        ((ListView) view.findViewById(R.id.main_list_favorites)).setAdapter(favorites);
        if (favoritesOnly()) {
            view.findViewById(R.id.running_scroll).setVisibility(View.GONE);
            ListView list = view.findViewById(R.id.main_list_favorites);
            list.setVisibility(View.VISIBLE);
            list.setEmptyView(view.findViewById(R.id.favorites_empty));
            getLoaderManager().restartLoader(0, null, this);
        } else {
            Button workflows = view.findViewById(R.id.open_workflows);
            styleButton(workflows, R.drawable.timer_tile);
            workflows.setOnClickListener(v -> startActivity(new Intent(getActivity(), WorkflowsActivity.class)));
        }
        renderQuick();
    }

    @Override public void onResume() {
        super.onResume();
        renderQuick();
        bound = getActivity().bindService(new Intent(getActivity(), TimerService.class), connection,
                Context.BIND_AUTO_CREATE);
        handler.post(tick);
    }

    @Override public void onPause() {
        handler.removeCallbacks(tick);
        if (service != null) service.deregisterListener(this);
        if (bound) getActivity().unbindService(connection);
        bound = false;
        service = null;
        favorites.setLocalBinder(null);
        super.onPause();
    }

    private int quick(int slot) {
        return PreferenceManager.getDefaultSharedPreferences(getActivity())
                .getInt("quick_minutes_" + slot, new int[]{1, 5, 10}[slot]);
    }

    private void renderQuick() {
        if (getView() == null || favoritesOnly()) return;
        LinearLayout row = getView().findViewById(R.id.quick_timers);
        row.removeAllViews();
        for (int i = 0; i < 3; i++) {
            final int slot = i;
            int minutes = quick(i);
            String label = getString(R.string.timer_minutes_short, minutes);
            Button start = button(row, label, () -> {
                if (service != null) service.startTimer(label, minutes * 60000L);
            });
            styleButton(start, R.drawable.timer_tile);
            start.setTextSize(16);
            start.setMinimumHeight(dp(58));
            start.setOnLongClickListener(v -> { editQuick(slot); return true; });
        }
        Button edit = getView().findViewById(R.id.quick_edit);
        edit.setOnClickListener(v -> {
            String[] labels = new String[3];
            for (int i = 0; i < 3; i++) labels[i] = getString(R.string.timer_minutes_short, quick(i));
            new AlertDialog.Builder(getActivity()).setTitle(R.string.quick_edit)
                    .setItems(labels, (dialog, slot) -> editQuick(slot)).show();
        });
        edit.setContentDescription(getString(R.string.quick_edit));
        Button notifications = getView().findViewById(R.id.enable_notifications);
        boolean enabled = android.support.v4.app.NotificationManagerCompat.from(getActivity()).areNotificationsEnabled();
        notifications.setVisibility(enabled ? View.GONE : View.VISIBLE);
        notifications.setOnClickListener(v -> {
            Intent settings;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                settings = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getActivity().getPackageName());
            } else {
                settings = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getActivity().getPackageName()));
            }
            try { startActivity(settings); } catch (android.content.ActivityNotFoundException ignored) {
                Toast.makeText(getActivity(), R.string.notification_enable, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void editQuick(int slot) {
        android.widget.EditText minutes = new android.widget.EditText(getActivity());
        minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minutes.setText(Integer.toString(quick(slot)));
        AlertDialog dialog = new AlertDialog.Builder(getActivity()).setTitle(R.string.quick_minutes)
                .setView(minutes).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(minutes.getText().toString());
                if (value < 1 || value > 1440) throw new NumberFormatException();
                PreferenceManager.getDefaultSharedPreferences(getActivity()).edit()
                        .putInt("quick_minutes_" + slot, value).apply();
                renderQuick();
                dialog.dismiss();
            } catch (NumberFormatException e) { minutes.setError(getString(R.string.quick_invalid)); }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(int id) {
        return android.support.v4.content.ContextCompat.getColor(getActivity(), id);
    }

    private void styleButton(Button view, int background) {
        view.setAllCaps(false);
        if (view instanceof AppCompatButton) ((AppCompatButton) view).setSupportBackgroundTintList(null);
        view.setBackgroundResource(background);
        view.setTextColor(color(background == R.drawable.timer_primary
                ? R.color.timer_on_accent : R.color.text_color_primary));
        view.setPadding(dp(5), dp(4), dp(5), dp(4));
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
    }

    private Button button(LinearLayout row, String label, Runnable action) {
        AppCompatButton view = new AppCompatButton(getActivity());
        styleButton(view, R.drawable.timer_secondary);
        view.setText(label);
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) params.setMarginStart(dp(8));
        row.addView(view, params);
        return view;
    }

    private String timeText(CountDown timer) {
        return Utilities.formatTimeNoBlanks(timer.getTimeLeft());
    }

    private int remainingPercent(CountDown timer) {
        return (int) Math.min(100, Math.ceil(timer.getTimeLeft() * 100.0 / timer.getDuration()));
    }

    public void refreshRunningTimersList() {
        if (getView() == null || favoritesOnly()) return;
        LinearLayout table = getView().findViewById(R.id.main_timers_table);
        table.removeAllViews();
        clocks.clear();
        progress.clear();
        boolean empty = service == null || service.getTimers().isEmpty();
        getView().findViewById(R.id.main_textview_running).setVisibility(empty ? View.VISIBLE : View.GONE);
        if (service == null) return;
        for (CountDown timer : service.getTimers().values()) {
            LinearLayout panel = new LinearLayout(getActivity());
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundResource(R.drawable.timer_card);
            panel.setPadding(dp(16), dp(14), dp(16), dp(14));
            LinearLayout heading = new LinearLayout(getActivity());
            heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView title = new TextView(getActivity());
            title.setText(timer.getName());
            title.setTextSize(19);
            title.setTextColor(color(R.color.text_color_primary));
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            TextView status = new TextView(getActivity());
            status.setText(timer.isWaiting() ? R.string.timer_waiting
                    : timer.isFinished() ? R.string.service_finished_label
                    : timer.isPaused() ? R.string.status_paused : R.string.status_running);
            status.setTextColor(color(timer.isStarted() ? R.color.colorAccent : R.color.text_color_secondary));
            status.setPadding(dp(8), 0, 0, 0);
            heading.addView(status);
            panel.addView(heading);
            if (timer.isStarted() || timer.isPaused()) {
                TextView clock = new AppCompatTextView(getActivity());
                clock.setText(timeText(timer));
                clock.setSingleLine();
                clock.setTextColor(color(R.color.text_color_primary));
                clock.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
                if (android.os.Build.VERSION.SDK_INT >= 21) clock.setFontFeatureSettings("tnum");
                android.support.v4.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        clock, 21, 46, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
                panel.addView(clock, new LinearLayout.LayoutParams(-1, dp(65)));
                clocks.put(timer, clock);

                ProgressBar bar = new ProgressBar(getActivity(), null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(100);
                bar.setProgress(remainingPercent(timer));
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    bar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                            color(timer.isPaused() ? R.color.text_color_secondary : R.color.colorAccent)));
                }
                panel.addView(bar, new LinearLayout.LayoutParams(-1, dp(5)));
                progress.put(timer, bar);
            }
            LinearLayout controls = new LinearLayout(getActivity());
            LinearLayout.LayoutParams controlLayout = new LinearLayout.LayoutParams(-1, -2);
            controlLayout.topMargin = dp(12);
            panel.addView(controls, controlLayout);
            if (timer.isWaiting()) {
                styleButton(button(controls, getString(R.string.timer_next),
                        () -> service.nextStep(timer.id)), R.drawable.timer_primary);
            } else if (!timer.isFinished()) {
                styleButton(button(controls, getString(timer.isPaused() ? R.string.timer_resume : R.string.timer_pause),
                        () -> { if (timer.isPaused()) service.resumeTimer(timer.id); else service.pauseTimer(timer.id); }),
                        R.drawable.timer_primary);
                button(controls, getString(R.string.timer_add_minute), () -> service.addMinute(timer.id));
            }
            button(controls, getString(R.string.timer_stop), () -> service.deleteTimer(timer.id));
            LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
            card.bottomMargin = dp(12);
            table.addView(panel, card);
        }
    }

    @Override public void onTimersChanged() { refreshRunningTimersList(); }

    @Override public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        return new CursorLoader(getActivity(), TimerTable.CONTENT_URI, null,
                TimerTable.TIMER_KEY_FAVORITE + "=1", null, null);
    }
    @Override public void onLoadFinished(Loader<Cursor> loader, Cursor cursor) { favorites.swapCursor(cursor); }
    @Override public void onLoaderReset(Loader<Cursor> loader) { favorites.swapCursor(null); }
}
