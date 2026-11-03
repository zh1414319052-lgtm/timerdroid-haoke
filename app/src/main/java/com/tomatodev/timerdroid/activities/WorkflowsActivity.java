package com.tomatodev.timerdroid.activities;

import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.support.v7.widget.SwitchCompat;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.Utilities;
import com.tomatodev.timerdroid.persistence.TimerLibrary;
import com.tomatodev.timerdroid.persistence.TimerLibrary.Workflow;
import com.tomatodev.timerdroid.persistence.TimersProvider.TimerTable;
import com.tomatodev.timerdroid.service.TimerDescription;
import com.tomatodev.timerdroid.service.TimerService;

import java.util.ArrayList;
import java.util.List;

public class WorkflowsActivity extends LocalizedActivity {
    private TimerLibrary library;
    private TimerService service;
    private boolean bound;
    private ListView list;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TimerService.LocalBinder) binder).getService();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle(R.string.workflows);
        library = new TimerLibrary(this);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(16), 0, dp(16), dp(16));
        setContentView(screen);
        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle(R.string.workflows);
        toolbar.setNavigationIcon(android.support.v7.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationContentDescription(R.string.home_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        screen.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        Button create = new Button(this);
        create.setText(R.string.workflow_new);
        workflowButton(create, true);
        create.setOnClickListener(view -> edit(new Workflow(-1, "", false, new ArrayList<>())));
        screen.addView(create);
        list = new ListView(this);
        screen.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        refresh();
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, TimerService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (bound) unbindService(connection);
        service = null;
        bound = false;
        super.onStop();
    }

    private void refresh() {
        try {
            List<Workflow> flows = library.workflows();
            List<String> labels = new ArrayList<>();
            for (Workflow flow : flows) labels.add(flow.name);
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
            list.setOnItemClickListener((parent, view, position, id) -> actions(flows.get(position)));
        } catch (Exception e) { error(e); }
    }

    private void actions(Workflow flow) {
        new AlertDialog.Builder(this).setTitle(flow.name).setItems(new String[]{
                getString(R.string.workflow_start), getString(R.string.list_timers_action_edit),
                getString(R.string.workflow_delete)}, (dialog, choice) -> {
            if (choice == 0) {
                if (service == null) {
                    Toast.makeText(this, R.string.create_timer_service_pending, Toast.LENGTH_SHORT).show();
                    return;
                }
                service.startWorkflow(flow.steps, flow.confirmNext);
                startActivity(new Intent(this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            } else if (choice == 1) {
                edit(flow);
            } else {
                new AlertDialog.Builder(this).setMessage(R.string.workflow_delete)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (confirm, button) -> {
                            try { library.delete(flow.id); refresh(); } catch (Exception e) { error(e); }
                        }).show();
            }
        }).show();
    }

    private void edit(Workflow flow) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, 0, pad, 0);
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setHint(R.string.workflow_name);
        name.setText(flow.name);
        form.addView(name);
        SwitchCompat confirm = new SwitchCompat(this);
        confirm.setMinHeight(dp(48));
        confirm.setText(R.string.workflow_confirm_next);
        confirm.setChecked(flow.confirmNext);
        List<TimerDescription> steps = new ArrayList<>(flow.steps);
        ListView stepList = new ListView(this);
        form.addView(stepList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override public android.view.View getView(int position, android.view.View recycled, ViewGroup parent) {
                LinearLayout row = new LinearLayout(WorkflowsActivity.this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(48));
                row.setPadding(dp(10), 0, 0, 0);
                row.setBackgroundResource(R.drawable.timer_tile);
                android.widget.TextView label = new android.widget.TextView(WorkflowsActivity.this);
                label.setText(getItem(position));
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                android.widget.TextView grip = new android.widget.TextView(WorkflowsActivity.this);
                grip.setText("↕");
                grip.setTextSize(22);
                grip.setGravity(android.view.Gravity.CENTER);
                row.addView(grip, new LinearLayout.LayoutParams(dp(58), -1));
                return row;
            }
        };
        stepList.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        stepList.setDividerHeight(dp(6));
        stepList.setAdapter(adapter);
        Runnable render = () -> {
            adapter.clear();
            for (int i = 0; i < steps.size(); i++) {
                TimerDescription step = steps.get(i);
                adapter.add((i + 1) + ". " + step.getName() + "  "
                        + Utilities.formatTime(step.getTime()));
            }
        };
        render.run();
        int[] dragged = {ListView.INVALID_POSITION};
        stepList.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (event.getX() < stepList.getWidth() - dp(58)) return false;
                dragged[0] = stepList.pointToPosition((int) event.getX(), (int) event.getY());
                return dragged[0] != ListView.INVALID_POSITION;
            }
            if (dragged[0] == ListView.INVALID_POSITION) return false;
            if (action == MotionEvent.ACTION_MOVE) {
                int target = stepList.pointToPosition((int) event.getX(), (int) event.getY());
                if (target != ListView.INVALID_POSITION && target != dragged[0]) {
                    steps.add(target, steps.remove(dragged[0]));
                    dragged[0] = target;
                    render.run();
                } else if (target == ListView.INVALID_POSITION && android.os.Build.VERSION.SDK_INT >= 19) {
                    stepList.scrollListBy(event.getY() < 0 ? -dp(16) : dp(16));
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragged[0] = ListView.INVALID_POSITION;
            }
            return true;
        });
        stepList.setOnItemClickListener((parent, row, index, id) ->
                new AlertDialog.Builder(this).setTitle(steps.get(index).getName()).setItems(new String[]{
                        getString(R.string.workflow_up), getString(R.string.workflow_down),
                        getString(R.string.workflow_remove_step)}, (dialog, choice) -> {
                    if (choice == 0 && index > 0) steps.add(index - 1, steps.remove(index));
                    if (choice == 1 && index + 1 < steps.size()) steps.add(index + 1, steps.remove(index));
                    if (choice == 2) steps.remove(index);
                    render.run();
                }).show());
        Button add = new Button(this);
        add.setText(R.string.workflow_add_step);
        workflowButton(add, false);
        add.setOnClickListener(view -> {
            if (steps.size() < TimerLibrary.MAX_STEPS) choosePreset(steps, render);
        });
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-1, -2);
        addParams.topMargin = dp(8);
        form.addView(add, addParams);
        form.addView(confirm);
        AlertDialog editor = new AlertDialog.Builder(this).setTitle(R.string.workflow_edit)
                .setView(form).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null).create();
        editor.show();
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String title = name.getText().toString().trim();
            if (title.isEmpty() || steps.isEmpty()) {
                Toast.makeText(this, R.string.workflow_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                library.save(new Workflow(flow.id, title, confirm.isChecked(), steps));
                editor.dismiss();
                refresh();
            } catch (Exception e) { error(e); }
        });
    }

    private void choosePreset(List<TimerDescription> steps, Runnable render) {
        List<TimerDescription> presets = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        try (Cursor cursor = getContentResolver().query(TimerTable.CONTENT_URI,
                new String[]{"name", "time", "tone"}, null, null, "name COLLATE NOCASE")) {
            if (cursor != null) while (cursor.moveToNext()) {
                long duration = cursor.getLong(1);
                if (duration <= 0 || duration > TimerService.MAX_DURATION) continue;
                presets.add(new TimerDescription(cursor.getString(0), duration, cursor.getString(2)));
                labels.add(cursor.getString(0) + "  " + Utilities.formatTime(duration));
            }
        }
        if (presets.isEmpty()) {
            Toast.makeText(this, R.string.workflow_no_presets, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.workflow_add_step)
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    steps.add(presets.get(index));
                    render.run();
                }).show();
    }

    private void workflowButton(Button button, boolean primary) {
        button.setBackgroundResource(primary ? R.drawable.timer_primary : R.drawable.timer_secondary);
        button.setTextColor(android.support.v4.content.ContextCompat.getColor(this,
                primary ? R.color.timer_on_accent : R.color.text_color_primary));
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void error(Exception error) {
        android.util.Log.e("TimerDroid", "Workflow operation failed", error);
        Toast.makeText(this, R.string.workflow_error, Toast.LENGTH_LONG).show();
    }
}
