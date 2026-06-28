# SSH Client for Android

This repository now includes an initial Android app shell and a checked-in release APK at `/release/app-release.apk`.

## Current contents

- `app/src/main/` — minimal Android application source
- `build-release.sh` — offline build script that uses the local Android SDK command-line tools
- `release/app-release.apk` — signed initial release artifact

## Rebuild the APK

```bash
cd <repository-root>
./build-release.sh
```

The script expects `ANDROID_SDK_ROOT` or `ANDROID_HOME` to point at an Android SDK installation with platform `android-34` and build-tools `34.0.0` or newer installed. It auto-selects the newest installed build-tools version.
