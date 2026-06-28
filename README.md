# SSH Client for Android

A native Android SSH client application built with Kotlin. Connect to remote servers directly from your Android device using a clean, touch-friendly terminal interface.

## Overview

SSH Client for Android lets you establish Secure Shell (SSH) connections to remote Linux/Unix servers from an Android device. Key capabilities include:

- **SSH connections** — connect to any SSH server using password or public-key authentication
- **Interactive terminal** — full PTY-backed terminal emulator with touch keyboard support
- **Multiple sessions** — manage and switch between concurrent SSH sessions
- **Connection profiles** — save host, port, username, and authentication settings for quick reconnect
- **Port forwarding** — local and remote tunnel support

The app targets Android 8.0 (API 26) and above and is written entirely in Kotlin using the Android SDK and Gradle build system.

## Repository Structure

```
ssh_client_for_android/
├── app/                        # Main application module
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/           # Kotlin source files
│   │   │   │   └── com/bedro96/sshclient/
│   │   │   │       ├── ui/     # Activities, Fragments, ViewModels
│   │   │   │       ├── ssh/    # SSH connection and session logic
│   │   │   │       ├── terminal/ # Terminal emulator
│   │   │   │       └── data/   # Room database, repositories, models
│   │   │   ├── res/            # Layouts, drawables, strings, themes
│   │   │   └── AndroidManifest.xml
│   │   ├── test/               # Unit tests (JUnit)
│   │   └── androidTest/        # Instrumented tests (Espresso)
│   └── build.gradle.kts        # App-level Gradle build script
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── build.gradle.kts            # Project-level Gradle build script
├── settings.gradle.kts         # Gradle settings (module declarations)
├── gradlew                     # Unix Gradle wrapper script
├── gradlew.bat                 # Windows Gradle wrapper script
├── .gitignore
├── LICENSE                     # Apache License 2.0
└── README.md
```

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Platform | Android SDK (min API 26, target API 34) |
| Build | Gradle (Kotlin DSL) |
| UI | Jetpack (ViewModel, LiveData, Navigation) |
| Persistence | Room (SQLite) |
| SSH library | JSch / SSHJ |
| Terminal | Custom PTY / JLine |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |

## Prerequisites

Before building or running the app you need:

| Requirement | Version |
|---|---|
| JDK | 17 or higher |
| Android Studio | Hedgehog (2023.1.1) or newer |
| Android SDK | API level 34 (Android 14) |
| Android Build Tools | 34.0.0 |
| Gradle | 8.x (bundled via wrapper) |

> You do **not** need to install Gradle manually — the `gradlew` wrapper downloads the correct version automatically.

## Installation

### Option A — Install a pre-built APK

1. Go to the [Releases](../../releases) page of this repository.
2. Download the latest `app-release.apk`.
3. On your Android device, open **Settings → Security** (or **Settings → Apps → Special app access**) and enable **Install unknown apps** for your file manager or browser.
4. Open the downloaded APK file on the device and follow the on-screen prompts to install.

### Option B — Build from source

#### 1. Clone the repository

```bash
git clone https://github.com/bedro96/ssh_client_for_android.git
cd ssh_client_for_android
```

#### 2. Open in Android Studio

1. Launch **Android Studio**.
2. Select **File → Open** and navigate to the cloned directory.
3. Wait for the Gradle sync to complete. Android Studio will automatically download all declared dependencies.

#### 3. Set up an Android device or emulator

**Physical device:**
1. On the device, go to **Settings → About phone** and tap **Build number** seven times to enable Developer options.
2. Go to **Settings → Developer options** and turn on **USB debugging**.
3. Connect the device to your computer via USB and accept the RSA key prompt on the device.

**Emulator:**
1. In Android Studio, open **Device Manager** (View → Tool Windows → Device Manager).
2. Click **Create device**, choose a hardware profile (e.g., Pixel 6), select a system image with API 26 or higher, and finish the wizard.
3. Start the emulator by clicking the play button next to the device entry.

#### 4. Build and run

**From Android Studio:**
- Click the green **Run** button (⇧F10) or go to **Run → Run 'app'**.

**From the command line:**

```bash
# Debug build — installs directly to a connected device/emulator
./gradlew installDebug

# Release build — produces an APK in app/build/outputs/apk/release/
./gradlew assembleRelease
```

> On Windows use `gradlew.bat` instead of `./gradlew`.

#### 5. Sign the release APK (optional, for distribution)

1. In Android Studio, go to **Build → Generate Signed Bundle / APK**.
2. Choose **APK**, then either create a new keystore or use an existing one.
3. Fill in the key alias, passwords, and certificate details.
4. Select the **release** build variant and click **Finish**.

Alternatively, configure signing in `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("path/to/your.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

Then run:

```bash
./gradlew assembleRelease
```

The signed APK will be at `app/build/outputs/apk/release/app-release.apk`.

## Running Tests

```bash
# Unit tests
./gradlew test

# Instrumented tests (requires a connected device or running emulator)
./gradlew connectedAndroidTest
```

## Permissions

The app requests the following Android permissions:

| Permission | Reason |
|---|---|
| `INTERNET` | Establish TCP connections to SSH servers |
| `ACCESS_NETWORK_STATE` | Detect connectivity changes |
| `USE_BIOMETRIC` | Optional biometric unlock for saved credentials |

## License

This project is licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) for the full text.
