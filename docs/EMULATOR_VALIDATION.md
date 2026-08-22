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
adb shell uiautomator dump
adb pull /sdcard/window_dump.xml window_dump.xml
grep -oE '(resource-id|text|bounds)="[^"]*"' window_dump.xml | paste - - -
```
`bounds="[x1,y1][x2,y2]"` → tap center = `((x1+x2)/2, (y1+y2)/2)`.
Current MainActivity layout (re-dump if the layout changes):

| Widget        | resource-id   | bounds                  | tap center   |
|---------------|---------------|-------------------------|--------------|
| Host field    | `editHost`    | `[32,286][778,404]`     | `405 345`    |
| Port field    | `editPort`    | `[799,286][1048,404]`   | `923 345`    |
| Username      | `editUser`    | `[32,420][529,538]`     | `280 479`    |
| Password      | `editPassword`| `[550,420][1048,538]`   | `799 479`    |
| CONNECT       | `btnConnect`  | `[817,696][1048,822]`   | `932 759`    |
| Terminal out  | `txtOutput`   | `[48,859][1032,2289]`   | `540 1574`   |

### Typing rules that actually work
- `adb shell input tap X Y` then `adb shell input text "..."`. Text always goes
  to the **currently focused** field; `TAB` (keyevent 61) does **not** reliably
  move focus between these EditTexts, so tap each field explicitly.
- To clear a field: tap it, `input keyevent KEYCODE_MOVE_END`, then loop
  `input keyevent 67` (DEL) ~30x. Verify with a screenshot before typing.
- `adb shell input text "10.0.2.2"` is **not** reliable for the host field on
  this emulator; it truncates after the second dot. Enter `10.0.2.2` with
  keyevents instead:
  `8 7 56 7 56 9 56 9` (`1 0 . 0 . 2 . 2`).
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

Install `tmux`, then switch the login repro from the old `repro.sh` to the tmux
scenario for issue #56:

```bash
sudo apt-get install -y tmux
printf 'exec /home/sshtest/tmux-repro.sh\n' | sudo tee /home/sshtest/.bash_profile
sudo -u sshtest env HOME=/home/sshtest TMUX_REPRO_NO_ATTACH=1 /home/sshtest/tmux-repro.sh
sudo -u sshtest tmux list-panes -t smoke -F '#{pane_index} #{pane_current_command} #{pane_width}x#{pane_height}'
```

`~sshtest/tmux-repro.sh` creates or re-attaches a `tmux` session named `smoke`
with:

- one pane continuously printing redraw-heavy ASCII/box rows,
- one pane continuously printing Korean UTF-8 text,
- tmux's status bar left enabled so status-line corruption is visible,
- aggressive resize enabled so rotate/IME resizes exercise the concurrent redraw
  path from issue #54.

## 5. Scripted tmux smoke run

Once the emulator, APK, and `sshtest` account are ready, prefer the dedicated
automation script:

```bash
cd /home/azureuser/ssh_client_for_android
export ANDROID_SDK_ROOT=/tmp/android-sdk
./run-emulator-tmux-smoke-test.sh
```

Artifacts are written to `emulator-artifacts/tmux-smoke/`:

- `after-connect.png`
- `mid-scroll.png`
- `rotated.png`
- `after-rotate-back.png`
- `logcat.txt`

The script computes tap coordinates from `uiautomator dump` on every run, types
the host IP with keyevents, connects to `10.0.2.2`, rotates to landscape and
back, and saves screenshots/logcat without guessing.

## 6. Manual tmux repro + expected results

```bash
adb shell input tap 405 345                      # Host
adb shell input keyevent 123
for i in $(seq 1 32); do adb shell input keyevent 67; done
for key in 8 7 56 7 56 9 56 9; do adb shell input keyevent "$key"; done

adb shell input tap 280 479;  adb shell input text "sshtest"
adb shell input tap 799 479;  adb shell input text "sshtest123"
adb shell input tap 932 759                      # CONNECT
sleep 8
adb exec-out screencap -p > after-connect.png
sleep 6
adb exec-out screencap -p > mid-scroll.png
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
adb exec-out screencap -p > rotated.png
adb shell settings put system user_rotation 0
sleep 4
adb exec-out screencap -p > after-rotate-back.png
```

Validation checks:

- no missing or duplicated rows while the panes are scrolling,
- no mojibake in `한글 테스트 유니코드`,
- box-drawing separators and status bar stay aligned after rotate/resize,
- tmux status bar remains a single intact line (no stale fragments, overlap, or
  duplicated text),
- no crash/exception in `adb logcat -d | grep -iE 'sshclient|TerminalScreen|Exception|ANR'`.

If a screenshot shows split timestamps, missing chunks, broken Hangul, or a
corrupted tmux status line, treat that as a **real regression signal** and save
the screenshot/logcat as evidence for follow-up triage.

## 7. Offline unit tests (fast, no emulator)

```bash
./run-tests.sh        # SshKeyAuth, Terminal* incl. TerminalGeometryTest
./test-ed25519.sh
```
