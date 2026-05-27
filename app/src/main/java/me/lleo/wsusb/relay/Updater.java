package me.lleo.wsusb.relay;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Native in-app self-update for wsusb.
 *
 * Flow:
 *   1. {@link #checkForUpdate(Activity)} fetches version.txt over HTTPS on a
 *      background thread, parses the latest versionCode (first line), and
 *      compares it against the installed versionCode.
 *   2. If the server is newer, the user is prompted; on confirm the latest APK
 *      is downloaded via {@link DownloadManager} into the app's external files
 *      dir, and the package installer is launched via a content:// Uri exposed
 *      through {@link FileProvider}.
 *   3. If already up to date, a toast is shown.
 *
 * The APK / version files are hosted under our standard convention. Update only
 * works once those files are actually deployed to the server.
 */
public class Updater {
    private static final String TAG = "wsusb";

    // Server endpoints (hardcoded by convention; manager deploys these files).
    // Stable public host with static IP — lleo.me/apk/<app>/. Never point at
    // qlleo.lleo.me (dev box, dynamic IP) or raw IPs.
    private static final String VERSION_URL = "https://lleo.me/apk/wsusb/version.txt";
    private static final String APK_URL = "https://lleo.me/apk/wsusb/lleo.wsusb.apk";

    // Local filename for the downloaded APK (kept stable so we overwrite, not pile up).
    private static final String APK_FILE_NAME = "lleo.wsusb.apk";

    private static final int HTTP_TIMEOUT_MS = 15000;

    private Updater() {
        // Utility class; no instances.
    }

    /** The installed versionCode of this app. Returns -1 on failure. */
    public static int getInstalledVersionCode(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not read own package info", e);
            return -1;
        }
    }

    /** The installed versionName of this app. Returns "?" on failure. */
    public static String getInstalledVersionName(Context ctx) {
        try {
            String name = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            return name != null ? name : "?";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /**
     * Checks the server for a newer version. Network I/O runs on a background
     * thread; all UI interaction is posted back to the main thread.
     */
    public static void checkForUpdate(final Activity activity) {
        final Context appContext = activity.getApplicationContext();
        final int installed = getInstalledVersionCode(appContext);
        final Handler ui = new Handler(activity.getMainLooper());

        Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                final RemoteVersion remote = fetchRemoteVersion();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) {
                            return;
                        }
                        if (remote == null) {
                            Toast.makeText(activity,
                                    "Update check failed (network or server error)",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (remote.versionCode > installed) {
                            promptDownload(activity, installed, remote);
                        } else {
                            Toast.makeText(activity,
                                    "You have the latest version (v" + installed + ")",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "wsusb-update-check").start();
    }

    /** Parsed contents of version.txt. */
    private static final class RemoteVersion {
        final int versionCode;
        final String versionName; // may be null if not provided

        RemoteVersion(int versionCode, String versionName) {
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }

    /** Fetches and parses version.txt. Returns null on any error. */
    private static RemoteVersion fetchRemoteVersion() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(VERSION_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Cache-Control", "no-cache");

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "version.txt returned HTTP " + code);
                return null;
            }

            try (InputStream in = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
                String firstLine = reader.readLine();
                String secondLine = reader.readLine();
                if (firstLine == null) {
                    return null;
                }
                int remoteCode = Integer.parseInt(firstLine.trim());
                String remoteName = (secondLine != null) ? secondLine.trim() : null;
                return new RemoteVersion(remoteCode, remoteName);
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "version.txt did not start with an integer versionCode", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch version.txt", e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Shows an "update available" dialog and starts the download on confirm. */
    private static void promptDownload(final Activity activity,
                                       int installed, final RemoteVersion remote) {
        String toVersion = (remote.versionName != null)
                ? ("v" + remote.versionCode + " (" + remote.versionName + ")")
                : ("v" + remote.versionCode);
        String message = "Update available: v" + installed + " → " + toVersion
                + ".\n\nDownload and install now?";

        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(message)
                .setPositiveButton("Download", (dialog, which) -> ensureInstallPermission(activity))
                .setNegativeButton("Later", null)
                .show();
    }

    /**
     * On Android O+ the user must grant "install unknown apps" to this app.
     * If it is not yet granted, send them to the system setting; otherwise start
     * the download directly.
     */
    private static void ensureInstallPermission(final Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Permission needed")
                    .setMessage("To install the update, allow this app to install "
                            + "unknown apps, then tap Update again.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        try {
                            activity.startActivity(intent);
                        } catch (Exception e) {
                            // Fallback to the global setting if the per-app one is unavailable.
                            activity.startActivity(
                                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        startDownload(activity);
    }

    /** Downloads the latest APK via DownloadManager and installs it when done. */
    private static void startDownload(final Activity activity) {
        final Context appContext = activity.getApplicationContext();

        // Target a stable path in the app's external files dir so FileProvider can
        // expose it and it survives without WRITE_EXTERNAL_STORAGE on modern API.
        File downloadDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir == null) {
            Toast.makeText(activity, "No storage available for download",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final File apkFile = new File(downloadDir, APK_FILE_NAME);
        // Remove a stale copy so DownloadManager writes a fresh APK.
        if (apkFile.exists() && !apkFile.delete()) {
            Log.w(TAG, "Could not delete old APK at " + apkFile.getAbsolutePath());
        }

        DownloadManager dm =
                (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(activity, "DownloadManager unavailable", Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(APK_URL));
        request.setTitle("wsusb update");
        request.setDescription("Downloading the latest wsusb APK");
        request.setDestinationUri(Uri.fromFile(apkFile));
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setMimeType("application/vnd.android.package-archive");

        final long downloadId;
        try {
            downloadId = dm.enqueue(request);
        } catch (Exception e) {
            Log.e(TAG, "Failed to enqueue download", e);
            Toast.makeText(activity, "Could not start download", Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show();

        // Listen for completion, then launch the installer.
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) {
                    return;
                }
                try {
                    appContext.unregisterReceiver(this);
                } catch (IllegalArgumentException ignored) {
                    // Already unregistered; ignore.
                }

                if (isDownloadSuccessful(dm, downloadId)) {
                    installApk(activity, apkFile);
                } else {
                    Toast.makeText(activity, "Download failed", Toast.LENGTH_LONG).show();
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(receiver, filter);
        }
    }

    /** Queries DownloadManager for the final status of a completed download. */
    private static boolean isDownloadSuccessful(DownloadManager dm, long downloadId) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
        try (Cursor cursor = dm.query(query)) {
            if (cursor != null && cursor.moveToFirst()) {
                int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (statusIndex >= 0) {
                    return cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to query download status", e);
        }
        return false;
    }

    /** Launches the system package installer for the downloaded APK. */
    private static void installApk(Activity activity, File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apkFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch installer", e);
            Toast.makeText(activity, "Could not launch installer", Toast.LENGTH_LONG).show();
        }
    }
}
