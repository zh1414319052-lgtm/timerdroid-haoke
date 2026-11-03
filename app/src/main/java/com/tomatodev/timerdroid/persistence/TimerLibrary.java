package com.tomatodev.timerdroid.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.tomatodev.timerdroid.service.AlarmTones;
import com.tomatodev.timerdroid.service.TimerDescription;
import com.tomatodev.timerdroid.service.TimerService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TimerLibrary {
    static final String FLOWS = "workflows";
    static final String CREATE_FLOWS = "CREATE TABLE workflows (_id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " name TEXT NOT NULL, confirm_next INTEGER NOT NULL, steps TEXT NOT NULL)";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 5000;
    public static final int MAX_STEPS = 100;
    private final Context context;

    public TimerLibrary(Context context) { this.context = context.getApplicationContext(); }

    public static final class Workflow {
        public final long id;
        public final String name;
        public final boolean confirmNext;
        public final List<TimerDescription> steps;
        public Workflow(long id, String name, boolean confirmNext, List<TimerDescription> steps) {
            this.id = id;
            this.name = name;
            this.confirmNext = confirmNext;
            this.steps = new ArrayList<>(steps);
        }
    }

    public List<Workflow> workflows() throws JSONException {
        List<Workflow> result = new ArrayList<>();
        try (DbAdapter.DbOpenHelper helper = new DbAdapter.DbOpenHelper(context);
             Cursor cursor = helper.getReadableDatabase().query(FLOWS, null, null, null, null, null,
                     "name COLLATE NOCASE, _id")) {
            while (cursor.moveToNext()) {
                result.add(new Workflow(cursor.getLong(0), cursor.getString(1), cursor.getInt(2) != 0,
                        decodeSteps(new JSONArray(cursor.getString(3)))));
            }
        }
        return result;
    }

    public void save(Workflow flow) throws JSONException {
        ContentValues values = flowValues(flow.name, flow.confirmNext, encodeSteps(flow.steps));
        try (DbAdapter.DbOpenHelper helper = new DbAdapter.DbOpenHelper(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            if (flow.id < 0) db.insertOrThrow(FLOWS, null, values);
            else if (db.update(FLOWS, values, "_id=?", args(flow.id)) != 1)
                throw new IllegalStateException("Workflow no longer exists");
        }
    }

    public void delete(long id) {
        try (DbAdapter.DbOpenHelper helper = new DbAdapter.DbOpenHelper(context)) {
            helper.getWritableDatabase().delete(FLOWS, "_id=?", args(id));
        }
    }

    private static String[] args(long value) { return new String[]{Long.toString(value)}; }

    private static String name(String value) throws JSONException {
        if (value.trim().isEmpty() || value.length() > 4096) throw new JSONException("Invalid name");
        return value;
    }

    private static long duration(long value) throws JSONException {
        if (value <= 0 || value > TimerService.MAX_DURATION) throw new JSONException("Invalid duration");
        return value;
    }

    private static JSONArray encodeSteps(List<TimerDescription> steps) throws JSONException {
        if (steps.isEmpty() || steps.size() > MAX_STEPS) throw new JSONException("Invalid step count");
        JSONArray result = new JSONArray();
        for (TimerDescription step : steps) result.put(new JSONObject()
                .put("name", name(step.getName())).put("time", duration(step.getTime()))
                .put("tone", AlarmTones.normalize(step.getTone())));
        return result;
    }

    private static List<TimerDescription> decodeSteps(JSONArray array) throws JSONException {
        if (array.length() == 0 || array.length() > MAX_STEPS) throw new JSONException("Invalid step count");
        List<TimerDescription> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            result.add(new TimerDescription(name(item.getString("name")), duration(item.getLong("time")),
                    AlarmTones.normalize(item.optString("tone"))));
        }
        return result;
    }

    private static ContentValues flowValues(String name, boolean confirm, JSONArray steps) throws JSONException {
        ContentValues values = new ContentValues();
        values.put("name", name(name));
        values.put("confirm_next", confirm ? 1 : 0);
        values.put("steps", steps.toString());
        return values;
    }

    public void exportTo(OutputStream stream) throws JSONException, IOException {
        JSONObject file = new JSONObject().put("format", "timerdroid-presets").put("version", 1);
        try (DbAdapter.DbOpenHelper helper = new DbAdapter.DbOpenHelper(context)) {
            SQLiteDatabase db = helper.getReadableDatabase();
            db.beginTransaction();
            try {
                file.put("categories", readTable(db, "categories", new String[]{"_id", "name", "parent", "image"}));
                file.put("timers", readTable(db, "timers",
                        new String[]{"name", "time", "category", "favorite", "usercreated", "tone"}));
                file.put("workflows", readTable(db, FLOWS, new String[]{"name", "confirm_next", "steps"}));
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        byte[] data = file.toString().getBytes(UTF8);
        if (data.length > MAX_BYTES) throw new IOException("Backup too large");
        stream.write(data);
    }

    private JSONArray readTable(SQLiteDatabase db, String table, String[] columns) throws JSONException {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.query(table, columns, null, null, null, null, "_id")) {
            if (cursor.getCount() > MAX_ROWS) throw new JSONException("Too many rows");
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < columns.length; i++) {
                    String value = cursor.getString(i);
                    if ("image".equals(columns[i]) && !portableImage(value)) value = null;
                    row.put(columns[i], value == null ? JSONObject.NULL : value);
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private static boolean portableImage(String value) {
        return value != null && value.matches("[a-zA-Z0-9_]{1,128}");
    }

    public void importFrom(InputStream stream) throws IOException, JSONException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            if (bytes.size() + read > MAX_BYTES) throw new IOException("Backup too large");
            bytes.write(buffer, 0, read);
        }
        JSONObject file = new JSONObject(bytes.toString(UTF8.name()));
        if (!"timerdroid-presets".equals(file.optString("format")) || file.optInt("version") != 1)
            throw new JSONException("Unsupported backup");
        JSONArray categories = bounded(file.getJSONArray("categories"));
        JSONArray timers = bounded(file.getJSONArray("timers"));
        JSONArray workflows = bounded(file.getJSONArray("workflows"));

        try (DbAdapter.DbOpenHelper helper = new DbAdapter.DbOpenHelper(context)) {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                Map<Long, Long> ids = importCategories(db, categories);
                for (int i = 0; i < timers.length(); i++) mergeTimer(db, timers.getJSONObject(i), ids);
                for (int i = 0; i < workflows.length(); i++) {
                    JSONObject row = workflows.getJSONObject(i);
                    ContentValues values = flowValues(row.getString("name"), row.getInt("confirm_next") != 0,
                            encodeSteps(decodeSteps(new JSONArray(row.getString("steps")))));
                    if (find(db, FLOWS, "name=? AND confirm_next=? AND steps=?",
                            values.getAsString("name"), values.getAsString("confirm_next"),
                            values.getAsString("steps")) < 0) db.insertOrThrow(FLOWS, null, values);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        context.getContentResolver().notifyChange(CategoriesProvider.CategoriesTable.CONTENT_URI, null);
        context.getContentResolver().notifyChange(TimersProvider.TimerTable.CONTENT_URI, null);
    }

    private static JSONArray bounded(JSONArray rows) throws JSONException {
        if (rows.length() > MAX_ROWS) throw new JSONException("Too many rows");
        return rows;
    }

    private Map<Long, Long> importCategories(SQLiteDatabase db, JSONArray rows) throws JSONException {
        Map<Long, Long> ids = new HashMap<>();
        Map<Long, JSONObject> pending = new java.util.LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            long id = row.getLong("_id");
            if (id <= 0 || pending.put(id, row) != null) throw new JSONException("Invalid category ID");
        }
        while (!pending.isEmpty()) {
            int before = pending.size();
            java.util.Iterator<Map.Entry<Long, JSONObject>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, JSONObject> entry = iterator.next();
                JSONObject row = entry.getValue();
                long parent = row.optLong("parent", 0);
                if (parent > 0 && !ids.containsKey(parent)) continue;
                long mappedParent = parent > 0 ? ids.get(parent) : 0;
                String name = name(row.getString("name"));
                long id = find(db, "categories", "name=? AND COALESCE(parent,0)=CAST(? AS INTEGER)", name,
                        Long.toString(mappedParent));
                if (id < 0) {
                    ContentValues values = new ContentValues();
                    values.put("name", name);
                    values.put("parent", mappedParent);
                    String image = row.optString("image", "");
                    if (portableImage(image)) values.put("image", image);
                    id = db.insertOrThrow("categories", null, values);
                }
                ids.put(entry.getKey(), id);
                iterator.remove();
            }
            if (before == pending.size()) throw new JSONException("Invalid category hierarchy");
        }
        return ids;
    }

    private void mergeTimer(SQLiteDatabase db, JSONObject row, Map<Long, Long> ids) throws JSONException {
        Long category = ids.get(row.getLong("category"));
        if (category == null) throw new JSONException("Missing category");
        String name = name(row.getString("name"));
        String time = Long.toString(duration(row.getLong("time")));
        String tone = AlarmTones.normalize(row.optString("tone"));
        long existing = find(db, "timers", "name=? AND time=? AND category=? AND tone=?", name, time,
                category.toString(), tone);
        ContentValues values = new ContentValues();
        if (row.optInt("favorite") == 1) values.put("favorite", 1);
        if (existing >= 0) {
            if (values.size() > 0) db.update("timers", values, "_id=?", args(existing));
            return;
        }
        if (values.size() == 0) values.put("favorite", 0);
        values.put("name", name);
        values.put("time", time);
        values.put("category", category);
        values.put("tone", tone);
        values.put("usercreated", row.optInt("usercreated", 1) == 0 ? 0 : 1);
        db.insertOrThrow("timers", null, values);
    }

    private long find(SQLiteDatabase db, String table, String where, String... arguments) {
        try (Cursor cursor = db.query(table, new String[]{"_id"}, where, arguments, null, null, "_id", "1")) {
            return cursor.moveToFirst() ? cursor.getLong(0) : -1;
        }
    }
}
