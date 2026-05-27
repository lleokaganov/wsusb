package org.cgutman.usbip.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.cgutman.usbip.relay.RelayController;
import org.cgutman.usbip.relay.Updater;
import org.cgutman.usbip.service.UsbIpService;
import org.cgutman.usbipserverforandroid.R;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

/**
 * Settings + status screen for wsusb.
 *
 * Interaction model (modeled on serial-USB-terminal "capture"): the app detects
 * the plugged-in USB device, shows it, and auto-raises the tunnel. There are no
 * per-action confirmations. Whenever a USB device is attached, the on-phone
 * USB/IP server (cgutman's service) and the relay (accept-incoming mode) are
 * started automatically — no peer needs to be configured. The first remote
 * computer that connects knowing this device's invite becomes its owner. When
 * the device is unplugged, the relay is stopped. The single "Stop sharing"
 * button is a manual override.
 */
public class UsbIpConfig extends ComponentActivity {
	// DEVICE section.
	private TextView usbDeviceInfo;
	private TextView combinedStatus;
	private Button stopSharingButton;

	// Relay (encrypted tunnel) controller — drives libusbws.so.
	private RelayController relayController;

	// YOUR INVITE section.
	private TextView myInvite;
	private Button copyInviteButton;

	// OWNERS section.
	private TextView ownersList;
	private Button forgetOwnersButton;

	// APP section.
	private TextView appVersion;
	private Button updateButton;

	private UsbManager usbManager;

	// True while the on-phone USB/IP server (UsbIpService) is running.
	private boolean serviceRunning;

	// When we requested POST_NOTIFICATIONS to be able to show the FGS notification,
	// remember that an auto-raise is pending so we continue once the dialog returns.
	private boolean autoRaisePending;

	// Receiver for live USB attach/detach. Registered in onResume, removed in onPause.
	private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				// A device just appeared: refresh the line and try to raise the bridge.
				refreshUsbAndMaybeRaise();
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				// Nothing left to share once the (last) device is gone.
				refreshUsbState();
				if (firstSharableDevice() == null) {
					// Auto-stop the relay; the local server can stay up cheaply,
					// but with no device attached there is nothing to import, so we
					// tear it down too for a clean idle state.
					if (relayController.isRunning()
							|| relayController.getState() == RelayController.State.CONNECTING) {
						relayController.stop();
					}
					stopServiceIfRunning();
				}
				updateUi();
			}
		}
	};

	// POST_NOTIFICATIONS is needed for the foreground-service notification. We do
	// not block on the result; we (re)try the raise once the dialog returns.
	private final ActivityResultLauncher<String> requestPermissionLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
				// Regardless of the answer, proceed with the pending auto-raise.
				if (autoRaisePending) {
					autoRaisePending = false;
					startServiceNow();
					startRelayNow();
					updateUi();
				}
			});

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_usbip_config);

		usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

		usbDeviceInfo = findViewById(R.id.usbDeviceInfo);
		combinedStatus = findViewById(R.id.combinedStatus);
		stopSharingButton = findViewById(R.id.stopSharingButton);
		myInvite = findViewById(R.id.myInvite);
		copyInviteButton = findViewById(R.id.copyInviteButton);
		ownersList = findViewById(R.id.ownersList);
		forgetOwnersButton = findViewById(R.id.forgetOwnersButton);
		appVersion = findViewById(R.id.appVersion);
		updateButton = findViewById(R.id.updateButton);

		serviceRunning = isMyServiceRunning(UsbIpService.class);

		// Relay controller drives the encrypted tunnel and reports state changes.
		relayController = new RelayController(this);
		relayController.setStateListener(new RelayController.StateListener() {
			@Override
			public void onRelayState(RelayController.State state) {
				updateUi();
			}
		});

		// Manual override: stop everything we are sharing.
		stopSharingButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				if (relayController.isRunning()
						|| relayController.getState() == RelayController.State.CONNECTING) {
					relayController.stop();
				}
				stopServiceIfRunning();
				updateUi();
			}
		});

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

		forgetOwnersButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				new AlertDialog.Builder(UsbIpConfig.this)
						.setTitle(R.string.forget_owners_confirm_title)
						.setMessage(R.string.forget_owners_confirm_message)
						.setPositiveButton(R.string.forget_owners, new DialogInterface.OnClickListener() {
							@Override
							public void onClick(DialogInterface dialog, int which) {
								boolean ok = relayController.clearAuthorized();
								Toast.makeText(UsbIpConfig.this,
										ok ? R.string.forget_owners_done : R.string.forget_owners_failed,
										Toast.LENGTH_LONG).show();
								refreshOwners();
							}
						})
						.setNegativeButton(R.string.cancel, null)
						.show();
			}
		});

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

		// App section: show the installed version and offer an in-app self-update.
		int versionCode = Updater.getInstalledVersionCode(this);
		String versionName = Updater.getInstalledVersionName(this);
		appVersion.setText(getString(R.string.app_version_label)
				+ " " + versionCode + " (" + versionName + ")");

		updateButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Updater.checkForUpdate(UsbIpConfig.this);
			}
		});
	}

	@Override
	protected void onResume() {
		super.onResume();

		// Register for live USB attach/detach while visible.
		IntentFilter filter = new IntentFilter();
		filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED);
		} else {
			registerReceiver(usbReceiver, filter);
		}

		// Pick up the current service state (it may have been stopped externally).
		serviceRunning = isMyServiceRunning(UsbIpService.class);

		// Reload the owners list each time the screen becomes visible; a new owner
		// may have been recorded by usbws since we were last shown.
		refreshOwners();

		// On launch (including being launched by USB_DEVICE_ATTACHED), raise the
		// bridge if a device is already plugged in (accept mode needs no peer).
		refreshUsbAndMaybeRaise();
	}

	@Override
	protected void onPause() {
		super.onPause();
		try {
			unregisterReceiver(usbReceiver);
		} catch (IllegalArgumentException ignored) {
			// Not registered (e.g. onPause without a matching onResume) — ignore.
		}
	}

	// ===================== USB enumeration / auto-raise =====================

	/** Returns the first attached non-hub USB device, or null if none. */
	private UsbDevice firstSharableDevice() {
		for (UsbDevice dev : usbManager.getDeviceList().values()) {
			if (dev.getDeviceClass() != UsbConstants.USB_CLASS_HUB) {
				return dev;
			}
		}
		// Fall back to any device (even a hub) so we never report "none" when the
		// list is non-empty; the first non-hub is preferred above.
		for (UsbDevice dev : usbManager.getDeviceList().values()) {
			return dev;
		}
		return null;
	}

	/** Refreshes the USB line, then attempts an auto-raise, then redraws the UI. */
	private void refreshUsbAndMaybeRaise() {
		refreshUsbState();
		maybeAutoRaise();
		updateUi();
	}

	/** Pulls the latest service-running flag (USB line itself is read live). */
	private void refreshUsbState() {
		serviceRunning = isMyServiceRunning(UsbIpService.class);
	}

	/**
	 * Core auto-flow: if a USB device is attached, start the USB/IP service and the
	 * relay (accept-incoming mode) with no confirmation. No peer is required — the
	 * first remote computer that connects with our invite becomes the owner. Does
	 * nothing if no device is present or if the relay is already up.
	 */
	private void maybeAutoRaise() {
		UsbDevice dev = firstSharableDevice();
		if (dev == null) {
			return; // No device — nothing to share.
		}
		if (relayController.isRunning()
				|| relayController.getState() == RelayController.State.CONNECTING) {
			return; // Already raising / raised.
		}

		// Ensure the FGS notification permission so the service can run foregrounded.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
				&& ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
						!= PackageManager.PERMISSION_GRANTED) {
			// Request once; the launcher callback resumes the raise afterwards.
			autoRaisePending = true;
			requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
			return;
		}

		startServiceNow();
		startRelayNow();
	}

	private void startServiceNow() {
		if (!serviceRunning) {
			startService(new Intent(this, UsbIpService.class));
			serviceRunning = true;
		}
	}

	/** Starts the relay in accept-incoming mode unless it is already up. */
	private void startRelayNow() {
		if (!relayController.isRunning()
				&& relayController.getState() != RelayController.State.CONNECTING) {
			relayController.start();
		}
	}

	private void stopServiceIfRunning() {
		if (serviceRunning) {
			stopService(new Intent(this, UsbIpService.class));
			serviceRunning = false;
		}
	}

	// ========================= status formatting =========================

	/** Short human label for a device: product name if known, else "USB device". */
	private static String deviceLabel(UsbDevice dev) {
		String name = null;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			name = dev.getProductName();
		}
		if (name == null || name.trim().isEmpty()) {
			name = "USB device";
		}
		return name.trim();
	}

	/** "vid:pid" in lowercase hex, e.g. "1a86:7523". */
	private static String vidPid(UsbDevice dev) {
		return String.format(Locale.US, "%04x:%04x", dev.getVendorId(), dev.getProductId());
	}

	/** Builds the full technical USB line for one device. */
	private String describeDevice(UsbDevice dev) {
		StringBuilder sb = new StringBuilder();
		sb.append(deviceLabel(dev));
		sb.append("  ").append(vidPid(dev));
		sb.append("  class ").append(String.format(Locale.US, "0x%02x", dev.getDeviceClass()));
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			String serial = null;
			try {
				serial = dev.getSerialNumber();
			} catch (SecurityException ignored) {
				// Reading the serial can require permission; tolerate denial.
			}
			if (serial != null && !serial.trim().isEmpty()) {
				sb.append("  sn ").append(serial.trim());
			}
		}
		return sb.toString();
	}

	/** Repaints the USB line, the combined status, and the Stop button. */
	private void updateUi() {
		List<UsbDevice> devices = new ArrayList<>(usbManager.getDeviceList().values());

		// ---- Top line: what is attached ----
		if (devices.isEmpty()) {
			usbDeviceInfo.setText(R.string.usb_none);
			usbDeviceInfo.setTextColor(ContextCompat.getColor(this, R.color.status_idle));
		} else {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < devices.size(); i++) {
				if (i > 0) {
					sb.append('\n');
				}
				sb.append(describeDevice(devices.get(i)));
			}
			usbDeviceInfo.setText(sb.toString());
			usbDeviceInfo.setTextColor(ContextCompat.getColor(this, R.color.status_ok));
		}

		// ---- Combined sharing status + Stop button visibility ----
		UsbDevice sharable = firstSharableDevice();
		RelayController.State relayState = relayController.getState();
		boolean relayActive = relayController.isRunning()
				|| relayState == RelayController.State.CONNECTING;

		if (sharable == null) {
			// No device attached.
			combinedStatus.setText(R.string.status_idle_no_device);
			combinedStatus.setTextColor(ContextCompat.getColor(this, R.color.status_idle));
		} else if (!relayActive) {
			// Device present but the relay is not up yet (e.g. permission pending):
			// it will be raised automatically, so show a waiting hint.
			combinedStatus.setText(R.string.status_waiting_owner);
			combinedStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn));
		} else {
			// Device + active relay: report the live relay state inline.
			String relayWord;
			int color;
			switch (relayState) {
				case ON:
					relayWord = getString(R.string.relay_connected);
					color = R.color.status_ok;
					break;
				case CONNECTING:
					relayWord = getString(R.string.relay_connecting);
					color = R.color.status_warn;
					break;
				case ERROR:
					relayWord = getString(R.string.relay_error);
					color = R.color.status_warn;
					break;
				case OFF:
				default:
					relayWord = getString(R.string.relay_off);
					color = R.color.status_idle;
					break;
			}
			String text = "Sharing: " + deviceLabel(sharable) + " "
					+ vidPid(sharable) + " → " + relayWord;
			combinedStatus.setText(text);
			combinedStatus.setTextColor(ContextCompat.getColor(this, color));
		}

		// Stop button is a manual override: only shown while something is active.
		boolean active = relayActive || serviceRunning;
		stopSharingButton.setVisibility(active ? View.VISIBLE : View.GONE);
	}

	/**
	 * Reloads the OWNERS list from the usbws authorized table. Shows a friendly
	 * "no owners yet" line when the table is empty/missing (TOFU still active),
	 * otherwise one "shortKey  nick" line per authorized owner.
	 */
	private void refreshOwners() {
		List<RelayController.Owner> owners = relayController.getOwners();
		if (owners.isEmpty()) {
			ownersList.setText(R.string.owners_none);
			ownersList.setTextColor(ContextCompat.getColor(this, R.color.helper_text));
			forgetOwnersButton.setVisibility(View.GONE);
			return;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < owners.size(); i++) {
			if (i > 0) {
				sb.append('\n');
			}
			RelayController.Owner o = owners.get(i);
			sb.append(o.shortKey());
			if (o.nick != null && !o.nick.isEmpty()) {
				sb.append("  ").append(o.nick);
			}
		}
		ownersList.setText(sb.toString());
		ownersList.setTextColor(ContextCompat.getColor(this, R.color.status_ok));
		forgetOwnersButton.setVisibility(View.VISIBLE);
	}

	// Elegant Stack Overflow solution to querying running services.
	private boolean isMyServiceRunning(Class<?> serviceClass) {
		ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
			if (serviceClass.getName().equals(service.service.getClassName())) {
				return true;
			}
		}
		return false;
	}
}
