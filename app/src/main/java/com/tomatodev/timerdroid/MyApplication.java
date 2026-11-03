package com.tomatodev.timerdroid;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;

public class MyApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguagePolicy.wrap(base));
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LanguagePolicy.refresh(getResources());
    }
	public static boolean mainVisible = false;
	private static int counter;
	
	public static boolean showRunningTimers = false;
	
	public static synchronized int getId() {
		return counter++;
	}
}
