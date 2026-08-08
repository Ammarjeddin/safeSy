# safeSy — Dev Environment Setup (macOS Apple Silicon)

Your machine, checked: **macOS 26.6, arm64, Homebrew 6.0.15, Java 26 (Temurin), Docker, git, python3, node**.
Missing: Android Studio, Android SDK, `adb`, `protoc`, **JDK 17**.

---

## ⚠️ The one thing that will bite you first

You have **Java 26**. The Android Gradle Plugin needs **JDK 17+ to run**, but AGP toolchains are validated against 17/21 — Java 26 is newer than the Android build stack reliably handles and produces confusing Gradle failures.

**Fix:** install JDK 17 *alongside* Java 26 and point Android at it. Don't uninstall Java 26; other tooling may want it.

---

## 1. Install (≈20 min, mostly download)

```bash
# JDK 17 for Android builds — coexists with your Java 26
brew install --cask temurin@17

# Android Studio (bundles the SDK, emulator, and its own JDK for the IDE itself)
brew install --cask android-studio

# Command-line tools we'll use directly
brew install protobuf        # protoc — S1 schema codegen
brew install android-platform-tools   # adb, on PATH without opening Studio
brew install scrcpy          # mirror the Xiaomi screen to your Mac — very useful for Drive Mode work
```

Then launch Android Studio once and complete the setup wizard (it downloads the SDK, ~3–6 GB).

## 2. Environment

Add to `~/.zshrc`:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# JDK 17 for Gradle/Android builds (leave the system default alone)
export JAVA_HOME_17="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
```

In Android Studio: **Settings → Build → Build Tools → Gradle → Gradle JDK → Temurin 17**.

Verify:
```bash
source ~/.zshrc
adb version && protoc --version && "$JAVA_HOME_17/bin/java" -version
```

## 3. Your Xiaomi phones — MIUI needs specific setup

Both devices being Xiaomi is genuinely useful: MIUI is the **worst-case** background-kill environment, so if the foreground service survives here it survives anywhere. It's also the risk flagged in `SPEC.md`.

**On each phone:**

1. **Settings → About phone → tap "MIUI version" 7×** → Developer options unlocked.
2. **Settings → Additional settings → Developer options**, enable:
   - **USB debugging**
   - **Install via USB**
   - **USB debugging (Security settings)** ← MIUI-specific; needed to grant permissions via adb. May require a Xiaomi account and a SIM.
   - **Stay awake while charging** (convenient for drive testing)
3. Plug into the Mac, accept the RSA fingerprint prompt on the phone.

```bash
adb devices     # should list both, "device" not "unauthorized"
```

**MIUI battery settings (do this now — it is the #1 cause of "the app stopped working"):**
- **Settings → Apps → Manage apps → safeSy → Battery saver → No restrictions**
- **Autostart → enabled**
- Recent-apps screen → swipe *down* on the safeSy card → **padlock** (prevents swipe-kill)

We will still instrument kill-rate in the app, because most real drivers won't do any of this.

## 4. Suggested repo layout

```
safeSy/
├── DESIGN.md          # reasoning record (why, what was rejected, corrections)
├── SPEC.md            # build spec (settled decisions)
├── SETUP.md           # this file
├── proto/             # S1 — safesy-proto
│   └── safesy/v1/telemetry.proto
├── spec/              # S1 — safesy-spec (sealing, seq, time rules)
├── conformance/       # S1 — fixtures (classes A/B/C)
├── android/           # S2-S4, S6.3 — Gradle project
└── server/            # S5, S6 — docker-compose + service
```

## 5. First commands (in order)

```bash
cd /Users/ammarjeddin/Local/Projects/safeSy
git init && git branch -m main

mkdir -p proto/safesy/v1 spec conformance android server
# → then we write telemetry.proto together (Step 0)

# backend deps come up in Docker, which you already have
# (postgres + postgis + timescale, one compose file — Step 2)
```

## 6. How we'll work together

I have file editing, shell, and git here, so the loop is:

- **You:** decide, review, run the app on real hardware, drive the routes.
- **Me:** write code, run builds and tests, read logcat, iterate.

Practical notes:
- `./gradlew assembleDebug` and `adb install -r` I can run directly.
- `adb logcat` I can read — so "it crashed" becomes a stack trace without you copying anything.
- `scrcpy` mirrors the phone to your screen, which makes reviewing Arabic RTL layout much faster than squinting at a handset.
- I cannot physically drive the bus or plug in cables. The **step-4 real-drive spike** is yours.

**Start point:** `SPEC.md` Step 0 — `safesy-proto`. It is small, it is the thing everything else depends on, and it is the artifact that keeps the Phase-2 embedded package honest.
