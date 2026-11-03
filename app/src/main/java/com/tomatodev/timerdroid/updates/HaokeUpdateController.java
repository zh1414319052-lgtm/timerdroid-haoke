package com.tomatodev.timerdroid.updates;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Activity-owned adapter. Call cancel on pause and close on destroy. */
public final class HaokeUpdateController implements HaokeUpdateUi.Backend {
    public static final String SOFTWARE_ID = "720ccd10b30051dd";
    public static final String APPLICATION_ID = UpdateAppIdentity.APPLICATION_ID;
    // Public notification key only. Exact SPKI SHA-256: 000ef7a45a5a58d5cafce6acba5e0292cf0c11b1259f1c89b801bddfdadfe84f.
    public static final String KEY_ID = "haoke-updates-000ef7a45a5a58d5";
    public static final String PUBLIC_KEY = "+8cSV1C+t+6GipgPi3a2zjZ4T3W/Z+tZDKzzO4tQXOY=";
    public interface Listener { void onResult(HaokeUpdateService.Result result); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
    private HaokeUpdateService service;
    private final Context appContext;
    private long generation;
    private boolean closed;

    public HaokeUpdateController(Context context) {
        appContext = context.getApplicationContext();
        service = initialize(appContext);
    }

    private HaokeUpdateService initialize(Context app) {
        HaokeUpdateService candidate = null;
        try {
            UpdateManifestValidator.Identity identity = installedIdentity(app);
            SharedPreferences prefs = app.getSharedPreferences("haoke_signed_updates_v1", Context.MODE_PRIVATE);
            HaokeUpdateService.Store store = new HaokeUpdateService.Store() {
                @Override public String read(String key) { return prefs.getString(key, null); }
                @Override public void write(String key, String value) throws Exception {
                    if (!prefs.edit().putString(key, value).commit()) throw new Exception("Update preferences unavailable");
                }
            };
            UpdateManifestValidator validator = new UpdateManifestValidator(Collections.singletonMap(KEY_ID,
                    UpdateManifestValidator.decodeBase64(PUBLIC_KEY, 32)));
            candidate = new HaokeUpdateService(identity, validator, store,
                    token -> fetch(identity, token), worker, System::currentTimeMillis);
        } catch (Exception unsupported) {
            // Unsupported identity is distinct from a signed no-update response.
        }
        return candidate;
    }

    public void check(boolean manual, Listener listener) {
        if (closed) return;
        long requestGeneration = ++generation;
        if (service == null) service = initialize(appContext);
        if (service == null) {
            main.post(() -> {
                if (!closed && generation == requestGeneration) listener.onResult(new HaokeUpdateService.Result(HaokeUpdateService.Status.UNSUPPORTED));
            });
            return;
        }
        service.check(manual).onComplete(result -> main.post(() -> {
            if (!closed && generation == requestGeneration) listener.onResult(result);
        }));
    }
    public void cancel() {
        ++generation;
        if (service != null) service.cancel();
    }
    public boolean ignore(UpdateManifestValidator.Release release) { return service != null && service.ignore(release); }
    public boolean snooze() { return service != null && service.snooze(); }
    public boolean isFresh(HaokeUpdateService.Result result) { return !closed && service != null && service.isFresh(result); }
    public boolean present(HaokeUpdateService.Result result, boolean manual, Runnable open) {
        return !closed && service != null && service.present(result, manual, open);
    }
    public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
        deadline.shutdownNow();
    }

    @SuppressWarnings("deprecation")
    private static UpdateManifestValidator.Identity installedIdentity(Context context) throws Exception {
        String applicationId = context.getPackageName();
        String channel = UpdateAppIdentity.channel(applicationId);
        if (channel == null) throw new Exception("Unsupported installed identity");
        PackageManager pm = context.getPackageManager();
        PackageInfo info = pm.getPackageInfo(applicationId, Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null || info.signingInfo.hasMultipleSigners()) throw new Exception("Unsupported signing identity");
            signatures = info.signingInfo.getApkContentsSigners();
        } else signatures = info.signatures;
        if (signatures == null || signatures.length != 1) throw new Exception("Missing signing identity");
        long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (version <= 0) throw new Exception("Invalid installed version");
        return new UpdateManifestValidator.Identity(SOFTWARE_ID, applicationId, channel, version,
                UpdateManifestValidator.sha256(signatures[0].toByteArray()), Build.VERSION.SDK_INT,
                Build.VERSION.SDK_INT >= 21 ? Arrays.asList(Build.SUPPORTED_ABIS)
                        : Arrays.asList(Build.CPU_ABI, Build.CPU_ABI2));
    }

    private byte[] fetch(UpdateManifestValidator.Identity identity, HaokeUpdateService.Cancellation token) throws Exception {
        URL url = new URL("https://www.haoke.org/api/client-updates/v1/" + identity.applicationId + "/" + identity.channel);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        // No identifiers, account credentials, timer names, or device identifiers are sent.
        token.setOnCancel(connection::disconnect);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timeout = deadline.schedule(() -> {
            timedOut.set(true);
            connection.disconnect();
        }, 12, TimeUnit.SECONDS);
        try {
            if (token.isCancelled()) throw new Exception("Cancelled");
            int status = connection.getResponseCode();
            if (status == 404) throw new HaokeUpdateService.UnregisteredException();
            if (status != 200) throw new Exception("Update service unavailable");
            String type = connection.getContentType();
            if (type == null || !"application/json".equals(type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT))
                    || connection.getContentLength() > UpdateManifestValidator.MAX_ENVELOPE_BYTES) throw new Exception("Update response rejected");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (token.isCancelled() || timedOut.get() || bytes.size() + count > UpdateManifestValidator.MAX_ENVELOPE_BYTES) throw new Exception("Update response rejected");
                    bytes.write(chunk, 0, count);
                }
                if (timedOut.get()) throw new Exception("Update request timed out");
                return bytes.toByteArray();
            }
        } finally {
            timeout.cancel(false);
            token.setOnCancel(null);
            connection.disconnect();
        }
    }
}
