package com.tomatodev.timerdroid.fragments;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.tomatodev.timerdroid.MyApplication;
import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.TimerCursorAdapter;
import com.tomatodev.timerdroid.activities.HomeActivity;
import com.tomatodev.timerdroid.persistence.TimersProvider;
import com.tomatodev.timerdroid.service.TimerDescription;
import com.tomatodev.timerdroid.service.TimerService;
import com.tomatodev.timerdroid.service.TimerService.LocalBinder;

import java.util.ArrayList;
import java.util.List;

public class ListTimersFragment extends ListFragment implements LoaderManager.LoaderCallbacks<Cursor> {

	private int categoryId;
	private TimerCursorAdapter items;
	
	private LocalBinder localBinder;
    private ServiceConnection connection;
    private boolean bound;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);		
		setHasOptionsMenu(true);
	}
	
	@Override
	public void onActivityCreated(Bundle savedInstanceState) {
		super.onActivityCreated(savedInstanceState);
		setEmptyText(getActivity().getString(R.string.no_timers_in_this_category));
		fillData();
	}
	
	private void fillData() {
		Intent intent = new Intent(this.getActivity(), TimerService.class);
		connection = new ServiceConnection() {

			@Override
			public void onServiceDisconnected(ComponentName name) {
				localBinder = null;
                items.setLocalBinder(null);
			}

			@Override
			public void onServiceConnected(ComponentName name, IBinder service) {
				localBinder = (LocalBinder) service;
				items.setLocalBinder(localBinder);

			}
		};
		bound = getActivity().bindService(intent,
				connection, AppCompatActivity.BIND_AUTO_CREATE);
		
		categoryId = getActivity().getIntent().getIntExtra("category_id", 1);
		
		items = new TimerCursorAdapter(this.getActivity(), null, getFragmentManager(), localBinder);
		setListAdapter(items);
        getLoaderManager().restartLoader(0, null, this);
	}
	
    @Override public void onDestroyView() {
        if (bound) getActivity().unbindService(connection);
        bound = false;
        localBinder = null;
        super.onDestroyView();
    }

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.action_bar_list_timers, menu);
	}
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menu_run_category:
            Cursor cursor = items.getCursor();
            if (localBinder == null || cursor == null) return true;
            List<TimerDescription> queue = new ArrayList<>();
            int position = cursor.getPosition();
            for (int index = 0; cursor.moveToPosition(index); index++) {
                queue.add(new TimerDescription(cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        cursor.getLong(cursor.getColumnIndexOrThrow("time")),
                        cursor.getString(cursor.getColumnIndexOrThrow("tone"))));
            }
            cursor.moveToPosition(position);
            if (!queue.isEmpty() && localBinder.getService().startWorkflow(queue, false) >= 0) {
                MyApplication.showRunningTimers = true;
                startActivity(new Intent(getActivity(), HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }

			return true;
		default:
			break;
		}
		return super.onOptionsItemSelected(item);
	}
	
	static final String[] PROJECTION = new String[] {
        TimersProvider.TimerTable._ID,
        TimersProvider.TimerTable.TIMER_KEY_NAME,
        TimersProvider.TimerTable.TIMER_KEY_TIME,
        TimersProvider.TimerTable.TIMER_KEY_CATEGORY,
        TimersProvider.TimerTable.TIMER_KEY_FAVORITE, "tone",
    };

	@Override
	public Loader<Cursor> onCreateLoader(int id, Bundle args) {
		CursorLoader cl = new CursorLoader(getActivity(), TimersProvider.TimerTable.CONTENT_URI,
                PROJECTION, TimersProvider.TimerTable.TIMER_KEY_CATEGORY + "=" + categoryId, null, null);
        return cl;
	}

	@Override
	public void onLoadFinished(Loader<Cursor> arg0, Cursor arg1) {
		items.swapCursor(arg1);
	}

	@Override
	public void onLoaderReset(Loader<Cursor> arg0) {
		items.swapCursor(null);
	}
}
