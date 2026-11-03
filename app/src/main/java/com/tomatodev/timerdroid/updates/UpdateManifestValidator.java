package com.tomatodev.timerdroid.updates;

import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Verifies exact signed bytes before interpreting any update metadata. */
public final class UpdateManifestValidator {
    public static final int MAX_ENVELOPE_BYTES = 65536;
    public static final int MAX_PAYLOAD_BYTES = 32768;
    private static final long MAX_INTEGER = 9007199254740991L;
    private static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(?:\\.\\d{1,6})?Z$");
    private static final List<String> ABIS = Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64");
    private final Map<String, byte[]> trustedKeys;

    public UpdateManifestValidator(Map<String, byte[]> keys) {
        Map<String, byte[]> copied = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> key : keys.entrySet()) copied.put(key.getKey(), key.getValue().clone());
        trustedKeys = Collections.unmodifiableMap(copied);
    }

    public static final class Identity {
        public final String softwareId, applicationId, channel, certificateSha256;
        public final long versionCode;
        public final int sdk;
        public final List<String> abis;
        public Identity(String softwareId, String applicationId, String channel, long versionCode,
                        String certificateSha256, int sdk, List<String> abis) {
            this.softwareId = softwareId;
            this.applicationId = applicationId;
            this.channel = channel;
            this.versionCode = versionCode;
            this.certificateSha256 = certificateSha256;
            this.sdk = sdk;
            this.abis = Collections.unmodifiableList(new ArrayList<>(abis));
        }
        public String storageKey() { return "haoke_updates_v1." + softwareId + "." + applicationId + "." + channel + "." + certificateSha256; }
    }

    public static final class Release {
        public final String releaseId, softwareId, versionName, pageUrl, apkSha256, certificateSha256;
        public final String notesZh, notesEn;
        public final long versionCode, publishedAt, sizeBytes;
        public final int minSdk;
        public final List<String> abis;
        private Release(String releaseId, String softwareId, long versionCode, String versionName,
                        long publishedAt, String notesZh, String notesEn, String pageUrl,
                        String apkSha256, String certificateSha256, long sizeBytes, int minSdk, List<String> abis) {
            this.releaseId = releaseId; this.softwareId = softwareId; this.versionCode = versionCode;
            this.versionName = versionName; this.publishedAt = publishedAt;
            this.notesZh = notesZh; this.notesEn = notesEn; this.pageUrl = pageUrl;
            this.apkSha256 = apkSha256; this.certificateSha256 = certificateSha256;
            this.sizeBytes = sizeBytes; this.minSdk = minSdk;
            this.abis = Collections.unmodifiableList(new ArrayList<>(abis));
        }
        public boolean isCompatible(Identity identity) {
            if (identity.sdk < minSdk) return false;
            for (String abi : abis) if (identity.abis.contains(abi)) return true;
            return false;
        }
        private JSONArray content() throws Exception {
            List<String> sorted = new ArrayList<>(abis);
            Collections.sort(sorted);
            return new JSONArray().put(releaseId).put(softwareId).put(versionCode).put(versionName)
                    .put(publishedAt).put(notesZh).put(notesEn).put(pageUrl).put(apkSha256)
                    .put(certificateSha256).put(sizeBytes).put(minSdk).put(new JSONArray(sorted));
        }
    }

    public static final class Manifest {
        public final long revision, expiresAt;
        public final String status, contentDigest;
        public final Release release;
        private Manifest(long revision, String status, long expiresAt, Release release) throws Exception {
            this.revision = revision; this.status = status; this.expiresAt = expiresAt; this.release = release;
            // Renewed timestamps are allowed; changed release content at the same revision is not.
            contentDigest = sha256(new JSONArray().put(status)
                    .put(release == null ? JSONObject.NULL : release.content()).toString().getBytes(Charset.forName("UTF-8")));
        }
    }

    public static final class InvalidManifestException extends Exception {
        public InvalidManifestException() { super("Update metadata rejected"); }
    }

    public Manifest verify(byte[] envelopeBytes, Identity identity, long now) throws InvalidManifestException {
        try {
            require(envelopeBytes != null && envelopeBytes.length > 0 && envelopeBytes.length <= MAX_ENVELOPE_BYTES);
            require(SLUG.matcher(identity.softwareId).matches() && HASH.matcher(identity.certificateSha256).matches());
            require(identity.channel.equals("stable") || identity.channel.equals("qa"));
            JSONObject envelope = new JSONObject(utf8(envelopeBytes));
            require("Ed25519".equals(envelope.get("algorithm")));
            byte[] key = trustedKeys.get(text(envelope.get("keyId"), 80));
            require(key != null && key.length == 32);
            byte[] payload = decodeBase64(text(envelope.get("payload"), 43692), MAX_PAYLOAD_BYTES);
            byte[] signature = decodeBase64(text(envelope.get("signature"), 88), 64);
            require(signature.length == 64);
            EdDSAEngine verifier = new EdDSAEngine(MessageDigest.getInstance("SHA-512"));
            verifier.initVerify(new EdDSAPublicKey(new EdDSAPublicKeySpec(key, EdDSANamedCurveTable.getByName("Ed25519"))));
            verifier.update(payload);
            require(verifier.verify(signature));
            JSONObject p = new JSONObject(utf8(payload));
            require(integer(p.get("schemaVersion"), 1) == 1);
            require(identity.applicationId.equals(p.get("applicationId")) && identity.channel.equals(p.get("channel")));
            long revision = integer(p.get("revision"), 0);
            long issued = utcDate(p.get("issuedAt")), expires = utcDate(p.get("expiresAt"));
            require(issued <= now + 300000L && expires > now && expires > issued && expires - issued <= 172800000L);
            String status = text(p.get("status"), 16);
            require(status.equals("published") || status.equals("withdrawn") || status.equals("none"));
            Release release = null;
            if (status.equals("published")) {
                require(revision > 0);
                JSONObject r = p.getJSONObject("release");
                String releaseId = text(r.get("releaseId"), 80), softwareId = text(r.get("softwareId"), 80);
                require(SLUG.matcher(releaseId).matches() && identity.softwareId.equals(softwareId));
                String page = text(r.get("pageUrl"), 512);
                URI uri = new URI(page);
                require("https".equals(uri.getScheme()) && "haoke.org".equals(uri.getHost()) && uri.getPort() == -1
                        && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && ("/software/" + softwareId + "/releases/" + releaseId).equals(uri.getRawPath()));
                String certificate = text(r.get("certificateSha256"), 64), apk = text(r.get("apkSha256"), 64);
                require(HASH.matcher(apk).matches() && HASH.matcher(certificate).matches()
                        && identity.certificateSha256.equals(certificate));
                JSONArray rawAbis = r.getJSONArray("abis");
                require(rawAbis.length() > 0 && rawAbis.length() <= 4);
                List<String> abis = new ArrayList<>();
                for (int i = 0; i < rawAbis.length(); i++) {
                    String abi = text(rawAbis.get(i), 20);
                    require(ABIS.contains(abi) && !abis.contains(abi));
                    abis.add(abi);
                }
                long published = utcDate(r.get("publishedAt"));
                require(published <= issued + 300000L);
                JSONObject notes = r.getJSONObject("notes");
                long minSdk = integer(r.get("minSdk"), 21);
                require(minSdk <= Integer.MAX_VALUE);
                release = new Release(releaseId, softwareId, integer(r.get("versionCode"), 1),
                        text(r.get("versionName"), 80), published, text(notes.get("zh"), 4000),
                        text(notes.get("en"), 4000), page, apk, certificate, integer(r.get("sizeBytes"), 1), (int) minSdk, abis);
            } else require(p.has("release") && p.isNull("release"));
            return new Manifest(revision, status, expires, release);
        } catch (Exception failure) {
            // Never expose a server body, attacker-controlled URL, or crypto detail through UI/logs.
            throw new InvalidManifestException();
        }
    }

    static void require(boolean valid) throws InvalidManifestException {
        if (!valid) throw new InvalidManifestException();
    }
    static long integer(Object value, long min) throws InvalidManifestException {
        require(value instanceof Integer || value instanceof Long);
        long result = ((Number) value).longValue();
        require(result >= min && result <= MAX_INTEGER);
        return result;
    }
    static String text(Object value, int max) throws InvalidManifestException {
        require(value instanceof String && !((String) value).isEmpty() && ((String) value).length() <= max);
        return (String) value;
    }
    private static String utf8(byte[] bytes) throws Exception {
        return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static long utcDate(Object value) throws Exception {
        String date = text(value, 32);
        require(DATE.matcher(date).matches());
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        ParsePosition pos = new ParsePosition(0);
        Date parsed = format.parse(date.substring(0, 19), pos);
        require(parsed != null && pos.getIndex() == 19);
        long millis = 0;
        if (date.charAt(19) == '.') {
            String fractional = date.substring(20, date.length() - 1) + "000";
            millis = Integer.parseInt(fractional.substring(0, 3));
        }
        return parsed.getTime() + millis;
    }
    // Standard padded base64 only, also on Android 7 where java.util.Base64 is unavailable.
    static byte[] decodeBase64(String text, int max) throws InvalidManifestException {
        require(!text.isEmpty() && text.length() % 4 == 0 && text.length() <= ((max + 2) / 3) * 4);
        int pad = text.endsWith("==") ? 2 : text.endsWith("=") ? 1 : 0;
        int size = text.length() / 4 * 3 - pad;
        require(size <= max);
        byte[] bytes = new byte[size];
        int out = 0;
        for (int i = 0; i < text.length(); i += 4) {
            int a = digit(text.charAt(i)), b = digit(text.charAt(i + 1));
            int c = text.charAt(i + 2) == '=' ? -1 : digit(text.charAt(i + 2));
            int d = text.charAt(i + 3) == '=' ? -1 : digit(text.charAt(i + 3));
            boolean last = i + 4 == text.length();
            require(a >= 0 && b >= 0 && (c >= 0 || last && pad == 2) && (d >= 0 || last && pad > 0));
            if (c < 0) require((b & 15) == 0);
            else if (d < 0) require((c & 3) == 0);
            bytes[out++] = (byte) (a << 2 | b >> 4);
            if (out < size) bytes[out++] = (byte) (b << 4 | c >> 2);
            if (out < size) bytes[out++] = (byte) (c << 6 | d);
        }
        return bytes;
    }
    private static int digit(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        return c == '+' ? 62 : c == '/' ? 63 : -2;
    }
    public static String sha256(byte[] value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
