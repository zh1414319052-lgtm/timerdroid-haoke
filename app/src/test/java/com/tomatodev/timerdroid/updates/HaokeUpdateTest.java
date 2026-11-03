package com.tomatodev.timerdroid.updates;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class HaokeUpdateTest {
    @Test public void installedDistributionIdentityIsExactAndChannelsAreSeparate() {
        assertEquals("stable", UpdateAppIdentity.channel("com.tomatodev.timerdroid.haoke", false));
        assertNull(UpdateAppIdentity.channel("com.tomatodev.timerdroid.haoke", true));
        assertNull(UpdateAppIdentity.channel("com.tomatodev.timerdroid.haoke.qa", true));
        assertNull(UpdateAppIdentity.channel("com.tomatodev.timerdroid", false));
    }

    @Test public void callbacksWorkBeforeAndAfterCompletionWithoutPlatformFutures() throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        AtomicInteger callbacks = new AtomicInteger();
        HaokeUpdateService svc = new HaokeUpdateService(IDENTITY, validator(), new MemoryStore(),
                token -> signed(payload(0, "none")), queued::add, () -> NOW);
        HaokeUpdateService.Check check = svc.check(false);
        check.onComplete(result -> { assertEquals(HaokeUpdateService.Status.CURRENT, result.status); callbacks.incrementAndGet(); });
        assertEquals(0, callbacks.get());
        queued.remove().run();
        assertEquals(1, callbacks.get());
        check.onComplete(result -> { assertEquals(HaokeUpdateService.Status.CURRENT, result.status); callbacks.incrementAndGet(); });
        assertEquals(2, callbacks.get());
    }

    private static final String SOFTWARE = "9cc1a2a65d669b28";
    private static final String APP = "net.glsk.wpgen";
    private static final String CERT = "a".repeat(64);
    private static final long NOW = 1791417600000L; // 2026-10-08 00:00:00 UTC
    private static final UpdateManifestValidator.Identity IDENTITY = identity(SOFTWARE, APP, "stable", CERT, 24, "arm64-v8a");
    // Deterministic, isolated TEST ONLY identity. Never used by client/website builds.
    private static final EdDSAPrivateKeySpec TEST_KEY = new EdDSAPrivateKeySpec(new byte[32], EdDSANamedCurveTable.getByName("Ed25519"));
    private static UpdateManifestValidator validator() { return new UpdateManifestValidator(Collections.singletonMap("unit-test-key", TEST_KEY.getA().toByteArray())); }
    private static UpdateManifestValidator.Identity identity(String software, String app, String channel, String cert, int sdk, String abi) {
        return new UpdateManifestValidator.Identity(software, app, channel, 32, cert, sdk, Collections.singletonList(abi));
    }
    private static JSONObject payload(long revision, String status) throws Exception {
        JSONObject p = new JSONObject().put("schemaVersion", 1).put("applicationId", APP).put("channel", "stable")
                .put("revision", revision).put("issuedAt", "2026-10-08T00:00:00.000Z").put("expiresAt", "2026-10-10T00:00:00.000Z")
                .put("status", status).put("release", JSONObject.NULL);
        if (status.equals("published")) p.put("release", new JSONObject().put("releaseId", "release-33").put("softwareId", SOFTWARE)
                .put("versionCode", 33).put("versionName", "3.2.1-haoke").put("publishedAt", "2026-10-07T23:59:00Z")
                .put("notes", new JSONObject().put("zh", "修复壁纸导出").put("en", "Fix wallpaper export"))
                .put("pageUrl", "https://haoke.org/software/" + SOFTWARE + "/releases/release-33")
                .put("apkSha256", "b".repeat(64)).put("certificateSha256", CERT).put("sizeBytes", 1024)
                .put("minSdk", 24).put("abis", new JSONArray().put("arm64-v8a").put("x86_64")));
        return p;
    }
    private static byte[] signed(JSONObject p) throws Exception {
        return signedText(p.toString());
    }
    private static byte[] signedText(String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        EdDSAEngine signer = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
        signer.initSign(new EdDSAPrivateKey(TEST_KEY)); signer.update(bytes);
        return new JSONObject().put("algorithm", "Ed25519").put("keyId", "unit-test-key")
                .put("payload", Base64.getEncoder().encodeToString(bytes)).put("signature", Base64.getEncoder().encodeToString(signer.sign()))
                .toString().getBytes(StandardCharsets.UTF_8);
    }
    @FunctionalInterface
    private interface ThrowingJsonMutation {
        void accept(JSONObject payload) throws Exception;
    }
    private static void rejects(ThrowingJsonMutation change) throws Exception {
        JSONObject p = payload(2, "published"); change.accept(p);
        assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> validator().verify(signed(p), IDENTITY, NOW));
    }
    private static final class MemoryStore implements HaokeUpdateService.Store {
        final Map<String, String> data = new HashMap<>();
        boolean fail;
        @Override public String read(String key) { return data.get(key); }
        @Override public void write(String key, String value) throws Exception {
            if (fail) throw new Exception("Synthetic disk failure"); data.put(key, value);
        }
    }
    private static HaokeUpdateService service(MemoryStore store, HaokeUpdateService.Transport fetch, AtomicLong clock) {
        return new HaokeUpdateService(IDENTITY, validator(), store, fetch, Runnable::run, clock::get);
    }

    @Test public void validPublishedAndNoneAndWithdrawal() throws Exception {
        UpdateManifestValidator.Manifest published = validator().verify(signed(payload(2, "published")), IDENTITY, NOW);
        assertEquals(33, published.release.versionCode); assertTrue(published.release.isCompatible(IDENTITY));
        assertEquals("none", validator().verify(signed(payload(0, "none")), IDENTITY, NOW).status);
        assertNull(validator().verify(signed(payload(3, "withdrawn")), IDENTITY, NOW).release);
    }
    @Test public void exactBytesServerFixtureUsesPinnedPublicKeyAndRejectsForeignApp() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/updates/server-anx-none-v1.json")) {
            assertNotNull(stream);
            byte[] fixture = stream.readAllBytes();
            JSONObject envelope = new JSONObject(new String(fixture, StandardCharsets.UTF_8));
            JSONObject p = new JSONObject(new String(Base64.getDecoder().decode(envelope.getString("payload")), StandardCharsets.UTF_8));
            byte[] pin = Base64.getDecoder().decode("+8cSV1C+t+6GipgPi3a2zjZ4T3W/Z+tZDKzzO4tQXOY=");
            UpdateManifestValidator live = new UpdateManifestValidator(Collections.singletonMap("haoke-updates-000ef7a45a5a58d5", pin));
            long time = UpdateManifestValidator.utcDate(p.get("issuedAt")) + 600000L;
            UpdateManifestValidator.Identity anx = identity("b22fe43210bee032", "org.haoke.anxreader", "stable", CERT, 24, "arm64-v8a");
            assertEquals("none", live.verify(fixture, anx, time).status);
            assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> live.verify(fixture, IDENTITY, time));
            byte[] spki = new byte[44];
            byte[] prefix = {0x30,0x2a,0x30,0x05,0x06,0x03,0x2b,0x65,0x70,0x03,0x21,0x00};
            System.arraycopy(prefix, 0, spki, 0, prefix.length); System.arraycopy(pin, 0, spki, prefix.length, pin.length);
            assertEquals("000ef7a45a5a58d5cafce6acba5e0292cf0c11b1259f1c89b801bddfdadfe84f", UpdateManifestValidator.sha256(spki));
        }
    }
    @Test public void signatureTamperingAndUnknownKeyAreRejected() throws Exception {
        JSONObject envelope = new JSONObject(new String(signed(payload(2, "published")), StandardCharsets.UTF_8));
        byte[] bytes = Base64.getDecoder().decode(envelope.getString("payload")); bytes[12] ^= 1;
        envelope.put("payload", Base64.getEncoder().encodeToString(bytes));
        assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> validator().verify(envelope.toString().getBytes(StandardCharsets.UTF_8), IDENTITY, NOW));
        JSONObject unknownEnvelope = new JSONObject(new String(signed(payload(2, "published")), StandardCharsets.UTF_8)).put("keyId", "foreign-key");
        byte[] unknown = unknownEnvelope.toString().getBytes(StandardCharsets.UTF_8);
        assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> validator().verify(unknown, IDENTITY, NOW));
    }
    @Test public void foreignSoftwarePackageChannelAndCertificateAreRejected() throws Exception {
        rejects(p -> p.put("applicationId", "org.other.application"));
        rejects(p -> p.put("channel", "qa"));
        rejects(p -> p.getJSONObject("release").put("softwareId", "other-product"));
        rejects(p -> p.getJSONObject("release").put("certificateSha256", "c".repeat(64)));
    }
    @Test public void invalidSchemaTypeAndRevisionAreRejected() throws Exception {
        rejects(p -> p.put("schemaVersion", 2)); rejects(p -> p.put("schemaVersion", "1"));
        byte[] decimalSchema = signedText(payload(2, "published").toString().replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"));
        assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> validator().verify(decimalSchema, IDENTITY, NOW));
        rejects(p -> p.put("revision", -1)); rejects(p -> p.put("revision", 1.5));
        rejects(p -> p.put("revision", 9007199254740992L)); rejects(p -> p.put("revision", 0));
    }
    @Test public void expiryClockSkewLifetimeAndNormalizedDatesAreRejected() throws Exception {
        rejects(p -> p.put("expiresAt", "2026-10-08T00:00:00Z"));
        rejects(p -> p.put("issuedAt", "2026-10-08T00:05:01Z"));
        rejects(p -> p.put("expiresAt", "2026-10-10T00:00:01Z"));
        rejects(p -> p.put("issuedAt", "2026-09-31T00:00:00Z"));
        rejects(p -> p.put("issuedAt", "2026-10-08T00:00:00+00:00"));
        rejects(p -> p.getJSONObject("release").put("publishedAt", "2026-10-08T00:05:01Z"));
    }
    @Test public void nonPublishedMustHaveNullRelease() throws Exception {
        rejects(p -> p.put("status", "none")); rejects(p -> p.put("status", "withdrawn"));
        rejects(p -> p.put("status", "untrusted"));
    }
    @Test public void pageMustBeImmutableHttpsProductReleasePage() throws Exception {
        for (String url : Arrays.asList("http://haoke.org/software/" + SOFTWARE + "/releases/release-33",
                "https://evil.org/software/" + SOFTWARE + "/releases/release-33",
                "https://haoke.org:443/software/" + SOFTWARE + "/releases/release-33",
                "https://haoke.org/software/" + SOFTWARE + "/releases/release-33?download=1",
                "https://haoke.org/software/" + SOFTWARE + "/releases/release-33#download",
                "https://haoke.org/software/other/releases/release-33")) {
            rejects(p -> p.getJSONObject("release").put("pageUrl", url));
        }
    }
    @Test public void artifactAbiAndSizeMetadataAreBounded() throws Exception {
        rejects(p -> p.getJSONObject("release").put("sizeBytes", 0));
        rejects(p -> p.getJSONObject("release").put("minSdk", 20));
        rejects(p -> p.getJSONObject("release").put("versionCode", "33"));
        rejects(p -> p.getJSONObject("release").put("apkSha256", "B".repeat(64)));
        rejects(p -> p.getJSONObject("release").put("abis", new JSONArray().put("unknown")));
        rejects(p -> p.getJSONObject("release").put("abis", new JSONArray().put("x86").put("x86")));
    }
    @Test public void oversizedAndNoncanonicalBase64AreRejected() throws Exception {
        assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> validator().verify(new byte[65537], IDENTITY, NOW));
        JSONObject env = new JSONObject(new String(signed(payload(2, "published")), StandardCharsets.UTF_8));
        env.put("payload", env.getString("payload") + "\n");
        assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> validator().verify(env.toString().getBytes(StandardCharsets.UTF_8), IDENTITY, NOW));
        assertThrows(UpdateManifestValidator.InvalidManifestException.class, () -> UpdateManifestValidator.decodeBase64("AB==", 32));
        assertArrayEquals(new byte[] {0}, UpdateManifestValidator.decodeBase64("AA==", 32));
    }
    @Test public void contentDigestAllowsRenewalButNotChangedRelease() throws Exception {
        JSONObject p = payload(2, "published");
        String first = validator().verify(signed(p), IDENTITY, NOW).contentDigest;
        p.put("issuedAt", "2026-10-08T00:01:00Z").put("expiresAt", "2026-10-09T00:01:00Z");
        assertEquals(first, validator().verify(signed(p), IDENTITY, NOW).contentDigest);
        p.getJSONObject("release").put("versionName", "tampered-but-signed");
        assertNotEquals(first, validator().verify(signed(p), IDENTITY, NOW).contentDigest);
    }
    @Test public void throttlePersistsAcrossRestartAndManualBypassesIt() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicLong clock = new AtomicLong(NOW); AtomicInteger calls = new AtomicInteger();
        byte[] none = signed(payload(0, "none"));
        HaokeUpdateService.Transport fetch = token -> { calls.incrementAndGet(); return none; };
        assertEquals(HaokeUpdateService.Status.CURRENT, service(store, fetch, clock).check(false).get().status);
        assertEquals(HaokeUpdateService.Status.DEBOUNCED, service(store, fetch, clock).check(false).get().status);
        assertEquals(HaokeUpdateService.Status.CURRENT, service(store, fetch, clock).check(true).get().status);
        assertEquals(2, calls.get()); clock.addAndGet(HaokeUpdateService.ONE_DAY);
        assertEquals(HaokeUpdateService.Status.CURRENT, service(store, fetch, clock).check(false).get().status);
    }
    @Test public void rollbackChangedRevisionAndWithdrawalCannotReviveCachedRelease() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicLong clock = new AtomicLong(NOW); byte[][] response = { signed(payload(2, "published")) };
        HaokeUpdateService service = service(store, token -> response[0], clock);
        assertEquals(HaokeUpdateService.Status.AVAILABLE, service.check(true).get().status);
        response[0] = signed(payload(0, "none")); assertEquals(HaokeUpdateService.Status.INVALID, service.check(true).get().status);
        JSONObject changed = payload(2, "published"); changed.getJSONObject("release").put("versionName", "changed");
        response[0] = signed(changed); assertEquals(HaokeUpdateService.Status.INVALID, service.check(true).get().status);
        response[0] = signed(payload(3, "withdrawn")); assertEquals(HaokeUpdateService.Status.CURRENT, service.check(true).get().status);
        response[0] = signed(payload(2, "published")); HaokeUpdateService.Result failed = service.check(true).get();
        assertEquals(HaokeUpdateService.Status.INVALID, failed.status); assertNull(failed.release);
    }
    @Test public void ignoreAndSnoozePersistWhileManualStillValidates() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicLong clock = new AtomicLong(NOW); byte[] release = signed(payload(2, "published"));
        HaokeUpdateService service = service(store, token -> release, clock);
        HaokeUpdateService.Result available = service.check(true).get(); assertTrue(service.ignore(available.release)); assertTrue(service.snooze());
        clock.addAndGet(HaokeUpdateService.DEBOUNCE);
        HaokeUpdateService restarted = service(store, token -> release, clock);
        assertEquals(HaokeUpdateService.Status.SKIPPED, restarted.check(false).get().status);
        assertEquals(HaokeUpdateService.Status.AVAILABLE, restarted.check(true).get().status);
        assertTrue(restarted.isFresh(available)); clock.set(NOW + 172800000L); assertFalse(restarted.isFresh(available));
        assertEquals(HaokeUpdateService.Status.INVALID, restarted.check(true).get().status);
    }
    @Test public void incompatibleSdkAndAbiAreReported() throws Exception {
        for (UpdateManifestValidator.Identity identity : Arrays.asList(identity(SOFTWARE, APP, "stable", CERT, 23, "arm64-v8a"),
                identity(SOFTWARE, APP, "stable", CERT, 24, "armeabi-v7a"))) {
            HaokeUpdateService svc = new HaokeUpdateService(identity, validator(), new MemoryStore(), token -> signed(payload(2, "published")), Runnable::run, () -> NOW);
            assertEquals(HaokeUpdateService.Status.INCOMPATIBLE, svc.check(true).get().status);
        }
    }
    @Test public void currentInstalledVersionIsNotOfferedAgain() throws Exception {
        JSONObject p = payload(2, "published"); p.getJSONObject("release").put("versionCode", 32);
        HaokeUpdateService svc = service(new MemoryStore(), token -> signed(p), new AtomicLong(NOW));
        assertEquals(HaokeUpdateService.Status.CURRENT, svc.check(true).get().status);
    }
    @Test public void failedRefreshAndMissingBindingDoNotBecomeCurrent() throws Exception {
        HaokeUpdateService failed = service(new MemoryStore(), token -> { throw new Exception("offline"); }, new AtomicLong(NOW));
        assertEquals(HaokeUpdateService.Status.UNAVAILABLE, failed.check(true).get().status);
        HaokeUpdateService missing = service(new MemoryStore(), token -> { throw new HaokeUpdateService.UnregisteredException(); }, new AtomicLong(NOW));
        assertEquals(HaokeUpdateService.Status.UNREGISTERED, missing.check(true).get().status);
    }
    @Test public void storageFailuresAndCorruptStateFailClosedBeforeNetworking() throws Exception {
        MemoryStore store = new MemoryStore(); store.fail = true; AtomicInteger calls = new AtomicInteger();
        HaokeUpdateService svc = service(store, token -> { calls.incrementAndGet(); return signed(payload(0, "none")); }, new AtomicLong(NOW));
        assertEquals(HaokeUpdateService.Status.STORAGE_FAILURE, svc.check(true).get().status); assertEquals(0, calls.get());
        store.fail = false; store.data.put(IDENTITY.storageKey(), "{\"revision\":3,\"digest\":\"broken\"}");
        assertEquals(HaokeUpdateService.Status.STORAGE_FAILURE, svc.check(true).get().status); assertEquals(0, calls.get());
    }
    @Test public void rapidClicksShareOneFlightAndCancelBeforeTransport() throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>(); AtomicInteger calls = new AtomicInteger();
        HaokeUpdateService svc = new HaokeUpdateService(IDENTITY, validator(), new MemoryStore(), token -> { calls.incrementAndGet(); return signed(payload(0, "none")); }, queued::add, () -> NOW);
        HaokeUpdateService.Check first = svc.check(false), second = svc.check(true);
        assertSame(first, second); assertEquals(1, queued.size()); svc.cancel(); queued.remove().run();
        assertEquals(HaokeUpdateService.Status.CANCELLED, first.get().status); assertEquals(0, calls.get());
    }
    @Test public void cancellingDuringFetchDiscardsLateReleaseAndPreventsRevisionWrite() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicInteger disconnects = new AtomicInteger(); final HaokeUpdateService[] holder = new HaokeUpdateService[1];
        holder[0] = service(store, token -> { token.setOnCancel(disconnects::incrementAndGet); holder[0].cancel(); return signed(payload(2, "published")); }, new AtomicLong(NOW));
        assertEquals(HaokeUpdateService.Status.CANCELLED, holder[0].check(true).get().status);
        assertEquals(1, disconnects.get()); assertFalse(new JSONObject(store.data.get(IDENTITY.storageKey())).has("revision"));
    }
    @Test public void stateIsIsolatedPerProductAndChannel() {
        assertNotEquals(IDENTITY.storageKey(), identity("other-product", APP, "stable", CERT, 24, "arm64-v8a").storageKey());
        assertNotEquals(IDENTITY.storageKey(), identity(SOFTWARE, APP + ".debug", "qa", CERT, 24, "arm64-v8a").storageKey());
        assertNotEquals(IDENTITY.storageKey(), identity(SOFTWARE, APP, "stable", "c".repeat(64), 24, "arm64-v8a").storageKey());
    }

    @Test public void presentationAcknowledgementPersistsAcrossRestartAndManualCanReopen() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicLong clock = new AtomicLong(NOW);
        byte[] bytes = signed(payload(2,"published")); AtomicInteger opened = new AtomicInteger();
        HaokeUpdateService first = service(store, token -> bytes, clock);
        HaokeUpdateService.Result result = first.check(false).get();
        assertTrue(first.present(result,false,opened::incrementAndGet));
        assertFalse(first.present(result,false,opened::incrementAndGet));
        clock.addAndGet(HaokeUpdateService.DEBOUNCE);
        HaokeUpdateService restarted = service(store, token -> bytes, clock);
        assertEquals(HaokeUpdateService.Status.SKIPPED,restarted.check(false).get().status);
        assertTrue(restarted.present(restarted.check(true).get(),true,opened::incrementAndGet));
        assertEquals(2,opened.get());
    }
    @Test public void laterIsPerVersionAndExpiresWithoutBlockingNewVersions() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicLong clock = new AtomicLong(NOW);
        byte[][] bytes = {signed(payload(2,"published"))};
        HaokeUpdateService svc = service(store, token -> bytes[0],clock);
        assertTrue(svc.present(svc.check(false).get(),false,() -> {})); assertTrue(svc.snooze());
        clock.addAndGet(HaokeUpdateService.DEBOUNCE);
        assertEquals(HaokeUpdateService.Status.SKIPPED,svc.check(false).get().status);
        JSONObject newer = payload(3,"published"); newer.getJSONObject("release").put("versionCode",34);
        bytes[0] = signed(newer); clock.addAndGet(HaokeUpdateService.DEBOUNCE);
        assertEquals(HaokeUpdateService.Status.AVAILABLE,svc.check(false).get().status);
        clock.set(NOW + HaokeUpdateService.ONE_DAY);
        HaokeUpdateService restarted = service(store,token -> bytes[0],clock);
        assertEquals(HaokeUpdateService.Status.AVAILABLE,restarted.check(false).get().status);
    }
    @Test public void failedModalOrStorageDoesNotConsumeReminder() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicLong clock = new AtomicLong(NOW);
        HaokeUpdateService svc = service(store,token -> signed(payload(2,"published")),clock);
        HaokeUpdateService.Result result = svc.check(true).get();
        assertFalse(svc.present(result,false,() -> {throw new IllegalStateException("UI gone");}));
        assertTrue(svc.present(result,false,() -> {}));
        store.fail = true;
        assertFalse(svc.present(result,true,() -> fail("Must not open without persistence")));
    }
    @Test public void failedAutomaticCheckCanRecoverAfterOneMinute() throws Exception {
        MemoryStore store = new MemoryStore(); AtomicLong clock = new AtomicLong(NOW); AtomicInteger calls = new AtomicInteger();
        HaokeUpdateService svc = service(store,token -> {if(calls.getAndIncrement()==0) throw new java.io.IOException("offline"); return signed(payload(0,"none"));},clock);
        assertEquals(HaokeUpdateService.Status.UNAVAILABLE,svc.check(false).get().status);
        clock.addAndGet(60000);
        assertEquals(HaokeUpdateService.Status.CURRENT,svc.check(false).get().status);
        assertEquals(2,calls.get());
    }
    @Test public void foregroundRetriesAreBounded() {
        ForegroundRetryPolicy policy = new ForegroundRetryPolicy();
        assertEquals(60000,policy.nextDelay()); assertEquals(300000,policy.nextDelay()); assertEquals(-1,policy.nextDelay());
        policy.reset(); assertEquals(60000,policy.nextDelay());
    }
}
