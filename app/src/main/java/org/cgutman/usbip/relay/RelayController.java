package org.cgutman.usbip.relay;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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

    /** Relay lifecycle state for the UI. */
    public enum State {
        OFF,
        CONNECTING,
        ON,
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

    /** One authorized owner parsed from the authorized table. */
    public static final class Owner {
        /** Full x_pub hex (64 chars). */
        public final String xPubHex;
        /** Optional nick (may be empty). */
        public final String nick;

        Owner(String xPubHex, String nick) {
            this.xPubHex = xPubHex;
            this.nick = nick;
        }

        /** First 8 + last 4 hex chars, for a compact UI line. */
        public String shortKey() {
            if (xPubHex.length() <= 12) {
                return xPubHex;
            }
            return xPubHex.substring(0, 8) + "…" + xPubHex.substring(xPubHex.length() - 4);
        }
    }

    /**
     * Reads the authorized-owners table written by usbws. Returns the parsed
     * owners (possibly empty) — an empty/missing file means trust-on-first-use
     * is still active and no owner has connected yet.
     */
    public List<Owner> getOwners() {
        List<Owner> owners = new ArrayList<>();
        File file = authorizedFile();
        if (!file.exists()) {
            return owners;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                // "<hex64> [nick...]" — split off the first whitespace-delimited token.
                int sp = indexOfWhitespace(line);
                String hex;
                String nick;
                if (sp < 0) {
                    hex = line;
                    nick = "";
                } else {
                    hex = line.substring(0, sp);
                    nick = line.substring(sp).trim();
                }
                owners.add(new Owner(hex, nick));
            }
        } catch (IOException e) {
            Log.w(TAG, "failed to read authorized table", e);
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (IOException ignored) {
                }
            }
        }
        return owners;
    }

    /**
     * Deletes the authorized-owners table so TOFU re-arms on the next connect.
     * Use when a previously-trusted owner has rotated keys (e.g. their identity
     * file was regenerated) and the gate now silently refuses them. Returns true
     * if the file was deleted or did not exist; false on a real I/O failure.
     */
    public boolean clearAuthorized() {
        File file = authorizedFile();
        if (!file.exists()) {
            return true;
        }
        boolean ok = file.delete();
        if (!ok) {
            Log.w(TAG, "failed to delete authorized table at " + file.getAbsolutePath());
        }
        return ok;
    }

    /** Index of the first whitespace char in s, or -1 if none. */
    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Absolute path to the per-device identity file in filesDir. */
    private File identityFile() {
        return new File(appContext.getFilesDir(), IDENTITY_FILE);
    }

    /** Absolute path to the authorized-owners table in filesDir. */
    private File authorizedFile() {
        return new File(appContext.getFilesDir(), AUTHORIZED_FILE);
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
            pb.environment().put("USBWS_IDENTITY", identity.getAbsolutePath());
            pb.redirectErrorStream(true);

            Log.i(TAG, "starting relay: " + binaryPath + " tcp-connect "
                    + LOCAL_ENDPOINT + " --accept (identity=" + identity.getAbsolutePath() + ")");

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
                setState(State.ON);
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

        synchronized (this) {
            // Only flip to OFF if this is still the current process (i.e. it died
            // on its own rather than being replaced by a restart).
            if (process == p) {
                process = null;
                logThread = null;
                setState(State.OFF);
            }
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
