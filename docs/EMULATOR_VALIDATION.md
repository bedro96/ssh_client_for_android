# Android Emulator Validation Method

Reusable, **no-guessing** procedure to build, install, and drive this SSH client
on the local headless emulator, then validate terminal rendering against a real
SSH session. Follow this exactly — do **not** re-derive it by eyeballing
screenshots (screenshots render scaled in tooling; tap coordinates guessed from
them are wrong).

## Environment (already provisioned on this host)

- Android SDK: `/tmp/android-sdk` (build-tools **35.0.0**, platforms android-34/35)
- AVD: `test34`, running headless as device **`emulator-5554`** (KVM at `/dev/kvm`)
- Device screen: **1080 x 2340**, density 440. `adb exec-out screencap` PNGs are
  **1:1** with device pixels — but viewer tools downscale them, so never read
  tap coordinates off a viewed image. Use `uiautomator dump` (below) instead.

```bash
export ANDROID_SDK_ROOT=/tmp/android-sdk
export PATH="$PATH:/tmp/android-sdk/platform-tools:/tmp/android-sdk/emulator"
adb devices        # expect: emulator-5554  device
```

If no emulator is running, start it detached:
```bash
/tmp/android-sdk/emulator/emulator -avd test34 -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot -accel on &
adb wait-for-device
```

## 1. Build the APK

`build-release.sh` calls `python` (absent) only to generate a keystore password.
Set `RELEASE_KEYSTORE_PASSWORD` to skip it. Requires build-tools 35.0.0+ (34.0.0
`d8` NPEs on multi-release jars).

```bash
cd /home/azureuser/ssh_client_for_android
export ANDROID_SDK_ROOT=/tmp/android-sdk
export RELEASE_KEYSTORE_PASSWORD="devtestpassword12345"
./build-release.sh 2>&1 | tail -5      # -> release/app-release.apk
```

## 2. Install (uninstall first — random keystore each build)

```bash
adb uninstall com.bedro96.sshclient 2>/dev/null   # avoids INSTALL_FAILED_UPDATE_INCOMPATIBLE
adb install -r release/app-release.apk
adb shell am force-stop com.bedro96.sshclient
adb shell monkey -p com.bedro96.sshclient -c android.intent.category.LAUNCHER 1
```

## 3. Get EXACT widget coordinates (never eyeball)

```bash
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
grep -oE '(resource-id|text|bounds)="[^"]*"' /tmp/ui.xml | paste - - -
```
`bounds="[x1,y1][x2,y2]"` → tap center = `((x1+x2)/2, (y1+y2)/2)`.
Current MainActivity layout (re-dump if the layout changes):

| Widget        | resource-id   | bounds                  | tap center   |
|---------------|---------------|-------------------------|--------------|
| Host field    | (EditText #1) | `[0,422][793,546]`      | `396 484`    |
| Port field    | (EditText #2) | `[815,422][1080,546]`   | `947 484`    |
| Username      | (EditText #3) | `[0,563][529,687]`      | `264 625`    |
| Password      | (EditText #4) | `[551,563][1080,687]`   | `815 625`    |
| CONNECT       | `btnConnect`  | `[835,853][1080,985]`   | `957 919`    |
| Terminal out  | `txtOutput`   | `[17,1024][1063,2191]`  | `540 1607`   |

### Typing rules that actually work
- `adb shell input tap X Y` then `adb shell input text "..."`. Text always goes
  to the **currently focused** field; `TAB` (keyevent 61) does **not** reliably
  move focus between these EditTexts, so tap each field explicitly.
- To clear a field: tap it, `input keyevent KEYCODE_MOVE_END`, then loop
  `input keyevent 67` (DEL) ~30x. Verify with a screenshot before typing.
- Do **not** press BACK (keyevent 4) to dismiss the keyboard — it exits the app.
  Username/Password rows sit above the IME, so they stay tappable with the
  keyboard open.

## 4. Local SSH target the emulator can reach

Host reaches emulator loopback via **`10.0.2.2`**. sshd runs on host port 22.
A `sshtest` user (password `sshtest123`) with password auth enabled is used for
validation. Password auth drop-in must sort **before** `60-cloudimg-settings.conf`
(first match wins):

```bash
printf 'PasswordAuthentication yes\nKbdInteractiveAuthentication yes\n' \
  | sudo tee /etc/ssh/sshd_config.d/01-sshtest.conf
sudo systemctl restart ssh
sudo sshd -T | grep -i passwordauthentication   # expect: yes
```

Login runs `~sshtest/repro.sh` (via `.bash_profile: exec ~/repro.sh`) which draws
a full-width box using the PTY `tput cols`/`tput lines` — this reproduces the
Copilot-CLI border "line-and-a-half" wrap if the app soft-wraps full-width rows.

## 5. Drive the connect flow + capture terminal

```bash
adb shell input tap 396 484;  adb shell input text "10.0.2.2"
adb shell input tap 264 625;  adb shell input text "sshtest"
adb shell input tap 815 625;  adb shell input text "sshtest123"
adb shell input tap 957 919                      # CONNECT
sleep 4
adb exec-out screencap -p > /tmp/term.png        # inspect terminal rendering
```

Validation check: the box top/bottom borders must each occupy **exactly one**
display line (no wrap to a second line) and every row must be column-aligned.

## 6. Offline unit tests (fast, no emulator)

```bash
./run-tests.sh        # SshKeyAuth, Terminal* incl. TerminalGeometryTest
./test-ed25519.sh
```
