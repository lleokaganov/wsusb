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

    private final Context appContext;

    // Guarded by 'this'.
    private Process process;
    private Thread logThread;
    private State state = State.OFF;
    private StateListener listener;

    public RelayController(Context context) {
        // Hold the application context to avoid leaking the Activity.
        this.appContext = context.getApplicationContext();
    }

    public synchronized void setStateListener(StateListener l) {
        this.listener = l;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized boolean isRunning() {
        return process != null;
    }

    /** Returns the saved peer invite, or an empty string if none is set. */
    public String getPeerInvite() {
        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_PEER_INVITE, "");
    }

    /** Persists the peer invite to connect to. */
    public void setPeerInvite(String invite) {
        SharedPreferences prefs =
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_PEER_INVITE, invite == null ? "" : invite.trim()).apply();
    }

    /** Absolute path to the per-device identity file in filesDir. */
    private File identityFile() {
        return new File(appContext.getFilesDir(), IDENTITY_FILE);
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
     * Starts the relay process. Returns immediately; actual startup happens on a
     * background thread and is reported via the StateListener.
     *
     * Does nothing (logs and reports ERROR) if no peer invite is configured.
     */
    public synchronized void start() {
        if (process != null) {
            // Already running.
            return;
        }

        final String peerInvite = getPeerInvite();
        if (peerInvite == null || peerInvite.isEmpty()) {
            Log.w(TAG, "cannot start relay: peer invite not set");
            setState(State.ERROR);
            return;
        }

        setState(State.CONNECTING);

        Thread starter = new Thread(new Runnable() {
            @Override
            public void run() {
                startInternal(peerInvite);
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

    private void startInternal(String peerInvite) {
        try {
            File identity = identityFile();
            String binaryPath = binaryPath();

            ProcessBuilder pb = new ProcessBuilder(
                    binaryPath,
                    "tcp-connect",
                    LOCAL_ENDPOINT,
                    "--peer", peerInvite);
            // usbws load-or-creates this identity, so it exists after the first run.
            pb.environment().put("USBWS_IDENTITY", identity.getAbsolutePath());
            pb.redirectErrorStream(true);

            Log.i(TAG, "starting relay: " + binaryPath + " tcp-connect "
                    + LOCAL_ENDPOINT + " (identity=" + identity.getAbsolutePath() + ")");

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
                Log.i(TAG, line);
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
