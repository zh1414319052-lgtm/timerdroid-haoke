package com.tomatodev.timerdroid.activities;

import android.content.Intent;
import android.os.Bundle;
import android.support.design.widget.TabLayout;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;

import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.fragments.AboutDialogFragment;
import com.tomatodev.timerdroid.fragments.CategoriesFragment;
import com.tomatodev.timerdroid.fragments.NewCategoryDialogFragment;
import com.tomatodev.timerdroid.fragments.RunningTimersFragment;

public class HomeActivity extends LocalizedActivity {

    /**
     * The {@link android.support.v4.view.PagerAdapter} that will provide
     * fragments for each of the sections. We use a
     * {@link FragmentPagerAdapter} derivative, which will keep every
     * loaded fragment in memory. If this becomes too memory intensive, it
     * may be best to switch to a
     * {@link android.support.v4.app.FragmentStatePagerAdapter}.
     */
    private SectionsPagerAdapter mSectionsPagerAdapter;

    /**
     * The {@link ViewPager} that will host the section contents.
     */
    private ViewPager mViewPager;
    private com.tomatodev.timerdroid.updates.HaokeUpdateUi updates;

    @Override protected void onResume() {
        super.onResume();
        if (updates != null) updates.resume();
    }

    @Override protected void onPause() {
        if (updates != null) updates.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (updates != null) updates.close();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        // Create the adapter that will return a fragment for each of the three
        // primary sections of the activity.
        mSectionsPagerAdapter = new SectionsPagerAdapter(getSupportFragmentManager());

        // Set up the ViewPager with the sections adapter.
        mViewPager = (ViewPager) findViewById(R.id.container);
        mViewPager.setAdapter(mSectionsPagerAdapter);

        TabLayout tabLayout = findViewById(R.id.tabs);

        tabLayout.setupWithViewPager(mViewPager);
        if (!com.tomatodev.timerdroid.BuildConfig.DEBUG) {
            updates = new com.tomatodev.timerdroid.updates.HaokeUpdateUi(this,
                    new com.tomatodev.timerdroid.updates.HaokeUpdateUi.Host() {
                @Override public boolean isSafeForUpdates(boolean manual) {
                    if (manual) return true;
                    if (!hasWindowFocus() || mViewPager.getCurrentItem() != 0) return false;
                    Fragment page = getSupportFragmentManager().findFragmentByTag(
                            "android:switcher:" + R.id.container + ":0");
                    return page instanceof RunningTimersFragment
                            && ((RunningTimersFragment) page).canShowUpdate();
                }
                @Override public void onDialogShown(android.support.v7.app.AlertDialog dialog) { }
            });
        }
    }

    private void addItem() {
        if (mViewPager.getCurrentItem() == 2) {
            new NewCategoryDialogFragment().show(getSupportFragmentManager(), "dialog");
        } else {
            startActivity(new Intent(this, TimerActivity.class));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_home, menu);
        menu.findItem(R.id.menu_updates).setVisible(updates != null);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.

        switch (item.getItemId()) {
            case R.id.menu_updates:
                if (updates != null) updates.manual();
                return true;
            case R.id.menu_home_add:
                addItem();
                return true;
            case R.id.menu_workflows:
                startActivity(new Intent(this, WorkflowsActivity.class));
                return true;
            case R.id.menu_backup_export:
                BackupActions.pick(this, true);
                return true;
            case R.id.menu_backup_import:
                BackupActions.pick(this, false);
                return true;
            case R.id.menu_settings:
                Intent settingsIntent = new Intent(this, UserSettingsActivity.class);
                startActivity(settingsIntent);
                return true;
            case R.id.menu_about:
                DialogFragment newFragment = AboutDialogFragment.newInstance();
                newFragment.show(this.getSupportFragmentManager(), "about_dialog");
                return true;
            case R.id.menu_feedback:
                Intent myIntent = new Intent(android.content.Intent.ACTION_SEND);
                myIntent.setType("text/plain");
                myIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.menu_feedback_subject));
                myIntent.putExtra(Intent.EXTRA_EMAIL, new String [] { getString(R.string.menu_feedback_address) });
                startActivity(Intent.createChooser(myIntent, getResources().getString(R.string.feedback_chooser_title)));
                return true;
            case R.id.menu_share:
                onInviteClicked();
                return true;
            default:
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        BackupActions.result(this, request, result, data);
    }

    private void onInviteClicked() {
        Intent shareIntent = new Intent(android.content.Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.menu_share_subject));
        shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.menu_share_url));
        startActivity(Intent.createChooser(shareIntent, getResources().getString(R.string.feedback_chooser_title)));
    }

    /**
     * A {@link FragmentPagerAdapter} that returns a fragment corresponding to
     * one of the sections/tabs/pages.
     */
    public class SectionsPagerAdapter extends FragmentPagerAdapter {

        public SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int position) {

            switch (position){
                case 0: return new RunningTimersFragment();
                case 1: return RunningTimersFragment.favoritesPage();
                case 2: return new CategoriesFragment();
            }
            throw new IllegalArgumentException("position for tab not valid: " + position);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            int[] titles = {R.string.running_timers, R.string.list_timers_title_favorites, R.string.categories_title};
            return getString(titles[position]);
        }

        @Override
        public int getCount() {
            return 3;
        }
    }
}
