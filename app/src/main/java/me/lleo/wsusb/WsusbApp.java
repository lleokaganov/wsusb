package me.lleo.wsusb;

import android.app.Application;

import me.lleo.wsusb.relay.RelayController;

/**
 * Application-scoped singletons.
 *
 * The {@link RelayController} owns a long-lived child process (libusbws.so) and
 * a couple of background threads. If we recreated it per Activity, every
 * orientation change / activity restart would leak the running process and
 * spawn a parallel one — they would all try to share a single identity on the
 * relay, fail the handshake (only one client per identity is allowed), and
 * flood logcat with retries. Pinning the controller to the Application makes
 * it survive Activity lifecycle events, so there is always at most one
 * libusbws.so child.
 */
public class WsusbApp extends Application {

    private RelayController relayController;

    @Override
    public void onCreate() {
        super.onCreate();
        relayController = new RelayController(this);
    }

    /** Process-wide singleton. */
    public RelayController getRelayController() {
        return relayController;
    }
}
