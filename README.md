# SSH Client for Android

A small, working SSH client app for Android. The app opens an interactive
remote shell over SSH (password authentication) and lets you stream output and
type commands from your phone or tablet.

The signed initial release APK is at `release/app-release.apk` and is built
without Gradle — it uses the Android SDK command-line tools directly (see
`build-release.sh`).

## Features

- Authentication via password and/or imported identity (private key) file;
  the password field also doubles as the private-key passphrase when needed
- Saved host profiles (name, host, port, username, password, identity file)
  selectable from a dropdown for fast reconnect; Save/Delete buttons
- Import an identity file from device storage into the app (system file picker)
- Interactive remote shell channel (PTY `xterm`) with live streaming output
- Single terminal area — keystrokes are streamed straight to the remote shell
  (no separate command field); remote echo drives the display
- Special-key toolbar: ESC, TAB, Ctrl+C, Ctrl+D, ▲ ▼ ◀ ▶, Ctrl+A, Ctrl+E,
  Ctrl+B, Ctrl+Z, and font size A+/A-
- Adaptive layout: phone layout (`res/layout/`) and a wider tablet layout
  (`res/layout-sw600dp/`) for modern handheld and high-resolution tablet
  Samsung devices
- `minSdkVersion` 24 (Android 7.0+) — covers every supported Samsung device
- `targetSdkVersion` follows the newest installed Android platform jar (35/36
  on a fully patched SDK), so the APK is current for the latest Samsung
  Galaxy S/Tab devices on Android 14/15/16

## Install on a Samsung device

1. Copy `release/app-release.apk` to the device (USB, Drive, etc.).
2. In **Settings → Apps → Special access → Install unknown apps**, allow your
   file manager / browser to install APKs.
3. Open the APK and tap **Install**. The launcher entry is **SSH Client**.

The APK is signed with a freshly generated release key — it installs cleanly,
but if you reinstall after rebuilding you may need to uninstall the previous
copy first because the signing certificate changes per build (no keystore is
committed to the repository).

## Rebuild the APK

```bash
./build-release.sh
```

The script:

- requires `ANDROID_SDK_ROOT` or `ANDROID_HOME` to point at an Android SDK
  with at least one `platforms/android-NN/android.jar` and modern
  `build-tools/` installed (34.0.0 or newer)
- auto-selects the newest installed build-tools directory and platform jar
- downloads and verifies (SHA-256) the
  [`com.github.mwiede:jsch`](https://github.com/mwiede/jsch) SSH library and the
  [`org.bouncycastle:bcprov-jdk18on`](https://www.bouncycastle.org/) provider
  jars into `app/libs/`
- compiles Java with `javac`, dexes via `d8`, packages with `aapt2`,
  packages every generated `*.dex`, zip-aligns and signs with `apksigner`
- writes the result to `release/app-release.apk`

Override the JSch version (and update its checksum in `build-release.sh`) if
you want to ship a newer SSH library.

BouncyCastle is bundled because Ed25519 identity keys (`id_ed25519`) rely on
jsch's `com.jcraft.jsch.bc.*` EdDSA classes on Android — jsch's preferred
JDK 15+ EdDSA implementation lives in the jar's `META-INF/versions/15/`
multi-release directory, which Android's dex packaging drops. Without the
provider, Ed25519 key auth fails with `Auth fail for methods 'publickey'`.

## Offline Ed25519 regression test

```bash
./test-ed25519.sh
```

This test disables JAR multi-release support to simulate Android, then proves
an imported Ed25519 identity can sign and verify through JSch's bundled
BouncyCastle-backed `com.jcraft.jsch.bc.*` implementation.

## Run the tests

```bash
./run-tests.sh
```

This compiles and runs the offline Ed25519 unit tests against the same jsch and
BouncyCastle jars the APK bundles, with the JDK 15+ multi-release classes
disabled so the Android (Bouncy Castle) code path is exercised.

## Source layout

- `app/src/main/AndroidManifest.xml` — INTERNET permission, screen-size
  support, launcher activity
- `app/src/main/java/com/bedro96/sshclient/MainActivity.java` — UI + SSH
  session lifecycle (background `ExecutorService`, reader thread for the
  remote stdout, command writer)
- `app/src/main/res/layout/activity_main.xml` — phone layout
- `app/src/main/res/layout-sw600dp/activity_main.xml` — tablet layout
- `build-release.sh` — offline build/sign pipeline

## Caveats of this release

- Profiles (including passwords) stored in plain `SharedPreferences`; identity
  files are copied into app-private storage (`filesDir/identity_keys/`)
- Trust-on-first-use host keys (`StrictHostKeyChecking=no`) — this is an
  initial client, not a hardened production tool
- Single concurrent session
