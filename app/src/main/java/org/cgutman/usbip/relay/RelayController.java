package org.cgutman.usbip.relay;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Drives the bundled usbws native binary (libusbws.so) to bridge the local
 * USB/IP server (TCP 127.0.0.1:3240) through the encrypted relay.
 *
 * The binary is shipped inside jniLibs/arm64-v8a and is extracted to
 * nativeLibraryDir at install time (extractNativeLibs=true), which is the only
 * place from which Android allows executing a process.
 *
 * START spawns:  libusbws.so tcp-connect 127.0.0.1:3240 --peer DEV_INVITE
 * with env USBWS_IDENTITY pointing at a per-app copy of the bundled identity.
 */
public class RelayController {
    private static final String TAG = "wsusb";

    // Native binary name as packaged in jniLibs (must keep the lib*.so shape so
    // the build system bundles it and Android extracts it).
    private static final String BINARY_NAME = "libusbws.so";

    // Asset shipped in app/src/main/assets, copied to filesDir on first use.
    private static final String IDENTITY_ASSET = "usbws_identity";

    // Local TCP endpoint of the USB/IP server (UsbIpServer.PORT == 3240).
    private static final String LOCAL_ENDPOINT = "127.0.0.1:3240";

    // Fixed dev peer invite (relay URL ws://ws.lleo.me/api0 is baked into usbws).
    public static final String DEV_INVITE =
            "K0O2cik71n0Fox64QYG68RYKVnaGIDqhYLd7uqhr9bqBsA7v3fGUsKXvnNyM3K3Q9oP5P2kkCteaoh6vkTirVvlnVzYndz";

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

    /**
     * Starts the relay process. Returns immediately; actual startup happens on a
     * background thread and is reported via the StateListener.
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
            File identity = ensureIdentity();
            String binaryPath = appContext.getApplicationInfo().nativeLibraryDir
                    + File.separator + BINARY_NAME;

            ProcessBuilder pb = new ProcessBuilder(
                    binaryPath,
                    "tcp-connect",
                    LOCAL_ENDPOINT,
                    "--peer", DEV_INVITE);
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

    /**
     * Copies the bundled usbws identity from assets to filesDir on first use.
     * Returns the on-disk identity file.
     */
    private File ensureIdentity() throws IOException {
        File dest = new File(appContext.getFilesDir(), IDENTITY_ASSET);
        if (dest.exists() && dest.length() > 0) {
            return dest;
        }

        InputStream in = null;
        OutputStream out = null;
        try {
            in = appContext.getAssets().open(IDENTITY_ASSET);
            out = new FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }

        Log.i(TAG, "copied usbws identity to " + dest.getAbsolutePath());
        return dest;
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
