# 🦡 Badger Access — Android App

**GitHub Repo:** https://github.com/augesrob/badger-android  
**Active Branch:** `badger-access`  
**Database:** Supabase (shared with the Badger web app)  
**Auto-Build:** GitHub Actions — every push to `badger-access` triggers an automatic build and release

---

## 📋 What Is This?

Badger Access is the Android companion app for the Badger warehouse management system. It gives warehouse staff mobile access to the same live data as the website — real-time truck movement, push-to-talk radio, voice commands, pre-shift setup, and more.

The app connects directly to the same Supabase database as the website. Changes made on the website appear on the app immediately (and vice versa) via real-time WebSocket subscriptions.

---

## 🏗️ How the App Is Built & Released

```
Developer pushes code to GitHub (badger-access branch)
        ↓
GitHub Actions workflow triggers automatically
(.github/workflows/build-release-access.yml)
        ↓
GitHub's build server (Ubuntu Linux):
  - Sets up Java 17 and Android SDK
  - Decodes the signing keystore from GitHub Secrets
  - Bumps the versionCode by 1 in build.gradle.kts
  - Injects all secret keys as environment variables
  - Runs: ./gradlew assembleRelease
  - Signs the APK with the release keystore
        ↓
Signed APK is uploaded as a GitHub Release
(tagged as: access-vNN, e.g. access-v42)
        ↓
The /download page on the website detects the new release
and shows the Download APK button to users
```

**Nobody needs to manually build or distribute the app.** Every code push produces a new signed, installable APK automatically.

---

## 📁 Repository Structure

```
badger-android/
├── .github/
│   └── workflows/
│       ├── build-release-access.yml  ← Auto-build for badger-access branch (this app)
│       └── build-release.yml         ← Auto-build for main branch (original app)
├── app/
│   ├── build.gradle.kts              ← Build config, version numbers, secret injection
│   ├── badger.keystore               ← APK signing keystore (generated once, kept in repo)
│   └── src/main/java/com/badger/trucks/
│       ├── BadgerApp.kt              ← Application entry point, Supabase client initialization
│       ├── MainActivity.kt           ← Main screen shell, tab navigation, role-change dialogs
│       ├── data/
│       │   ├── Models.kt             ← All data models (Truck, Door, User, Status, etc.)
│       │   ├── BadgerRepo.kt         ← All database queries (reads/writes to Supabase)
│       │   └── AuthManager.kt        ← Login/logout state, user profile, role watching
│       ├── service/
│       │   ├── BadgerService.kt      ← Background Android Service — keeps running when app
│       │   │                            is in background. Handles TTS announcements, PTT radio,
│       │   │                            voice commands, real-time data sync, and volume boost.
│       │   ├── NotificationHelper.kt ← Creates Android notification channels and posts alerts
│       │   └── NotificationPrefsStore.kt ← Stores user settings (TTS on/off, PTT visibility, etc.)
│       ├── voice/
│       │   ├── VoiceCommand.kt       ← Speech recognition + Gemini AI voice command parsing.
│       │   │                            Listens to spoken commands, sends to Gemini API to
│       │   │                            understand intent, then executes the action (e.g.
│       │   │                            "Set truck 170 to In Door")
│       │   └── PushToTalk.kt         ← PTT radio — records and broadcasts audio over Supabase
│       ├── ui/
│       │   ├── login/
│       │   │   └── LoginScreen.kt    ← Login screen (email + password via Supabase Auth)
│       │   ├── movement/
│       │   │   └── MovementScreen.kt ← 🚚 Live Movement tab — real-time truck/door board,
│       │   │                            PTT button, mic/voice command button, fix-all button
│       │   ├── shiftsetup/
│       │   │   ├── ShiftSetupScreen.kt ← 🖨️ Shift Setup tab — menu for Print Room,
│       │   │   │                          PreShift, and Tractor/Trailer screens
│       │   │   ├── PrintRoomScreen.kt  ← Print room queue management
│       │   │   ├── PreShiftScreen.kt   ← Pre-shift door-to-truck setup
│       │   │   └── TractorsScreen.kt   ← Tractor/trailer database
│       │   ├── settings/
│       │   │   ├── SettingsScreen.kt             ← ⚙️ Settings tab — tabbed settings menu
│       │   │   ├── NotificationSettingsScreen.kt ← TTS, PTT, mic visibility toggles
│       │   │   ├── StatusValuesScreen.kt          ← View/manage truck status options
│       │   │   ├── UsersScreen.kt                 ← View all users and their roles
│       │   │   ├── BackupScreen.kt                ← View backup log, trigger manual backup
│       │   │   ├── GlobalMessagesScreen.kt        ← View/manage site-wide messages
│       │   │   └── ApiMonitorScreen.kt            ← Live view of recent API activity/logs
│       │   ├── admin/
│       │   │   ├── AdminScreen.kt    ← Admin-only screen
│       │   │   └── DebugScreen.kt    ← Debug log viewer
│       │   ├── chat/
│       │   │   └── ChatScreen.kt     ← 💬 Chat tab — internal messaging
│       │   ├── profile/
│       │   │   └── ProfileScreen.kt  ← User profile and avatar settings
│       │   └── theme/
│       │       └── Theme.kt          ← App colors, typography, dark theme definitions
│       └── util/
│           ├── RemoteLogger.kt       ← Sends log messages to Supabase `debug_logs` table
│           │                            so developers can see app logs remotely
│           └── AppUpdater.kt         ← Checks GitHub Releases for a newer APK version,
│                                        downloads and prompts install if one is found
```

---

## 🔑 Environment Variables & Secrets

The app uses two systems for secrets depending on whether you are building locally or on GitHub Actions.

### Local Development
Secrets are stored in `local.properties` at the root of the Android project (this file is git-ignored and never committed).

```properties
# local.properties (DO NOT COMMIT)
SUPABASE_URL=https://YOUR_PROJECT_ID.supabase.co
SUPABASE_KEY=your-supabase-anon-key-here
GEMINI_API_KEY=your-gemini-api-key-here
GH_TOKEN=github_pat_your_token_here
```

### GitHub Actions (Automated Builds)
Secrets are stored in **GitHub → Repository Settings → Secrets and variables → Actions**. The build workflow reads them as environment variables at build time.

| Secret Name | Where to Get It | What It's Used For |
|---|---|---|
| `SUPABASE_URL` | Supabase → Project Settings → API | URL for the Supabase project. Used by the app to connect to the database. |
| `SUPABASE_KEY` | Supabase → Project Settings → API → `anon` key | Public API key for Supabase. The app uses this to authenticate all database requests. |
| `GEMINI_API_KEY` | Google AI Studio → https://aistudio.google.com | API key for Google's Gemini AI. Used by the voice command system to understand spoken commands like "set truck 170 to In Door". |
| `GH_TOKEN` | GitHub → Settings → Fine-grained tokens | Read-only token for the `badger-android` repo. Used by the app's auto-update checker to find the latest release version. |
| `KEYSTORE_BASE64` | Generate once (see below) | The APK signing keystore, base64-encoded. GitHub Actions decodes this and uses it to sign the release APK. |
| `KEYSTORE_PASSWORD` | Set when generating keystore | Password for the keystore file. |
| `KEY_ALIAS` | Set when generating keystore | The alias name of the signing key inside the keystore. |
| `KEY_PASSWORD` | Set when generating keystore | Password for the specific key inside the keystore. |

### How Secrets Get Into the App

In `app/build.gradle.kts`, during the build process, secrets are read from environment variables (on GitHub) or from `local.properties` (locally) and compiled directly into the app as `BuildConfig` fields:

```kotlin
buildConfigField("String", "SUPABASE_URL",   "\"${secret("SUPABASE_URL")}\"")
buildConfigField("String", "SUPABASE_KEY",   "\"${secret("SUPABASE_KEY")}\"")
buildConfigField("String", "GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
buildConfigField("String", "GITHUB_TOKEN",   "\"${secret("GH_TOKEN")}\"")
```

This means the secrets are baked into the compiled APK at build time. They are not fetched at runtime from any server.

---

## 📱 App Structure — Tabs

The app has 4 main tabs shown at the bottom of the screen. Which tabs a user sees depends on their role (same role system as the website).

| Tab | What It Does |
|---|---|
| **🖨️ Shift Setup** | Menu for Print Room, PreShift door setup, and Tractor/Trailer database |
| **🚚 Live** | Real-time truck movement board — shows all trucks and dock doors, with PTT and voice command buttons |
| **💬 Chat** | Internal team chat |
| **⚙️ Settings** | Notification preferences, status values, users, backup, API monitor |

---

## 🔄 How Real-Time Data Works

```
User opens app
        ↓
App connects to Supabase via WebSocket (wss://...)
        ↓
App subscribes to database table changes (live_movement, loading_doors, etc.)
        ↓
When any user (web or app) updates a truck status or door assignment...
        ↓
Supabase instantly pushes the change to ALL connected clients
        ↓
The app screen updates within milliseconds — no refresh needed
```

This is handled by `BadgerService` (a background Android Service) which maintains the WebSocket connection even when the app is not in the foreground. The service also:
- Announces truck status changes aloud via **Text-to-Speech (TTS)**
- Hosts the **Push-to-Talk (PTT)** radio feature
- Processes **voice commands** via microphone + Gemini AI
- Applies optional **volume boost** for loud warehouse environments

---

## 🎙️ Voice Commands

When the user taps the blue microphone button on the Live Movement screen:

```
User taps mic button
        ↓
App checks microphone permission (requests if not granted)
        ↓
BadgerService starts Android speech recognizer (listens for ~5 seconds)
        ↓
Recognized speech text (e.g. "Set truck 170 to In Door") is sent to Gemini API
        ↓
Gemini parses the intent and returns a structured command object
(or a built-in Kotlin parser handles common patterns directly, faster and free)
        ↓
The app executes the command against Supabase
        ↓
TTS announces the result: "Truck 170 set to In Door"
        ↓
The live board updates in real-time for all users
```

---

## 📦 APK Signing & Distribution

Android apps must be **signed** with a cryptographic keystore before they can be installed on devices. The signing process proves the APK came from a trusted source and has not been tampered with.

### The Keystore
The signing keystore (`app/badger.keystore`) was generated once using the `keytool` command:
```bash
keytool -genkeypair -v -keystore badger.keystore -alias badger \
  -keyalg RSA -keysize 2048 -validity 10000
```

This file lives in the repo. For GitHub Actions to use it, it was base64-encoded and stored as the `KEYSTORE_BASE64` secret:
```bash
base64 -i badger.keystore | pbcopy   # copies to clipboard (macOS)
```

During the build workflow, the action decodes it back:
```bash
echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > app/badger.keystore
```

### Why This Matters
- If the keystore is lost, the only way to update the app on devices that have it installed is to uninstall and reinstall
- The same keystore must be used for every release — changing it breaks updates
- The keystore is backed up in the `KEYSTORE_BASE64` GitHub secret

---

## 🔁 Auto-Update Flow

The app automatically checks for newer versions every time it launches:

```
App starts
        ↓
AppUpdater.kt calls GitHub API: /repos/augesrob/badger-android/releases/latest
(using GH_TOKEN for authentication — works even when repo is private)
        ↓
Compares latest release versionCode with current app versionCode
        ↓
If newer version exists: shows yellow "Update available — Install / Later" banner
        ↓
User taps Install → app downloads the new APK and prompts system install
        ↓
User accepts → app updates to new version
```

---

## 🚀 How to Trigger a New Build

Simply push code to the `badger-access` branch:

```bash
git add .
git commit -m "your change description"
git push origin badger-access
```

Within ~2 minutes:
1. GitHub Actions builds and signs the APK
2. versionCode is automatically incremented (e.g. v42 → v43)
3. A new GitHub Release is created with the APK attached
4. The `/download` page on the website shows the new version

The workflow file that controls all of this is at:
`.github/workflows/build-release-access.yml`

---

## 🖥️ Building Locally (Developer Setup)

Requirements: Android Studio, Java 17, Android SDK

1. Clone the repo: `git clone https://github.com/augesrob/badger-android.git`
2. Checkout the active branch: `git checkout badger-access`
3. Open the project in Android Studio
4. Create `local.properties` in the project root with your secret values (see above)
5. Connect an Android device (API 26+ / Android 8.0 or higher) or use an emulator
6. Press **Run** in Android Studio

> **Note:** The minimum supported Android version is **Android 8.0 (API 26)**. The app targets Android 14 (API 34).

---

## 🔒 Security Notes

- All secrets are injected at **build time** — they never travel over the network after the app is installed
- The Supabase `anon` key is the public key — Supabase's Row-Level Security (RLS) policies on the database enforce what the app can and cannot access based on the logged-in user
- The `GH_TOKEN` stored in the app is **read-only** — it can only read releases, not push code or modify anything
- The keystore (`badger.keystore`) must be kept safe — losing it means future updates cannot be signed with the same identity
