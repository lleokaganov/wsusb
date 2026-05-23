USB/IP Server for Android + encrypted relay
===========================================

Share a USB device that is plugged into your phone (via OTG) with a remote
computer, over an end-to-end encrypted relay — no public IP needed on either
side.

This is a fork of [cgutman/USBIPServerForAndroid](https://github.com/cgutman/USBIPServerForAndroid)
(a USB/IP server for Android that exports a USB device over the USB/IP protocol
on `localhost:3240`) with an added **relay bridge**: the bundled native binary
`libusbws.so` tunnels that local `127.0.0.1:3240` socket through the encrypted
[ws_server](https://ws.lleo.me) relay to a remote peer. On the remote side,
[`usbws`](https://github.com/lleokaganov/usbws) listens and exposes the phone's
device as if it were locally plugged in.

How it works
------------

```
  phone (USB device on OTG)                          remote computer
  ┌───────────────────────┐                          ┌───────────────────────┐
  │ USB/IP server :3240    │                          │ usbws tcp-listen :3240 │
  │ libusbws.so            │  ◄── encrypted relay ──► │ usbip attach           │
  │   tcp-connect          │      (ws.lleo.me)        │   → /dev/bus/usb/...    │
  └───────────────────────┘                          └───────────────────────┘
```

Traffic between the two ends is encrypted peer-to-peer; the relay only ever
sees ciphertext. Each side authorizes the other by its **invite code**
(trust-by-key): you exchange invites once, and the two ends connect
automatically.

Using it
--------

### On the phone

1. Install the APK.
2. Plug your USB device into the phone with an OTG adapter.
3. Open the app:
   - **Your invite** — copy it and send it to whoever runs the remote side.
   - **Peer invite** — paste the remote side's invite here and tap **Save**.
   - Tap **Start Service** to bring up the USB/IP server (grant the USB
     permission when prompted).
   - Tap **Relay (remote USB)** to bring up the encrypted tunnel.

Your identity (the keypair behind your invite) is generated on first run and
stored privately on the device — it is unique per install and is never bundled
in the APK.

### On the remote computer

Install [`usbws`](https://github.com/lleokaganov/usbws), then:

```sh
# Listen locally and tunnel to the phone (paste the phone's invite):
usbws tcp-listen 3240 --peer K0...

# Attach the exported device (in another terminal):
sudo modprobe vhci-hcd
usbip list -r 127.0.0.1          # find the busid
sudo usbip attach -r 127.0.0.1 -b <busid>
```

The phone's USB device now appears as a local USB device on the remote machine.

Building the native binary
--------------------------

The relay binary `libusbws.so` is built from the
[`usbws`](https://github.com/lleokaganov/usbws) repository for Android and
placed at `app/src/main/jniLibs/arm64-v8a/libusbws.so`. It is shipped under the
`lib*.so` name so the build system bundles it and Android extracts it to a
directory from which a process may be executed.

The Android build is TCP-only: the `serial` feature is disabled because
Bionic (Android's libc) has no `openpty`.

```sh
# In the usbws repo:
cargo install cargo-ndk           # once
rustup target add aarch64-linux-android
cargo ndk -t arm64-v8a build --release --no-default-features
cp target/aarch64-linux-android/release/usbws \
   <this-repo>/app/src/main/jniLibs/arm64-v8a/libusbws.so
```

> The `libusbws.so` binary and the per-device `usbws_identity` file are listed
> in `.gitignore` and are **not** committed to this repository.

Building the APK
----------------

```sh
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

License / attribution
---------------------

Fork of [cgutman/USBIPServerForAndroid](https://github.com/cgutman/USBIPServerForAndroid),
licensed under the **GNU General Public License v3.0** (GPL-3). The relay
integration in this fork is distributed under the same license.
