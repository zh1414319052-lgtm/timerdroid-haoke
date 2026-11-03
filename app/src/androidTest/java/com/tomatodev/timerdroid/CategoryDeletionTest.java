package com.tomatodev.timerdroid;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import com.tomatodev.timerdroid.persistence.CategoriesProvider.CategoriesTable;
import com.tomatodev.timerdroid.persistence.TimersProvider.TimerTable;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CategoryDeletionTest {
    private final ContentResolver resolver = InstrumentationRegistry.getTargetContext().getContentResolver();

    private Uri category(String name) {
        ContentValues values = new ContentValues();
        values.put("name", name); values.put("parent", 0);
        return resolver.insert(CategoriesTable.CONTENT_URI, values);
    }
    private Uri timer(Uri category, String name) {
        ContentValues values = new ContentValues();
        values.put("name", name); values.put("time", 60000);
        values.put("category", ContentUris.parseId(category)); values.put("favorite", 1);
        return resolver.insert(TimerTable.CONTENT_URI, values);
    }
    private int count(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{"_id"}, null, null, null)) {
            return cursor.getCount();
        }
    }
    private void exercise(boolean collectionUri) {
        Uri removed = category("QA_delete_category");
        Uri kept = category("QA_keep_category");
        Uri removedTimer = timer(removed, "QA_delete_favorite");
        Uri keptTimer = timer(kept, "QA_keep_favorite");
        try {
            int deleted = collectionUri
                    ? resolver.delete(CategoriesTable.CONTENT_URI, "_id = ?", new String[]{removed.getLastPathSegment()})
                    : resolver.delete(removed, null, null);
            assertEquals(1, deleted);
            assertEquals(0, count(removed));
            assertEquals("Deleted-category favorites must disappear", 0, count(removedTimer));
            assertEquals(1, count(kept));
            assertEquals(1, count(keptTimer));
        } finally {
            resolver.delete(removedTimer, null, null); resolver.delete(keptTimer, null, null);
            resolver.delete(removed, null, null); resolver.delete(kept, null, null);
        }
    }
    @Test public void itemDeletionRemovesItsFavoritesOnly() { exercise(false); }
    @Test public void collectionDeletionUsesTheSameCascade() { exercise(true); }
    @Test public void unmatchedSelectionMustPreserveTimers() {
        Uri category = category("QA_unmatched_category");
        Uri timer = timer(category, "QA_unmatched_timer");
        try {
            assertEquals(0, resolver.delete(category, "name = ?", new String[]{"not-this-category"}));
            assertEquals(1, count(category)); assertEquals(1, count(timer));
        } finally {
            resolver.delete(timer, null, null); resolver.delete(category, null, null);
        }
    }
    @Test public void deletedCategoryRejectsAStaleEditorWrite() {
        Uri category = category("QA_stale_category");
        resolver.delete(category, null, null);
        try {
            Uri unexpected = timer(category, "QA_stale_insert");
            resolver.delete(unexpected, null, null);
            fail("A deleted category must not accept a new timer");
        } catch (android.database.SQLException expected) {
            // The rejected input must not create an orphaned favorite.
        }
    }
}
