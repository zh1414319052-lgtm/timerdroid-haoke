package com.tomatodev.timerdroid.activities;

import android.content.Context;
import android.support.v7.app.AppCompatActivity;
import com.tomatodev.timerdroid.LanguagePolicy;

public abstract class LocalizedActivity extends AppCompatActivity {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguagePolicy.wrap(base));
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        LanguagePolicy.refresh(getResources());
    }
}
