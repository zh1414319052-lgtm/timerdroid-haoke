package com.tomatodev.timerdroid;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.widget.EditText;
import com.tomatodev.timerdroid.activities.TimerActivity;
import com.tomatodev.timerdroid.persistence.CategoriesProvider.CategoriesTable;
import com.tomatodev.timerdroid.persistence.TimersProvider.TimerTable;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.*;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TimerEditorTest {
    @Rule public ActivityTestRule<TimerActivity> activity = new ActivityTestRule<>(TimerActivity.class, true, false);
    private final ContentResolver resolver = InstrumentationRegistry.getTargetContext().getContentResolver();

    @After public void removeSyntheticRecords() {
        resolver.delete(TimerTable.CONTENT_URI, "name LIKE ?", new String[]{"QA_editor_%"});
    }
    private void typeSeconds(String value) {
        onView(allOf(isDescendantOfA(withId(R.id.timer_picker_secs)), isAssignableFrom(EditText.class)))
                .perform(click(), replaceText(value), closeSoftKeyboard());
    }
    private void save() { onView(withId(R.id.menu_save_timer)).perform(click()); }
    private Cursor records(String name) {
        return resolver.query(TimerTable.CONTENT_URI, null, "name = ?", new String[]{name}, null);
    }
    private Uri preset(String name) {
        long category;
        try (Cursor cursor = resolver.query(CategoriesTable.CONTENT_URI, new String[]{"_id"}, null, null, null)) {
            assertTrue(cursor.moveToFirst()); category = cursor.getLong(0);
        }
        ContentValues values = new ContentValues();
        values.put("name", name); values.put("time", 30000); values.put("category", category);
        values.put("favorite", 1);
        return resolver.insert(TimerTable.CONTENT_URI, values);
    }
    private Intent edit(Uri uri) {
        Intent intent = new Intent();
        intent.putExtra("timerId", (int)ContentUris.parseId(uri));
        return intent;
    }
    @Test public void typedDurationAndRepeatedSaveSurviveRecreationWithoutDuplicates() {
        activity.launchActivity(null);
        onView(withId(R.id.timerName)).perform(replaceText("QA_editor_saved"), closeSoftKeyboard());
        typeSeconds("45"); save(); save();
        try (Cursor cursor = records("QA_editor_saved")) {
            assertEquals(1, cursor.getCount()); assertTrue(cursor.moveToFirst());
            assertEquals(45000, cursor.getLong(cursor.getColumnIndex("time")));
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.getActivity().recreate());
        onView(withId(R.id.timerName)).check(matches(withText("QA_editor_saved")));
        save();
        try (Cursor cursor = records("QA_editor_saved")) { assertEquals(1, cursor.getCount()); }
    }
    @Test public void zeroDurationKeepsTheEditorOpen() {
        activity.launchActivity(null);
        onView(withId(R.id.menu_start_timer)).perform(click());
        onView(withId(R.id.timerName)).check(matches(isDisplayed()));
        assertFalse(activity.getActivity().isFinishing());
    }
    @Test public void editingAnExistingPresetKeepsItsIdentity() {
        Uri preset = preset("QA_editor_original");
        activity.launchActivity(edit(preset));
        onView(withId(R.id.timerName)).perform(replaceText("QA_editor_renamed"), closeSoftKeyboard());
        save();
        try (Cursor cursor = resolver.query(preset, null, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("QA_editor_renamed", cursor.getString(cursor.getColumnIndex("name")));
        }
        try (Cursor cursor = records("QA_editor_original")) { assertEquals(0, cursor.getCount()); }
    }
    @Test public void aDeletedPresetIsNotSilentlyRecreated() {
        Uri preset = preset("QA_editor_deleted");
        activity.launchActivity(edit(preset));
        resolver.delete(preset, null, null);
        save();
        try (Cursor cursor = records("QA_editor_deleted")) { assertEquals(0, cursor.getCount()); }
        onView(withId(R.id.timerName)).check(matches(withText("QA_editor_deleted")));
    }
}
