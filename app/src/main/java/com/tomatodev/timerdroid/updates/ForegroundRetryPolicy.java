package com.tomatodev.timerdroid.updates;

/** Bounded retries belong to the foreground session, never a background worker. */
public final class ForegroundRetryPolicy {
    private int retries;
    public long nextDelay() {
        if (retries == 0) { retries++; return 60000; }
        if (retries == 1) { retries++; return 300000; }
        return -1;
    }
    public void reset() { retries = 0; }
}
