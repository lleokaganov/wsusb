package me.lleo.wsusb.relay;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Map;

import me.lleo.wsusb.config.Settings;

/**
 * Drives the bundled usbws native binary (libusbws.so) to bridge the local
 * USB/IP server (TCP 127.0.0.1:3240) through the encrypted relay.
 *
 * The binary is shipped inside jniLibs/arm64-v8a and is extracted to
 * nativeLibraryDir at install time (extractNativeLibs=true), which is the only
 * place from which Android allows executing a process.
 *
 * START spawns:  libusbws.so tcp-connect 127.0.0.1:3240 --accept
 * with env USBWS_IDENTITY pointing at a per-device identity file in filesDir.
 *
 * Capability / accept-incoming mode: the gate no longer needs a fixed peer. It
 * listens on its own invite and accepts whoever connects knowing it, learning
 * the initiator's key from the handshake. The first remote to connect (when the
 * authorized table is empty) is trusted-on-first-use and remembered as an owner;
 * afterwards only listed owners are accepted. The authorized table is a plain
 * file named "authorized" living alongside the identity file (filesDir), one
 * "&lt;x_pub_hex64&gt; [nick]" per line.
 *
 * Identity is unique per install: usbws performs load-or-create on the path in
 * USBWS_IDENTITY, so the keypair is generated on this device the first time
 * keygen (or tcp-connect) runs, and persists across restarts. The invite to
 * hand to the remote side is produced by {@link #generateInvite()}.
 */
public class RelayController {
    private static final String TAG = "wsusb";

    // Native binary name as packaged in jniLibs (must keep the lib*.so shape so
    // the build system bundles it and Android extracts it).
    private static final String BINARY_NAME = "libusbws.so";

    // Per-device identity file (load-or-create by usbws). Lives in filesDir so it
    // is unique to this install and never bundled in the APK.
    private static final String IDENTITY_FILE = "usbws_identity";

    // Authorized-owners table written by usbws (capability list). It lives in the
    // same directory as the identity file (filesDir) and is named "authorized";
    // one "<x_pub_hex64> [nick]" per line. Empty/missing => trust-on-first-use.
    private static final String AUTHORIZED_FILE = "authorized";

    // Local TCP endpoint of the USB/IP server (UsbIpServer.PORT == 3240).
    private static final String LOCAL_ENDPOINT = "127.0.0.1:3240";

    /**
     * Combined lifecycle + connectivity state for the UI.
     *
     * OFF / CONNECTING / ON describe the subprocess. ON used to mean "process
     * started" only — we now refine it by parsing usbws stdout so the UI can
     * distinguish "process up but WS not connected yet" (DISCONNECTED → red)
     * from "WS connected to the relay" (ON → green) and from "WS dropped,
     * the binary's own retry loop is retrying" (DISCONNECTED → red).
     */
    public enum State {
        /** Subprocess not running. */
        OFF,
        /** Subprocess starting, no stdout yet. */
        CONNECTING,
        /** Subprocess up AND relay reports a live WebSocket. */
        ON,
        /** Subprocess up but relay WebSocket is currently dropped / retrying. */
        DISCONNECTED,
        /** Subprocess failed to start (exec error or immediate exit). */
        ERROR
    }

    /** Callback invoked on the UI thread when the relay state changes. */
    public interface StateListener {
        void onRelayState(State state);
    }

    /**
     * Callback invoked on the UI thread when the relay process emits a
     * "STAT tx=N rx=N" stat line. N is 0 (idle in this window) or 1 (had
     * traffic). Used to blink two little TX/RX dots on screen so the user
     * sees data is flowing without caring about throughput.
     */
    public interface TrafficListener {
        void onTraffic(boolean tx, boolean rx);
    }

    private final Context appContext;

    // Guarded by 'this'.
    private Process process;
    private Thread logThread;
    private State state = State.OFF;
    private StateListener listener;
    private TrafficListener trafficListener;
    // The relay URL the running subprocess was started with. Surfaces on the
    // main screen so you can see at a glance which server you're actually
    // talking to — distinct from whatever is currently saved in prefs (which
    // only takes effect on the next start).
    private String currentRelayUrl;
    // True while we're supposed to keep the relay running (set by start(),
    // cleared by stop()). If the subprocess dies while this is true, the
    // watchdog respawns it after a short delay — covers Android killing the
    // process, the local USB/IP server closing its accept-side on the last
    // detach, and other transient deaths.
    private boolean shouldBeRunning;

    public RelayController(Context context) {
        // Hold the application context to avoid leaking the Activity.
        this.appContext = context.getApplicationContext();
    }

    public synchronized void setStateListener(StateListener l) {
        this.listener = l;
    }

    public synchronized void setTrafficListener(TrafficListener l) {
        this.trafficListener = l;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized boolean isRunning() {
        return process != null;
    }

    /**
     * URL the running subprocess was started with, or null if no subprocess.
     * Use this on the main screen — what's in SharedPreferences may differ
     * because the subprocess only picks up env on (re)start.
     */
    public synchronized String getCurrentRelayUrl() {
        return process != null ? currentRelayUrl : null;
    }

    /**
     * Restart the subprocess so it picks up new prefs (server URL, keys, etc).
     * No-op if it wasn't running — you'd just call start() in that case. Used
     * by SettingsActivity right after Save so the user doesn't need to do
     * "Stop sharing" → re-plug manually.
     */
    public synchronized void restartIfRunning() {
        if (process == null) return;
        stop();
        // start() spawns a worker thread for the actual exec, so we can call
        // it back-to-back without blocking the caller.
        start();
    }

    /** Absolute path to the per-device identity file in filesDir. */
    private File identityFile() {
        return new File(appContext.getFilesDir(), IDENTITY_FILE);
    }

    /** Absolute path to the (legacy) authorized-owners table in filesDir. */
    private File authorizedFile() {
        return new File(appContext.getFilesDir(), AUTHORIZED_FILE);
    }

    /**
     * Rotate this device's identity: deletes the keypair and the legacy
     * authorized table, then restarts the subprocess (which will load-or-
     * create a fresh keypair). The old invite immediately stops working;
     * the new invite is whatever generateInvite() returns afterwards.
     *
     * Use this when the previous invite leaked or you just want a fresh
     * one. The subprocess MUST be restarted because the running instance
     * holds the old x_pub in memory.
     */
    public synchronized boolean resetIdentity() {
        boolean wasRunning = (process != null);
        if (wasRunning) {
            stop();
        }
        boolean ok = true;
        File id = identityFile();
        if (id.exists() && !id.delete()) {
            Log.w(TAG, "could not delete identity at " + id.getAbsolutePath());
            ok = false;
        }
        File auth = authorizedFile();
        if (auth.exists() && !auth.delete()) {
            // Not fatal — table is unused under USBWS_NO_PINNING=1, but tidy.
            Log.w(TAG, "could not delete legacy authorized table at " + auth.getAbsolutePath());
        }
        if (wasRunning) {
            start();
        }
        return ok;
    }

    private String binaryPath() {
        return appContext.getApplicationInfo().nativeLibraryDir
                + File.separator + BINARY_NAME;
    }

    /**
     * Runs `libusbws.so keygen`, which load-or-creates the per-device identity
     * and prints this device's invite ("K0...") on stdout (with human notes on
     * stderr). Returns the invite string, or null on failure.
     *
     * Safe to call repeatedly: keygen is idempotent once the identity exists.
     * Blocks briefly; call off the UI thread.
     */
    public String generateInvite() {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath(), "keygen");
            pb.environment().put("USBWS_IDENTITY", identityFile().getAbsolutePath());
            // Keep stdout (the invite) separate from stderr (human notes).
            pb.redirectErrorStream(false);

            Process p = pb.start();

            // Read stdout: the invite is printed there as a single line.
            String invite = null;
            BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
            try {
                String line;
                while ((line = out.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("K0")) {
                        invite = line;
                    }
                }
            } finally {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }

            // Drain stderr to logcat (id, file location, notes).
            BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            try {
                String line;
                while ((line = err.readLine()) != null) {
                    Log.i(TAG, "keygen: " + line);
                }
            } finally {
                try {
                    err.close();
                } catch (IOException ignored) {
                }
            }

            int exit = p.waitFor();
            if (exit != 0) {
                Log.e(TAG, "keygen exited with code " + exit);
            }
            return invite;
        } catch (IOException | InterruptedException e) {
            Log.e(TAG, "failed to run keygen", e);
            return null;
        }
    }

    /**
     * Starts the relay process in accept-incoming (capability) mode. Returns
     * immediately; actual startup happens on a background thread and is reported
     * via the StateListener.
     *
     * No peer invite is needed: the gate listens on its own identity and accepts
     * the first remote that connects knowing this device's invite (then only the
     * remembered owners, per the authorized table).
     */
    public synchronized void start() {
        shouldBeRunning = true;
        if (process != null) {
            // Already running.
            return;
        }

        setState(State.CONNECTING);

        Thread starter = new Thread(new Runnable() {
            @Override
            public void run() {
                startInternal();
            }
        }, "usbws-starter");
        starter.start();
    }

    /** Stops the relay process if running. Safe to call when already stopped. */
    public synchronized void stop() {
        shouldBeRunning = false;
        Process p = process;
        Thread t = logThread;
        process = null;
        logThread = null;

        if (p != null) {
            p.destroy();

            // Force-kill if it does not exit promptly. Done on a helper thread so
            // we never block the caller.
            final Process toKill = p;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                    }
                    if (toKill.isAlive()) {
                        toKill.destroyForcibly();
                    }
                }
            }, "usbws-killer").start();
        }

        if (t != null) {
            t.interrupt();
        }

        setState(State.OFF);
    }

    private void startInternal() {
        try {
            File identity = identityFile();
            String binaryPath = binaryPath();

            // Accept-incoming (capability) mode: no fixed --peer. The gate waits
            // for an initiator who knows our invite; the authorized table (next to
            // the identity in filesDir) gates who is allowed (TOFU when empty).
            ProcessBuilder pb = new ProcessBuilder(
                    binaryPath,
                    "tcp-connect",
                    LOCAL_ENDPOINT,
                    "--accept");
            // usbws load-or-creates this identity, so it exists after the first run.
            Map<String, String> env = pb.environment();
            env.put("USBWS_IDENTITY", identity.getAbsolutePath());

            // Wire up the configured WS endpoint + matching relay pubkeys. We
            // resolve them here (not in the binary) so the user can pick a
            // server in Settings without touching native code; unknown hosts
            // leave the env unset and the binary falls back to its own defaults.
            Settings settings = new Settings(appContext);
            String relayUrl = settings.getRelayUrl();
            env.put("USBWS_WS_URL", relayUrl);
            String[] keys = Settings.lookupServerKeys(relayUrl);
            if (keys != null && keys.length == 2) {
                env.put("USBWS_SERVER_X_PUB", keys[0]);
                env.put("USBWS_SERVER_ED_PUB", keys[1]);
            }
            // Lab-tool semantics: knowing the invite IS the access. No TOFU
            // pinning, no authorized table — anyone who knows the invite is
            // accepted, no surprise rejects after a reinstall.
            env.put("USBWS_NO_PINNING", "1");
            pb.redirectErrorStream(true);

            Log.i(TAG, "starting relay: " + binaryPath + " tcp-connect "
                    + LOCAL_ENDPOINT + " --accept (identity=" + identity.getAbsolutePath()
                    + ", url=" + relayUrl
                    + ", keys=" + (keys != null ? "preset" : "binary-default") + ")");

            Process p = pb.start();

            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    pumpLogs(p);
                }
            }, "usbws-log");
            reader.setDaemon(true);

            synchronized (this) {
                process = p;
                logThread = reader;
                currentRelayUrl = relayUrl;
                // Process is up but the WS hasn't reported "connected" yet;
                // start in DISCONNECTED so the UI immediately shows red and
                // flips to green only when we see "[usbws] relay connected".
                setState(State.DISCONNECTED);
            }

            reader.start();
        } catch (IOException e) {
            Log.e(TAG, "failed to start relay", e);
            synchronized (this) {
                process = null;
                logThread = null;
                setState(State.ERROR);
            }
        }
    }

    /** Reads the merged stdout/stderr of the process to logcat until it exits. */
    private void pumpLogs(Process p) {
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        try {
            while ((line = br.readLine()) != null) {
                if (line.startsWith("STAT ")) {
                    // Stat tick: "STAT tx=0|1 rx=0|1". Don't spam logcat with
                    // these (they fire 4×/s); just hand them to the UI.
                    handleStatLine(line);
                } else {
                    Log.i(TAG, line);
                    // Parse the binary's own connectivity messages so the UI
                    // can show a sharp red/green status without spawning more
                    // sidecars. usbws emits one of these on each transition.
                    maybeUpdateConnState(line);
                }
            }
        } catch (IOException e) {
            // Stream closed (process exited or destroyed) -- expected on stop.
        } finally {
            try {
                br.close();
            } catch (IOException ignored) {
            }
        }

        int exit;
        try {
            exit = p.waitFor();
        } catch (InterruptedException e) {
            exit = -1;
        }
        Log.i(TAG, "relay process exited (code=" + exit + ")");

        boolean respawn = false;
        synchronized (this) {
            // Only flip to OFF if this is still the current process (i.e. it died
            // on its own rather than being replaced by a restart).
            if (process == p) {
                process = null;
                logThread = null;
                currentRelayUrl = null;
                // If we're still supposed to be running (stop() wasn't called),
                // schedule a respawn so transient deaths — the local USB/IP
                // server closing our TCP on detach, Android battery killing
                // the subprocess, etc — don't leave the user with a grey dot.
                if (shouldBeRunning) {
                    setState(State.DISCONNECTED);
                    respawn = true;
                } else {
                    setState(State.OFF);
                }
            }
        }
        if (respawn) {
            // Small delay so we don't spin if the subprocess fails immediately
            // (e.g. binary missing). Mirrors usbws's own internal retry cadence.
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                    synchronized (RelayController.this) {
                        // Bail if the caller has since stopped us, or another
                        // start has already raised a new process meanwhile.
                        if (!shouldBeRunning || process != null) return;
                    }
                    Log.i(TAG, "relay subprocess died — respawning");
                    start();
                }
            }, "usbws-respawn").start();
        }
    }

    /**
     * Maps each interesting usbws stdout line to a state transition. The
     * binary owns the actual reconnect; we only mirror its view to the UI.
     *
     * Strings we look for are the eprintln! lines in usbws/src/relay.rs and
     * usbws/src/main.rs — keep this in sync with that crate's log wording.
     */
    private void maybeUpdateConnState(String line) {
        State next = null;
        if (line.contains("relay connected") || line.contains("bridge ready")) {
            next = State.ON;
        } else if (line.contains("connect failed")
                || line.contains("relay unavailable")
                || line.contains("relay closed")
                || line.contains("handshake failed")
                || line.contains("handshake send failed")
                || line.contains("ws error")
                || line.contains("send failed")
                || line.contains("reconnecting")) {
            next = State.DISCONNECTED;
        }
        if (next == null) {
            return;
        }
        synchronized (this) {
            // Only push state changes from inside an active subprocess; if we
            // raced with stop() and the process is gone, ignore the line.
            if (process == null) return;
            if (state == next) return;
            setState(next);
        }
    }

    /**
     * Parse a "STAT tx=N rx=N" line from usbws stdout and forward the two
     * boolean flags to the TrafficListener on the UI thread. Robust to extra
     * whitespace or missing fields (assume "no traffic" if a field is absent).
     */
    private void handleStatLine(String line) {
        boolean tx = parseFlag(line, "tx=");
        boolean rx = parseFlag(line, "rx=");
        final TrafficListener l;
        synchronized (this) {
            l = trafficListener;
        }
        if (l == null) return;
        final boolean fTx = tx, fRx = rx;
        android.os.Handler h = new android.os.Handler(appContext.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                l.onTraffic(fTx, fRx);
            }
        });
    }

    private static boolean parseFlag(String line, String key) {
        int i = line.indexOf(key);
        if (i < 0) return false;
        int p = i + key.length();
        if (p >= line.length()) return false;
        char c = line.charAt(p);
        return c == '1';
    }

    /** Updates state and notifies the listener on the UI thread. */
    private void setState(State newState) {
        final StateListener l;
        synchronized (this) {
            state = newState;
            l = listener;
        }
        if (l != null) {
            // Marshal to the main thread for safe UI updates.
            android.os.Handler h = new android.os.Handler(appContext.getMainLooper());
            h.post(new Runnable() {
                @Override
                public void run() {
                    l.onRelayState(newState);
                }
            });
        }
    }
}
