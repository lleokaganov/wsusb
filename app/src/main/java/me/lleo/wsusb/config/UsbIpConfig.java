package me.lleo.wsusb.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.lleo.wsusb.WsusbApp;
import me.lleo.wsusb.relay.RelayController;
import me.lleo.wsusb.service.UsbIpService;
import me.lleo.wsusb.R;

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
import android.net.Uri;
import android.os.PowerManager;
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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
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
	private Button stopSharingButton;

	// Relay (encrypted tunnel) controller — drives libusbws.so.
	private RelayController relayController;

	// YOUR INVITE section.
	private TextView myInvite;
	private Button copyInviteButton;

	// OWNERS section.
	private TextView ownersList;
	private Button forgetOwnersButton;

	// Traffic dots (TX/RX) — blinked by RelayController.TrafficListener on each
	// "STAT tx=N rx=N" stdout line from usbws.
	private View dotTx;
	private View dotRx;
	// alpha used for "idle" (dot present but very dim) — keeps the layout from
	// jumping when the dot lights up and matches the layout XML's default.
	private static final float DOT_IDLE_ALPHA = 0.15f;

	// Prominent server-connection indicator (large dot + label) at top of screen.
	private View connDot;
	private TextView connStatus;

	// Settings activity launcher.
	private Button settingsButton;

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
				// Sensory feedback: one short blip + short vibration. Phone-in-
				// pocket workflow — user knows the device was seen without
				// looking at the screen.
				feedbackAttach();
				// A device just appeared: refresh the line and try to raise the bridge.
				refreshUsbAndMaybeRaise();
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				feedbackDetach();
				// The relay stays up while the app is open — the phone is on
				// the line independent of any USB device. Detach just leaves
				// "no devices to enumerate" for the remote side; the WS link
				// itself is not torn down. (Stop sharing still tears everything
				// down explicitly when the user wants that.)
				refreshUsbState();
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
		stopSharingButton = findViewById(R.id.stopSharingButton);
		myInvite = findViewById(R.id.myInvite);
		copyInviteButton = findViewById(R.id.copyInviteButton);
		ownersList = findViewById(R.id.ownersList);
		forgetOwnersButton = findViewById(R.id.forgetOwnersButton);
		dotTx = findViewById(R.id.dotTx);
		dotRx = findViewById(R.id.dotRx);
		connDot = findViewById(R.id.connDot);
		connStatus = findViewById(R.id.connStatus);
		settingsButton = findViewById(R.id.settingsButton);

		serviceRunning = isMyServiceRunning(UsbIpService.class);

		// Relay controller is an Application-scoped singleton: surviving Activity
		// recreate (rotation, restore from background) keeps the libusbws child
		// process unique — multiple instances would all fight for one relay
		// identity and fail handshakes.
		relayController = ((WsusbApp) getApplication()).getRelayController();
		relayController.setStateListener(new RelayController.StateListener() {
			@Override
			public void onRelayState(RelayController.State state) {
				updateUi();
			}
		});
		relayController.setTrafficListener(new RelayController.TrafficListener() {
			@Override
			public void onTraffic(boolean tx, boolean rx) {
				blinkDot(dotTx, tx);
				blinkDot(dotRx, rx);
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

		settingsButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(UsbIpConfig.this, SettingsActivity.class));
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

		// Force a USB permission dialog for every attached device we don't yet
		// own. Without this, after a package reinstall Android no longer pops
		// the "Allow access" dialog automatically — even though our intent-
		// filter + device_filter.xml are correct — and UsbIpService gets stuck
		// inside attachToDevice() forever waiting on permission. Calling
		// requestPermission() explicitly here forces the dialog every time the
		// user opens the app, so they can grant and we can proceed.
		ensureUsbPermission();

		// One-shot prompt: ask the user to whitelist us from battery
		// optimizations so the WS link stays alive while the screen is off.
		// Skipped silently if already whitelisted or if the user dismissed it.
		maybePromptBatteryWhitelist();
	}

	/**
	 * Show the "ignore battery optimizations" prompt at most once per install
	 * (or until the user grants it). Doze + per-app battery restrictions are
	 * the main reason the relay subprocess loses CPU and the WS link drops in
	 * the background; whitelisting wsusb keeps the foreground service genuinely
	 * unrestricted.
	 */
	private void maybePromptBatteryWhitelist() {
		// REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + isIgnoringBatteryOptimizations
		// arrived in API 23 (Doze itself was a no-op below that).
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
		Settings s = new Settings(this);
		if (s.isBatteryPromptDone()) return;
		PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
		if (pm == null) return;
		if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
			s.markBatteryPromptDone();
			return;
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.battery_opt_title)
				.setMessage(R.string.battery_opt_message)
				.setPositiveButton(R.string.battery_opt_continue,
						new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						try {
							Intent i = new Intent(
									android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
									Uri.parse("package:" + getPackageName()));
							startActivity(i);
						} catch (Exception e) {
							// Fall back to the global battery-optimizations list.
							try {
								startActivity(new Intent(
										android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
							} catch (Exception ignored) {
							}
						}
						new Settings(UsbIpConfig.this).markBatteryPromptDone();
					}
				})
				.setNegativeButton(R.string.battery_opt_skip,
						new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						new Settings(UsbIpConfig.this).markBatteryPromptDone();
					}
				})
				.show();
	}

	/** PendingIntent used to receive USB permission grant results. */
	private android.app.PendingIntent permissionPendingIntent() {
		Intent i = new Intent("me.lleo.wsusb.USB_PERMISSION");
		i.setPackage(getPackageName());
		int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			flags |= android.app.PendingIntent.FLAG_MUTABLE;
		}
		return android.app.PendingIntent.getBroadcast(this, 0, i, flags);
	}

	/** For each non-hub attached USB device that we don't have permission for,
	 *  call requestPermission() so Android shows the grant dialog. */
	private void ensureUsbPermission() {
		android.app.PendingIntent pi = permissionPendingIntent();
		for (UsbDevice dev : usbManager.getDeviceList().values()) {
			if (dev.getDeviceClass() == UsbConstants.USB_CLASS_HUB) continue;
			if (!usbManager.hasPermission(dev)) {
				usbManager.requestPermission(dev, pi);
			}
		}
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

	/** Refreshes the USB line, ensures the relay is up, then redraws the UI. */
	private void refreshUsbAndMaybeRaise() {
		refreshUsbState();
		ensureRelayUp();
		updateUi();
	}

	/** Pulls the latest service-running flag (USB line itself is read live). */
	private void refreshUsbState() {
		serviceRunning = isMyServiceRunning(UsbIpService.class);
	}

	/**
	 * Always-on relay: as long as the app is open, the foreground service and
	 * the libusbws.so subprocess are running and the WS link to the relay is
	 * held. This is INDEPENDENT of USB device presence — the remote computer
	 * can still enumerate (it'll see an empty device list when nothing is
	 * plugged in) and the phone-on-the-line indicator stays meaningful.
	 *
	 * Stop sharing tears the relay down explicitly; otherwise it only goes
	 * away when the app is swiped away (UsbIpService.onTaskRemoved).
	 */
	private void ensureRelayUp() {
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

		// ---- Stop button + top connection indicator ----
		RelayController.State relayState = relayController.getState();
		boolean relayActive = relayController.isRunning()
				|| relayState == RelayController.State.CONNECTING;

		// Stop button is a manual override: only shown while the relay or the
		// foreground service is actually running. There is no per-device
		// "sharing" label any more — every attached device is offered to the
		// remote and that's what the DEVICE list above already shows.
		boolean active = relayActive || serviceRunning;
		stopSharingButton.setVisibility(active ? View.VISIBLE : View.GONE);

		// Prominent server-connection dot+label at the top: red/amber/green
		// derived from the live relay state, independent of USB device presence.
		updateConnectionIndicator(relayState);
	}

	/**
	 * Top-of-screen indicator. Red = WS link to the relay is down (or never
	 * came up). Amber = subprocess starting / WS opening. Green = WS link up,
	 * phone is on the line. Grey = subprocess not running at all (e.g. before
	 * onResume finished, or after explicit Stop sharing).
	 *
	 * Label is "<state>: <host>" so you can see at a glance which server you
	 * are actually connected to (the running subprocess's URL — NOT prefs,
	 * which only take effect on the next start).
	 */
	private void updateConnectionIndicator(RelayController.State relayState) {
		int colorId;
		int textId;
		boolean showHost = true;
		switch (relayState) {
			case ON:
				colorId = R.color.status_ok;
				textId = R.string.conn_status_connected;
				break;
			case CONNECTING:
				colorId = R.color.status_warn;
				textId = R.string.conn_status_connecting;
				break;
			case DISCONNECTED:
				colorId = R.color.status_bad;
				textId = R.string.conn_status_disconnected;
				break;
			case ERROR:
				colorId = R.color.status_bad;
				textId = R.string.conn_status_error;
				break;
			case OFF:
			default:
				colorId = R.color.status_idle;
				textId = R.string.conn_status_off;
				showHost = false;
				break;
		}
		int tint = ContextCompat.getColor(this, colorId);
		connDot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tint));
		if (showHost) {
			// Prefer the URL the subprocess is actually using; if it isn't
			// running yet, show prefs so the user can sanity-check before start.
			String liveUrl = relayController.getCurrentRelayUrl();
			if (liveUrl == null) {
				liveUrl = new Settings(this).getRelayUrl();
			}
			connStatus.setText(getString(textId, hostOf(liveUrl)));
		} else {
			connStatus.setText(textId);
		}
		connStatus.setTextColor(tint);
	}

	/** Extract just the host out of a ws[s]:// URL, e.g. "tele.karlson.ru". */
	private static String hostOf(String url) {
		if (url == null) return "?";
		try {
			android.net.Uri u = android.net.Uri.parse(url);
			String host = u.getHost();
			return host != null ? host : url;
		} catch (Exception e) {
			return url;
		}
	}

	/**
	 * Sensory feedback when a USB device is attached: one clear ringing blip
	 * + short vibration buzz. Loud-on-purpose (max volume, ringer stream so
	 * it bypasses the media slider) so the phone can sit in a pocket and
	 * still confirm the event audibly.
	 */
	private void feedbackAttach() {
		playTone(ToneGenerator.TONE_DTMF_5, 200);
		vibrate(80);
	}

	/**
	 * Sensory feedback for detach: a "double-tap" (two shorter buzzes + a
	 * lower-pitched blip) so it's distinguishable from attach without the
	 * user having to think about which one just happened.
	 */
	private void feedbackDetach() {
		playTone(ToneGenerator.TONE_DTMF_1, 150);
		new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
			@Override public void run() { playTone(ToneGenerator.TONE_DTMF_1, 150); }
		}, 220);
		vibratePattern(new long[]{0, 80, 100, 80});
	}

	private void playTone(int toneType, int durationMs) {
		try {
			// Ringer stream + max volume so the blip is loud enough to hear
			// from a pocket. Ringer is not silenced by media volume, only by
			// the phone's mute switch.
			final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_RING, 100);
			tg.startTone(toneType, durationMs);
			// Tone runs async; release the generator slightly after the tone ends.
			new android.os.Handler(getMainLooper()).postDelayed(new Runnable() {
				@Override
				public void run() {
					tg.release();
				}
			}, durationMs + 50);
		} catch (RuntimeException ignored) {
			// Some devices throw if the audio service is busy; not critical.
		}
	}

	@SuppressWarnings("deprecation")
	private void vibrate(long ms) {
		Vibrator v = obtainVibrator();
		if (v == null) return;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
		} else {
			v.vibrate(ms);
		}
	}

	@SuppressWarnings("deprecation")
	private void vibratePattern(long[] pattern) {
		Vibrator v = obtainVibrator();
		if (v == null) return;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			v.vibrate(VibrationEffect.createWaveform(pattern, -1));
		} else {
			v.vibrate(pattern, -1);
		}
	}

	private Vibrator obtainVibrator() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			VibratorManager vm = (VibratorManager) getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
			return vm != null ? vm.getDefaultVibrator() : null;
		}
		return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
	}

	/**
	 * Light up a traffic dot if active=true, otherwise fade it back to the
	 * idle (very dim) state. Animation is short so rapid STAT ticks visually
	 * coalesce into a "lit" dot rather than flickering.
	 */
	private void blinkDot(View dot, boolean active) {
		if (dot == null) return;
		dot.animate().cancel();
		if (active) {
			dot.setAlpha(1f);
			dot.animate().alpha(DOT_IDLE_ALPHA).setDuration(200).start();
		} else {
			dot.animate().alpha(DOT_IDLE_ALPHA).setDuration(120).start();
		}
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
