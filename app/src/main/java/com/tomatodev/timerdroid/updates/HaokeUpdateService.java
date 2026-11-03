package com.tomatodev.timerdroid.updates;

import org.json.JSONObject;
import java.util.concurrent.Executor;
import java.util.ArrayList;
import java.util.List;

/** Product-scoped, foreground-only update checks. No cached release survives a failed check. */
public final class HaokeUpdateService {
    public static final long ONE_DAY = 86400000L;
    public static final long DEBOUNCE = 60000L;
    public enum Status { AVAILABLE, CURRENT, DEBOUNCED, SKIPPED, CANCELLED, INVALID, UNAVAILABLE, UNREGISTERED, INCOMPATIBLE, UNSUPPORTED, STORAGE_FAILURE }
    public static final class Result {
        public final Status status;
        public final UpdateManifestValidator.Release release;
        public final long expiresAt;
        public Result(Status status) { this(status, null, 0); }
        Result(Status status, UpdateManifestValidator.Release release, long expiresAt) {
            this.status = status; this.release = release; this.expiresAt = expiresAt;
        }
    }
    public interface Clock { long getAsLong(); }
    public interface ResultListener { void onResult(Result result); }
    /** Small callback result compatible with API 15; never block an Android UI thread. */
    public static final class Check {
        private Result value;
        private final List<ResultListener> listeners = new ArrayList<>();
        public void onComplete(ResultListener listener) {
            Result ready;
            synchronized (this) {
                ready = value;
                if (ready == null) { listeners.add(listener); return; }
            }
            listener.onResult(ready);
        }
        private void complete(Result result) {
            List<ResultListener> waiting;
            synchronized (this) {
                if (value != null) return;
                value = result;
                waiting = new ArrayList<>(listeners);
                listeners.clear();
                notifyAll();
            }
            for (ResultListener listener : waiting) listener.onResult(result);
        }
        /** Used by JVM tests only. Production uses onComplete. */
        public synchronized Result get() throws InterruptedException {
            while (value == null) wait();
            return value;
        }
    }
    public interface Store {
        String read(String key) throws Exception;
        void write(String key, String value) throws Exception;
    }
    public interface Transport { byte[] fetch(Cancellation cancellation) throws Exception; }
    public static final class UnregisteredException extends Exception { }
    public static final class Cancellation {
        private volatile boolean cancelled;
        private volatile boolean manual;
        private Runnable onCancel;
        public boolean isCancelled() { return cancelled; }
        public synchronized void setOnCancel(Runnable action) {
            onCancel = action;
            if (cancelled && action != null) action.run();
        }
        public synchronized void cancel() {
            cancelled = true;
            if (onCancel != null) onCancel.run();
        }
    }
    private final UpdateManifestValidator.Identity identity;
    private final UpdateManifestValidator validator;
    private final Store store;
    private final Transport transport;
    private final Executor worker;
    private final Clock clock;
    private Check flight;
    private Cancellation cancellation;

    public HaokeUpdateService(UpdateManifestValidator.Identity identity, UpdateManifestValidator validator,
                              Store store, Transport transport, Executor worker, Clock clock) {
        this.identity = identity; this.validator = validator; this.store = store;
        this.transport = transport; this.worker = worker; this.clock = clock;
    }

    /** Rapid manual taps and simultaneous automatic checks share one request. */
    public synchronized Check check(boolean manual) {
        if (flight != null) {
            if (manual) cancellation.manual = true;
            return flight;
        }
        Check result = new Check();
        Cancellation token = new Cancellation();
        token.manual = manual;
        flight = result;
        cancellation = token;
        try {
            worker.execute(() -> {
                Result checked;
                try { checked = checkCore(token); }
                catch (Exception ignored) { checked = new Result(token.isCancelled() ? Status.CANCELLED : Status.UNAVAILABLE); }
                synchronized (HaokeUpdateService.this) {
                    if (flight == result) { flight = null; cancellation = null; }
                }
                result.complete(checked);
            });
        } catch (RuntimeException rejected) {
            flight = null; cancellation = null;
            result.complete(new Result(Status.UNAVAILABLE));
        }
        return result;
    }

    public synchronized void cancel() { if (cancellation != null) cancellation.cancel(); }

    private Result checkCore(Cancellation token) {
        try {
            long now = clock.getAsLong();
            synchronized (this) {
                JSONObject state = readState();
                long last = state.optLong("lastAttempt", -1);
                if (!token.manual && last >= 0 && now >= last && now - last < DEBOUNCE) return new Result(Status.DEBOUNCED);
                if (token.isCancelled()) return new Result(Status.CANCELLED);
                state.put("lastAttempt", now);
                saveState(state);
            }
            if (token.isCancelled()) return new Result(Status.CANCELLED);
            byte[] bytes = transport.fetch(token);
            if (token.isCancelled()) return new Result(Status.CANCELLED);
            UpdateManifestValidator.Manifest manifest = validator.verify(bytes, identity, clock.getAsLong());
            synchronized (this) {
                if (token.isCancelled()) return new Result(Status.CANCELLED);
                JSONObject state = readState();
                long previous = state.optLong("revision", -1);
                if (manifest.revision < previous || manifest.revision == previous
                        && !manifest.contentDigest.equals(state.optString("digest", ""))) return new Result(Status.INVALID);
                state.put("revision", manifest.revision).put("digest", manifest.contentDigest);
                saveState(state);
                if (token.isCancelled()) return new Result(Status.CANCELLED);
                if (manifest.expiresAt <= clock.getAsLong()) return new Result(Status.INVALID);
                UpdateManifestValidator.Release release = manifest.release;
                if (release == null || release.versionCode <= identity.versionCode) return new Result(Status.CURRENT);
                if (release.versionCode < state.optLong("maximumVersion", 0)) return new Result(Status.INVALID);
                state.put("maximumVersion", release.versionCode);
                saveState(state);
                if (!release.isCompatible(identity)) return new Result(Status.INCOMPATIBLE);
                if (!token.manual && !shouldPresent(state, release.versionCode)) return new Result(Status.SKIPPED);
                return new Result(Status.AVAILABLE, release, manifest.expiresAt);
            }
        } catch (UpdateManifestValidator.InvalidManifestException rejected) {
            return new Result(Status.INVALID);
        } catch (StorageException unavailable) {
            return new Result(Status.STORAGE_FAILURE);
        } catch (UnregisteredException missing) {
            return new Result(token.isCancelled() ? Status.CANCELLED : Status.UNREGISTERED);
        } catch (Exception unavailable) {
            return new Result(token.isCancelled() ? Status.CANCELLED : Status.UNAVAILABLE);
        }
    }

    public synchronized boolean ignore(UpdateManifestValidator.Release release) {
        if (!identity.softwareId.equals(release.softwareId)) return false;
        try { JSONObject state = readState(); state.put("ignoredVersion", release.versionCode); saveState(state); return true; }
        catch (Exception unavailable) { return false; }
    }
    public synchronized boolean snooze() {
        try { JSONObject state = readState(); state.put("snoozedVersion", state.optLong("maximumVersion", 0)); state.put("snoozeUntil", clock.getAsLong() + ONE_DAY); saveState(state); return true; }
        catch (Exception unavailable) { return false; }
    }
    private boolean shouldPresent(JSONObject state, long version) {
        if (state.optLong("ignoredVersion", -1) == version) return false;
        if (state.optLong("snoozedVersion", -1) == version) return state.optLong("snoozeUntil", 0) <= clock.getAsLong();
        return state.optLong("acknowledgedVersion", -1) != version;
    }
    /** Persist at the synchronous modal-open boundary, rolling back if opening fails. */
    public synchronized boolean present(Result result, boolean manual, Runnable open) {
        if (!isFresh(result)) return false;
        try {
            JSONObject state = readState();
            long version = result.release.versionCode;
            if (!manual && !shouldPresent(state, version)) return false;
            String previous = state.toString();
            state.put("acknowledgedVersion", version);
            state.remove("snoozedVersion"); state.remove("snoozeUntil");
            saveState(state);
            try { open.run(); return true; }
            catch (RuntimeException failed) { saveState(new JSONObject(previous)); return false; }
        } catch (Exception unavailable) { return false; }
    }
    public boolean isFresh(Result result) {
        return result != null && result.status == Status.AVAILABLE && result.release != null && result.expiresAt > clock.getAsLong();
    }
    private JSONObject readState() throws StorageException {
        try {
            String value = store.read(identity.storageKey());
            JSONObject state = value == null ? new JSONObject() : new JSONObject(value);
            for (String key : new String[] { "revision", "lastAttempt", "ignoredVersion", "snoozeUntil", "snoozedVersion", "acknowledgedVersion", "maximumVersion" }) {
                if (state.has(key)) UpdateManifestValidator.integer(state.get(key), 0);
            }
            if (state.has("revision")) UpdateManifestValidator.require(state.get("digest") instanceof String
                    && ((String) state.get("digest")).matches("^[a-f0-9]{64}$"));
            return state;
        } catch (Exception unavailable) { throw new StorageException(); }
    }
    private void saveState(JSONObject state) throws StorageException {
        try { store.write(identity.storageKey(), state.toString()); }
        catch (Exception unavailable) { throw new StorageException(); }
    }
    private static final class StorageException extends Exception { }
}
