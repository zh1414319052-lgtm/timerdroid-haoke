package com.tomatodev.timerdroid.updates;

import com.tomatodev.timerdroid.BuildConfig;

/** QA and stable use the real installed package and separate server/state channels. */
public final class UpdateAppIdentity {
    public static final String APPLICATION_ID = "com.tomatodev.timerdroid.haoke";
    private UpdateAppIdentity() { }
    public static String channel(String applicationId) {
        return channel(applicationId, BuildConfig.DEBUG);
    }
    static String channel(String applicationId, boolean debug) {
        return !debug && APPLICATION_ID.equals(applicationId) ? "stable" : null;
    }
}
