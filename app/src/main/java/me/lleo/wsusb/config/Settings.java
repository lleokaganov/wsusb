package me.lleo.wsusb.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.HashMap;
import java.util.Map;

/**
 * Persistent app settings (SharedPreferences). Keep this small and dumb — just
 * key/value reads and writes. The relay binary takes the WS URL + optional
 * server pubkeys via environment variables (USBWS_WS_URL / USBWS_SERVER_X_PUB /
 * USBWS_SERVER_ED_PUB); we resolve those here so the rest of the app does not
 * need to know about hex keys.
 *
 * Default server: wss://tele.karlson.ru/ws (RU-reachable relay used by telefon).
 * Known hosts are auto-bound to the right (x_pub, ed_pub) so the user only
 * needs to type a URL; custom hosts leave the env unset, falling back to the
 * binary's compile-time defaults.
 */
public final class Settings {

    private static final String PREFS_NAME = "wsusb.settings";
    private static final String KEY_RELAY_URL = "relay_url";
    private static final String KEY_BATTERY_PROMPT_DONE = "battery_prompt_done";

    /** Default relay URL — RU-reachable telefon relay. */
    public static final String DEFAULT_RELAY_URL = "wss://tele.karlson.ru/ws";

    /**
     * Hosts we know the (X25519, Ed25519) public keys for. Lookup is by URL
     * host name only; the URL path is free-form. Hex strings must be 64 chars.
     */
    private static final Map<String, String[]> KNOWN_HOSTS = new HashMap<>();
    static {
        // telefon relay (hosted as tele.karlson.ru and telefon.lleo.me — same backend, same keys)
        String[] telefonKeys = new String[]{
                "4e8250d28b9b28836aadf6497535ef01056f19982d08ba4059b5c93537c80f06",
                "b835840fd3aba7cc4519513f3bbcb1c35170f6aa97d97c16eabdb2e36710d003"
        };
        KNOWN_HOSTS.put("tele.karlson.ru", telefonKeys);
        KNOWN_HOSTS.put("telefon.lleo.me", telefonKeys);

        // ws.lleo.me — the home Pi relay (this is what the usbws binary defaults to).
        KNOWN_HOSTS.put("ws.lleo.me", new String[]{
                "2beba374aeb45b1220bd06a794dea54bc4484aad0a81dbea9d3d5518da73005b",
                "08d98c12e044d5cacdf54933934c9a4e34f4ce7b3527adbd29a9c0a736f3bf0f"
        });
    }

    private final SharedPreferences prefs;

    public Settings(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** User-configured relay URL (defaults to {@link #DEFAULT_RELAY_URL}). */
    public String getRelayUrl() {
        String v = prefs.getString(KEY_RELAY_URL, null);
        if (v == null || v.trim().isEmpty()) {
            return DEFAULT_RELAY_URL;
        }
        return v.trim();
    }

    public void setRelayUrl(String url) {
        prefs.edit().putString(KEY_RELAY_URL, url == null ? "" : url.trim()).apply();
    }

    public boolean isBatteryPromptDone() {
        return prefs.getBoolean(KEY_BATTERY_PROMPT_DONE, false);
    }

    public void markBatteryPromptDone() {
        prefs.edit().putBoolean(KEY_BATTERY_PROMPT_DONE, true).apply();
    }

    /**
     * Returns the (x_pub_hex, ed_pub_hex) pair matching the URL's host, or null
     * if the host is unknown (caller should leave the env unset so the binary
     * falls back to its compile-time defaults).
     */
    public static String[] lookupServerKeys(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            Uri parsed = Uri.parse(url);
            String host = parsed.getHost();
            if (host == null) return null;
            return KNOWN_HOSTS.get(host.toLowerCase());
        } catch (Exception e) {
            return null;
        }
    }

    /** True iff the URL starts with ws:// or wss://. Used by the settings UI. */
    public static boolean isValidRelayUrl(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        return trimmed.startsWith("ws://") || trimmed.startsWith("wss://");
    }
}
