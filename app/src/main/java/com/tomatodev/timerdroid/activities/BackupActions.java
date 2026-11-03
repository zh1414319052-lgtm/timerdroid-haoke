package com.tomatodev.timerdroid.activities;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.tomatodev.timerdroid.R;
import com.tomatodev.timerdroid.persistence.TimerLibrary;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BackupActions {
    private static final int EXPORT = 400;
    private static final int IMPORT = 401;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private BackupActions() {}

    static void pick(Activity host, boolean export) {
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .setType(export ? "application/json" : "*/*").addCategory(Intent.CATEGORY_OPENABLE);
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "TimerDroid-backup.json");
        try {
            host.startActivityForResult(intent, export ? EXPORT : IMPORT);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(host, R.string.backup_error, Toast.LENGTH_LONG).show();
        }
    }

    static void result(Activity host, int request, int result, Intent data) {
        if ((request != EXPORT && request != IMPORT) || result != Activity.RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        android.content.Context context = host.getApplicationContext();
        IO.execute(() -> {
            int message;
            try {
                TimerLibrary library = new TimerLibrary(context);
                if (request == EXPORT) {
                    try (OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt")) {
                        if (stream == null) throw new java.io.IOException("Output unavailable");
                        library.exportTo(stream);
                    }
                    message = R.string.backup_exported;
                } else {
                    try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                        if (stream == null) throw new java.io.IOException("Input unavailable");
                        library.importFrom(stream);
                    }
                    message = R.string.backup_imported;
                }
            } catch (Exception error) {
                android.util.Log.e("TimerDroid", "Backup operation failed", error);
                message = R.string.backup_error;
            }
            final int toast = message;
            host.runOnUiThread(() -> Toast.makeText(context, toast, Toast.LENGTH_LONG).show());
        });
    }
}
