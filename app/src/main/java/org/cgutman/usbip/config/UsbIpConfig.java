package org.cgutman.usbip.config;

import org.cgutman.usbip.relay.RelayController;
import org.cgutman.usbip.service.UsbIpService;
import org.cgutman.usbipserverforandroid.R;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

public class UsbIpConfig extends ComponentActivity {
	private Button serviceButton;
	private TextView serviceStatus;
	private TextView serviceReadyText;

	private Button relayToggle;
	private TextView relayStatus;
	private RelayController relayController;

	private TextView myInvite;
	private Button copyInviteButton;
	private EditText peerInviteEdit;
	private Button savePeerButton;

	private boolean running;

	private ActivityResultLauncher<String> requestPermissionLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
				// We don't actually care if the permission is granted or not. We will launch the service anyway.
				startService(new Intent(UsbIpConfig.this, UsbIpService.class));
			});
	
	private void updateStatus() {
		if (running) {
			serviceButton.setText("Stop Service");
			serviceStatus.setText("USB/IP Service Running");
			serviceReadyText.setText(R.string.ready_text);
		}
		else {
			serviceButton.setText("Start Service");
			serviceStatus.setText("USB/IP Service Stopped");
			serviceReadyText.setText("");
		}
	}
	
	// Elegant Stack Overflow solution to querying running services
	private boolean isMyServiceRunning(Class<?> serviceClass) {
	    ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
	    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
	        if (serviceClass.getName().equals(service.service.getClassName())) {
	            return true;
	        }
	    }
	    return false;
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_usbip_config);

		serviceButton = findViewById(R.id.serviceButton);
		serviceStatus = findViewById(R.id.serviceStatus);
		serviceReadyText = findViewById(R.id.serviceReadyText);
		relayToggle = findViewById(R.id.relay_toggle);
		relayStatus = findViewById(R.id.relayStatus);
		myInvite = findViewById(R.id.myInvite);
		copyInviteButton = findViewById(R.id.copyInviteButton);
		peerInviteEdit = findViewById(R.id.peerInviteEdit);
		savePeerButton = findViewById(R.id.savePeerButton);

		running = isMyServiceRunning(UsbIpService.class);

		updateStatus();

		serviceButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (running) {
					stopService(new Intent(UsbIpConfig.this, UsbIpService.class));
				}
				else {
					if (ContextCompat.checkSelfPermission(UsbIpConfig.this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
						startService(new Intent(UsbIpConfig.this, UsbIpService.class));
					} else {
						requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
					}
				}

				running = !running;
				updateStatus();
			}
		});

		// Relay: manually bridge the local USB/IP server through the encrypted relay.
		relayController = new RelayController(this);
		relayController.setStateListener(new RelayController.StateListener() {
			@Override
			public void onRelayState(RelayController.State state) {
				updateRelayStatus(state);
			}
		});
		updateRelayStatus(relayController.getState());

		relayToggle.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (relayController.isRunning()) {
					relayController.stop();
				} else {
					if (relayController.getPeerInvite().isEmpty()) {
						Toast.makeText(UsbIpConfig.this,
								"Set the peer invite first", Toast.LENGTH_LONG).show();
						return;
					}
					relayController.start();
				}
			}
		});

		// Load the saved peer invite into the edit field.
		peerInviteEdit.setText(relayController.getPeerInvite());

		// Generate (or load) this device's invite off the UI thread, then show it.
		myInvite.setText("(generating...)");
		final Handler ui = new Handler(getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final String invite = relayController.generateInvite();
				ui.post(new Runnable() {
					@Override
					public void run() {
						myInvite.setText(invite != null ? invite
								: "(failed to generate invite, see logcat tag wsusb)");
					}
				});
			}
		}, "usbws-keygen").start();

		copyInviteButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				String invite = myInvite.getText().toString();
				if (invite.startsWith("K0")) {
					ClipboardManager cm =
							(ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
					cm.setPrimaryClip(ClipData.newPlainText("usbws invite", invite));
					Toast.makeText(UsbIpConfig.this,
							"Invite copied", Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(UsbIpConfig.this,
							"Invite not ready yet", Toast.LENGTH_SHORT).show();
				}
			}
		});

		savePeerButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				String invite = peerInviteEdit.getText().toString().trim();
				if (invite.isEmpty() || invite.startsWith("K0")) {
					relayController.setPeerInvite(invite);
					Toast.makeText(UsbIpConfig.this,
							invite.isEmpty() ? "Peer invite cleared" : "Peer invite saved",
							Toast.LENGTH_SHORT).show();
				} else {
					Toast.makeText(UsbIpConfig.this,
							"That does not look like an invite (should start with K0)",
							Toast.LENGTH_LONG).show();
				}
			}
		});
	}

	private void updateRelayStatus(RelayController.State state) {
		switch (state) {
			case CONNECTING:
				relayStatus.setText("Relay: connecting...");
				relayToggle.setText("Stop Relay");
				break;
			case ON:
				relayStatus.setText("Relay: on");
				relayToggle.setText("Stop Relay");
				break;
			case ERROR:
				relayStatus.setText("Relay: error (see logcat tag wsusb)");
				relayToggle.setText("Relay (remote USB)");
				break;
			case OFF:
			default:
				relayStatus.setText("Relay: off");
				relayToggle.setText("Relay (remote USB)");
				break;
		}
	}
}
