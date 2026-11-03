package com.tomatodev.timerdroid.fragments;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.SimpleCursorAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import com.tomatodev.timerdroid.MyApplication;
import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.Utilities;
import com.tomatodev.timerdroid.activities.HomeActivity;
import com.tomatodev.timerdroid.persistence.CategoriesProvider;
import com.tomatodev.timerdroid.persistence.CategoriesProvider.CategoriesTable;
import com.tomatodev.timerdroid.persistence.DbAdapter;
import com.tomatodev.timerdroid.persistence.TimersProvider;
import com.tomatodev.timerdroid.persistence.TimersProvider.TimerTable;
import com.tomatodev.timerdroid.service.TimerService;
import com.tomatodev.timerdroid.service.TimerService.LocalBinder;
import com.tomatodev.timerdroid.shortcuts.TimerShortcutManager;

public class TimerFragment extends Fragment {

	private NumberPicker pickerHrs;
	private NumberPicker pickerMins;
	private NumberPicker pickerSecs;

	private EditText timerNameEditText;

	private LocalBinder localBinder;
	private Spinner categorySpinner;
	private CheckBox favoriteCheckBox;
    private android.widget.Button toneButton;
    private int toneIndex;
    private ServiceConnection timerConnection;
    private boolean timerBound;
    private int mTimerId;

    @Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
	}

	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);

        mTimerId = savedInstanceState != null ? savedInstanceState.getInt("savedTimerId", -1)
                : getActivity().getIntent().getIntExtra("timerId", -1);

		pickerHrs = getActivity().findViewById(R.id.timer_picker_hrs);
		pickerMins = getActivity().findViewById(R.id.timer_picker_mins);
		pickerSecs = getActivity().findViewById(R.id.timer_picker_secs);

		pickerHrs.setMinValue(0);
		pickerHrs.setMaxValue(1000);
		pickerHrs.setValue(0);
		pickerHrs.setOnLongPressUpdateInterval(200);

		pickerMins.setMinValue(0);
		pickerMins.setMaxValue(59);
		pickerMins.setValue(0);
		pickerMins.setOnLongPressUpdateInterval(200);

		pickerSecs.setMinValue(0);
		pickerSecs.setMaxValue(59);
		pickerSecs.setValue(0);
		pickerSecs.setOnLongPressUpdateInterval(200);

		timerNameEditText = getActivity().findViewById(R.id.timerName);
		
		favoriteCheckBox = getActivity().findViewById(R.id.checkBoxFavorite);

		Intent intent = new Intent(this.getActivity(), TimerService.class);
		timerConnection = new ServiceConnection() {

			@Override
			public void onServiceDisconnected(ComponentName name) {
				localBinder = null;
			}

			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				localBinder = (LocalBinder) service;
			}
		};
		timerBound = getActivity().bindService(intent, timerConnection,
				AppCompatActivity.BIND_AUTO_CREATE);
		// if (!success) {
		// // TODO: do something
		// }

        toneButton = getActivity().findViewById(R.id.timer_tone);
        toneButton.setOnClickListener(view -> chooseTone());
        showTone(0);
        categorySpinner = getActivity().findViewById(R.id.spinner_category);
		Cursor cursor = getCategoriesCursor();
		getActivity().startManagingCursor(cursor);
		String[] columns = new String[] { DbAdapter.CATEGORIES_KEY_NAME };
		int[] to = new int[] { android.R.id.text1 };
		SimpleCursorAdapter mAdapter = new SimpleCursorAdapter(this.getActivity(),
				android.R.layout.simple_spinner_item, cursor, columns, to);

		mAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		categorySpinner.setAdapter(mAdapter);

        ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();

		if (mTimerId != -1) {
			showTimer(mTimerId);
            actionBar.setTitle(R.string.cr_timer_edit_title);
		} else {
			int categoryId = getActivity().getIntent().getIntExtra("categoryId", -1);
			if (categoryId != -1) {
				adjustCategorySpinner(categoryId);
			}
            actionBar.setTitle(R.string.menu_new_timer);
        }
        if (savedInstanceState != null && savedInstanceState.containsKey("toneIndex")) {
            showTone(savedInstanceState.getInt("toneIndex"));
        }
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		return inflater.inflate(R.layout.timer, container, false);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.action_bar_timer_fragment, menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_start_timer:
			startTimer();
			return true;
		case R.id.menu_save_timer:
			saveTimer();
			return true;
		case android.R.id.home:
			Intent intent = new Intent(getActivity().getApplicationContext(), HomeActivity.class);
			MyApplication.showRunningTimers = true;
			intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
					| Intent.FLAG_ACTIVITY_SINGLE_TOP
					| Intent.FLAG_ACTIVITY_CLEAR_TOP);
			getActivity().getApplication().startActivity(intent);
			return true;
		default:
			break;
		}
		return super.onOptionsItemSelected(item);
	}

    @Override
    public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("savedTimerId", mTimerId);
        state.putInt("toneIndex", toneIndex);
    }

    private long readDuration() {
        // Commit a typed NumberPicker value before the toolbar receives focus.
        pickerHrs.clearFocus(); pickerMins.clearFocus(); pickerSecs.clearFocus();
        return Utilities.computeLength(pickerHrs.getValue(), pickerMins.getValue(), pickerSecs.getValue());
    }

    private void message(int resource) {
        Toast.makeText(getActivity(), resource, Toast.LENGTH_SHORT).show();
    }

    private void startTimer() {
        long length = readDuration();
        if (length <= 0) { message(R.string.create_timer_no_time_set); return; }
        if (localBinder == null) { message(R.string.create_timer_service_pending); return; }
        String name = timerNameEditText.getText().toString().trim();
        localBinder.getService().startPreset(name, length, selectedTone());
        if (mTimerId != -1) {
            TimerShortcutManager.storeAppShortcut(getContext(), mTimerId,
                    name.isEmpty() ? getString(R.string.cr_timer_custom) : name, length);
        }
        MyApplication.showRunningTimers = true;
        Intent home = new Intent(getActivity(), HomeActivity.class);
        home.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(home);
        getActivity().finish();
    }

    @Override public void onDestroyView() {
        if (timerBound) getActivity().unbindService(timerConnection);
        timerBound = false;
        localBinder = null;
        super.onDestroyView();
    }

    private void showTone(int index) {
        toneIndex = index;
        toneButton.setText(com.tomatodev.timerdroid.service.AlarmTones.labels(getActivity())[index]);
    }

    private void chooseTone() {
        int[] selection = {toneIndex};
        new android.support.v7.app.AlertDialog.Builder(getActivity()).setTitle(R.string.tone_label)
                .setSingleChoiceItems(com.tomatodev.timerdroid.service.AlarmTones.labels(getActivity()),
                        toneIndex, (dialog, index) -> selection[0] = index)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> showTone(selection[0]))
                .show();
    }

    private String selectedTone() {
        return com.tomatodev.timerdroid.service.AlarmTones.key(toneIndex);
    }

    private void saveTimer() {
        long length = readDuration();
        String name = timerNameEditText.getText().toString().trim();
        if (length <= 0) { message(R.string.create_timer_no_time_set); return; }
        if (name.isEmpty()) { message(R.string.create_timer_no_name_set); return; }
        long category = categorySpinner.getSelectedItemId();
        if (category < 0) { message(R.string.create_timer_no_category); return; }
        ContentValues values = new ContentValues();
        values.put(TimerTable.TIMER_KEY_NAME, name);
        values.put("tone", selectedTone());
        values.put(TimerTable.TIMER_KEY_TIME, Long.toString(length));
        values.put(TimerTable.TIMER_KEY_CATEGORY, category);
        values.put(TimerTable.TIMER_KEY_FAVORITE, favoriteCheckBox.isChecked() ? 1 : 0);
        try {
            if (mTimerId == -1) {
                Uri saved = getActivity().getContentResolver().insert(TimerTable.CONTENT_URI, values);
                if (saved == null) { message(R.string.create_timer_save_failed); return; }
                mTimerId = (int) android.content.ContentUris.parseId(saved);
                getActivity().getIntent().putExtra("timerId", mTimerId);
                ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.cr_timer_edit_title);
            } else {
                Uri target = android.content.ContentUris.withAppendedId(TimerTable.CONTENT_URI, mTimerId);
                if (getActivity().getContentResolver().update(target, values, null, null) == 0) {
                    message(R.string.create_timer_missing); return;
                }
            }
            message(R.string.create_timer_timer_saved);
        } catch (android.database.SQLException failure) {
            android.util.Log.e("TimerDroid", "Could not save timer", failure);
            message(R.string.create_timer_save_failed);
        }
    }

    private void showTimer(int id) {
        Uri target = android.content.ContentUris.withAppendedId(TimerTable.CONTENT_URI, id);
        try (Cursor cursor = getActivity().getContentResolver().query(target, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                message(R.string.create_timer_missing);
                getActivity().finish();
                return;
            }
            timerNameEditText.setText(cursor.getString(cursor.getColumnIndexOrThrow(TimerTable.TIMER_KEY_NAME)));
            favoriteCheckBox.setChecked(cursor.getInt(cursor.getColumnIndexOrThrow(TimerTable.TIMER_KEY_FAVORITE)) == 1);
            int[] times = Utilities.lengthToTime(cursor.getLong(cursor.getColumnIndexOrThrow(TimerTable.TIMER_KEY_TIME)));
            pickerSecs.setValue(times[0]); pickerMins.setValue(times[1]); pickerHrs.setValue(times[2]);
            adjustCategorySpinner(cursor.getInt(cursor.getColumnIndexOrThrow(TimerTable.TIMER_KEY_CATEGORY)));
            showTone(com.tomatodev.timerdroid.service.AlarmTones.index(
                    cursor.getString(cursor.getColumnIndexOrThrow("tone"))));
        }
    }

    private void adjustCategorySpinner(int categoryId) {
        // Reuse the managed spinner cursor. A deleted/last/only category must not
        // move past the end or leak a second cursor.
        SimpleCursorAdapter adapter = (SimpleCursorAdapter) categorySpinner.getAdapter();
        Cursor cursor = adapter.getCursor();
        if (cursor == null) return;
        int selection = 0;
        for (int position = 0; position < cursor.getCount(); position++) {
            cursor.moveToPosition(position);
            if (cursor.getInt(cursor.getColumnIndexOrThrow(CategoriesTable._ID)) == categoryId) {
                selection = position;
                break;
            }
        }
        if (cursor.getCount() > 0) categorySpinner.setSelection(selection);
    }

	private Cursor getCategoriesCursor() {
		final String[] categoryProjection = new String[] { CategoriesProvider.CategoriesTable._ID,
				CategoriesProvider.CategoriesTable.CATEGORIES_KEY_NAME,
				CategoriesProvider.CategoriesTable.CATEGORIES_KEY_PARENT_CATEGORY,
				CategoriesProvider.CategoriesTable.CATEGORIES_KEY_IMAGE, };
		Cursor cursorAllCat = getActivity().getContentResolver().query(CategoriesTable.CONTENT_URI, categoryProjection,
				null, null, CategoriesTable.CATEGORIES_KEY_NAME);
		return cursorAllCat;
	}
}
