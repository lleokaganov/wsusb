package me.lleo.wsusb.config;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.core.content.ContextCompat;

import me.lleo.wsusb.R;
import me.lleo.wsusb.WsusbApp;
import me.lleo.wsusb.relay.RelayController;
import me.lleo.wsusb.relay.Updater;

/**
 * Settings + About screen.
 *
 * Mandated layout (top to bottom):
 *   1. BRAND block (English) — Leo's intro + contacts. Engineers know whose
 *      app this is and how to reach him.
 *   2. SERVER section — single editable WS URL. Known hosts are auto-bound to
 *      the correct relay public keys (see {@link Settings#lookupServerKeys}).
 *   3. APP section — the Check-for-updates button (moved off the main screen).
 *   4. ABOUT section — what the app does, and the installed version. The
 *      version line lives here, NOT on the main screen.
 */
public class SettingsActivity extends ComponentActivity {

    private Settings settings;

    private EditText serverUrlInput;
    private TextView serverUrlNote;
    private Button serverSaveButton;
    private Button resetIdentityButton;
    private Button updateButton;
    private TextView appVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);

        settings = new Settings(this);

        serverUrlInput = findViewById(R.id.serverUrlInput);
        serverUrlNote = findViewById(R.id.serverUrlNote);
        serverSaveButton = findViewById(R.id.serverSaveButton);
        resetIdentityButton = findViewById(R.id.resetIdentityButton);
        updateButton = findViewById(R.id.updateButton);
        appVersion = findViewById(R.id.appVersion);

        serverUrlInput.setText(settings.getRelayUrl());
        refreshKeysNote(settings.getRelayUrl());

        // Re-check the "known host?" hint as the user edits the URL so the
        // warning about custom hosts appears immediately.
        serverUrlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                refreshKeysNote(s.toString());
            }
        });

        serverSaveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = serverUrlInput.getText().toString().trim();
                if (!Settings.isValidRelayUrl(url)) {
                    Toast.makeText(SettingsActivity.this,
                            R.string.server_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                String prev = settings.getRelayUrl();
                settings.setRelayUrl(url);
                refreshKeysNote(url);
                // Subprocess only reads env on (re)start, so applying the URL
                // change means stopping and starting the relay. Otherwise the
                // user would have to Stop sharing → re-plug by hand and the
                // green dot would still report the OLD server until they did.
                RelayController rc = ((WsusbApp) getApplication()).getRelayController();
                if (rc.isRunning() && !url.equals(prev)) {
                    rc.restartIfRunning();
                    Toast.makeText(SettingsActivity.this,
                            R.string.server_applied, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(SettingsActivity.this,
                            R.string.server_saved, Toast.LENGTH_LONG).show();
                }
            }
        });

        resetIdentityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.reset_identity_confirm_title)
                        .setMessage(R.string.reset_identity_confirm_message)
                        .setPositiveButton(R.string.reset_identity,
                                new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                RelayController rc =
                                        ((WsusbApp) getApplication()).getRelayController();
                                boolean ok = rc.resetIdentity();
                                Toast.makeText(SettingsActivity.this,
                                        ok ? R.string.reset_identity_done
                                           : R.string.reset_identity_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Updater.checkForUpdate(SettingsActivity.this);
            }
        });

        int versionCode = Updater.getInstalledVersionCode(this);
        String versionName = Updater.getInstalledVersionName(this);
        appVersion.setText(getString(R.string.app_version_label)
                + " " + versionCode + " (" + versionName + ")");
    }

    /**
     * Show the "custom host — built-in keys" warning iff the URL host is not
     * in our KNOWN_HOSTS table. Hidden otherwise.
     */
    private void refreshKeysNote(String url) {
        String[] keys = Settings.lookupServerKeys(url);
        if (keys == null && Settings.isValidRelayUrl(url)) {
            serverUrlNote.setText(R.string.server_unknown_keys);
            serverUrlNote.setTextColor(ContextCompat.getColor(this, R.color.status_warn));
            serverUrlNote.setVisibility(View.VISIBLE);
        } else {
            serverUrlNote.setVisibility(View.GONE);
        }
    }
}
