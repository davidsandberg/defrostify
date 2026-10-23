# Defrostify ❄️🚗🔥

**Defrostify** is a native Android application (built with **Kotlin** and **Jetpack Compose**) for controlling and scheduling an electric car heater connected to a smart Z-Wave switch (such as a Fibaro Wall Plug) via a **Telldus TellStick ZNet Lite v2** controller.

Instead of manually calculating when to turn on the heater, you simply pick **when the car should be warm** using a Samsung One UI alarm-style scroll wheel, and Defrostify automatically computes and syncs the pre-heat start time and auto-off safety timer to your TellStick controller.

---

## ✨ Key Features

- **Departure-Time Scheduling ("When Car Should Be Warm")**
  - Dual 24-hour scroll wheels with haptic feedback and quick morning preset chips (`06:30`, `07:00`, `07:30`, `08:00`).
  - Automatic calculation of **Heater ON** (`readyTime - preHeatMinutes`) and **Auto-OFF** (`readyTime + autoOffOffsetMinutes`), including automatic weekday shifting if the pre-heat window crosses midnight.
  - Supports single next-occurrence scheduling or repeating weekday schedules (`Mon`–`Sun`).
- **Direct Heater Control & Live Status**
  - Instant one-tap **START HEATER** and **STOP HEATER** controls.
  - Live **`● ON` / `○ OFF`** status badge in the header with automatic 5-second background polling so external changes (e.g., physical switch button or scheduled triggers) are reflected automatically.
- **Hybrid Local LAN + Telldus Live Cloud Architecture**
  - **Local ZNet Lite v2 HTTP API (`/api/...`)**: Ultra-fast, low-latency direct control (`device/turnOn`, `device/turnOff`, `device/info`, `devices/list`) over local Wi-Fi with UDP LAN auto-discovery on port `30303`.
  - **Telldus Live Cloud API (`https://pa-api.telldus.com/json/...`)**: Automatic fallback when away from home Wi-Fi, plus hardware controller schedule synchronization (`scheduler/setJob` & `scheduler/jobList`) with automatic local-to-cloud device ID resolution and coroutine-based rate-limit pacing.
- **Streamlined In-App Authentication (`⚙ Config`)**
  - Built-in two-step TellStick ZNet login and token exchange (`PUT /api/token` $\rightarrow$ `GET /api/token`).
  - Zero-copy-paste **Telldus Cloud OAuth 1.0a Key Auto-Fetch** directly from the authenticated session (`https://pa-api.telldus.com/keys/generatePrivate`).
  - Configurable **Pre-heat time (before warm)** and **Auto-off timer (after warm)** with preset chips and custom minute inputs for easy testing and customization.

---

## 🏗️ Project Structure

```text
app/src/main/java/com/example/carheater/
├── MainActivity.kt
├── data/telldus/
│   ├── TelldusModels.kt          # Domain models, AuthConfig, and HeaterScheduleCalculator
│   ├── TelldusApiClient.kt       # UDP LAN discovery, Local ZNet & Cloud OAuth 1.0a client
│   └── HeaterStorage.kt          # SharedPreferences persistence for auth & schedule config
├── ui/
│   ├── main/
│   │   ├── MainScreen.kt         # Main screen layout, header badge, control & schedule cards
│   │   └── MainScreenViewModel.kt# State management, live polling, and debounced schedule sync
│   └── components/
│       ├── SamsungWheelTimePicker.kt # Circular 24h scroll wheel picker with snap & haptics
│       ├── AuthConfigDialog.kt       # Configuration modal (timers, auth mode, switch picker)
│       └── ZNetBrowserAuthDialog.kt  # Inline TellStick & Telldus Live login workflow
└── theme/                        # Material 3 theme & typography
```

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio** (or Android SDK command-line tools) with **JDK 17+**
- Android device or emulator running **Android 8.0 (API 26)+**
- A **Telldus TellStick ZNet Lite v2** (or TellStick Net / Telldus Live account) paired with a Z-Wave or 433 MHz switch

### Build & Install via Gradle

```powershell
# Build the debug APK (uses --no-daemon to keep memory usage low)
.\gradlew.bat assembleDebug --no-daemon

# Install onto a connected Android device
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Initial Configuration in the App
1. Tap **`⚙ Config`** in the top-right corner.
2. Under **TellStick Authentication**, select **Local ZNet (Wi-Fi)** and tap **Scan LAN** (or enter your TellStick IP address) to discover your TellStick ZNet Lite v2.
3. Tap **Sign In & Authorize in App** to sign in with your Telldus Live credentials and approve local API access—Defrostify will automatically obtain both your local ZNet Bearer token and your Telldus Cloud OAuth 1.0a keys.
4. Select your heater switch under **Target Heater Switch**, adjust your desired **Pre-heat time** and **Auto-off timer**, and tap **Save & Close**.

---

## 🧪 Running Unit Tests

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon
```

Unit tests in [`HeaterScheduleCalculatorTest.kt`](app/src/test/java/com/example/carheater/data/telldus/HeaterScheduleCalculatorTest.kt) verify pre-heat and auto-off time calculations, midnight wrap-around weekday shifting, and countdown formatting.
