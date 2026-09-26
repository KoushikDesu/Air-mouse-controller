# 🖱️ Air Connect Pro (Bluetooth Air Mouse & Precision Trackpad)

[![Download APK](https://img.shields.io/badge/Download-AirConnectPro.apk-success?style=for-the-badge&logo=android&logoColor=white)](https://github.com/KoushikDesu/Air-mouse-controller/releases/download/v1.0/AirConnectPro.apk)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#)
[![Connection](https://img.shields.io/badge/Bluetooth-HID%20(100%25%20Offline)-0078D7?style=for-the-badge&logo=bluetooth&logoColor=white)](#)

Turn your Android smartphone into an ultra-responsive **Air Mouse**, **Laptop Precision Trackpad**, and **Multimedia Remote Control** for your laptop (Windows 10/11, macOS, Linux).

**100% Offline • Zero Laptop Software or Drivers Required • Hardware-Level Bluetooth HID**

---

## 📥 Direct APK Download & One-Tap Install

Click the direct download button below to download the APK directly to your phone:

### 🚀 **[👉 CLICK HERE TO DOWNLOAD AirConnectPro.apk 👈](https://github.com/KoushikDesu/Air-mouse-controller/releases/download/v1.0/AirConnectPro.apk)**

> **Alternative Direct Link:** [Download from Repository (Raw APK)](https://github.com/KoushikDesu/Air-mouse-controller/raw/main/AirConnectPro.apk)

### 📲 How to Install in 3 Easy Steps:
1. Tap the **Download link** above from your smartphone browser (Chrome, Samsung Internet, etc.).
2. Once the download finishes, tap the **"Download complete"** notification (or open **Files / Downloads**).
3. Tap **AirConnectPro.apk** and press **Install**. *(If prompted, enable "Allow installation from this source")*.

---

## 🌟 Key Highlights & Capabilities

### 1. 🪂 Gyroscope Air Mouse (Free-Hand Pointer)
- Uses your phone's built-in 3-axis **hardware gyroscope** (`Sensor.TYPE_GYROSCOPE`) running at maximum hardware sampling frequency (`SENSOR_DELAY_GAME`).
- Translates physical hand motion in the air into pixel-perfect pointer movement on your laptop screen.
- **Independent Free-Motion Mode**: Move the pointer effortlessly without holding down any button.
- **Air Clutch Mode**: Touch the central trackpad to guide the pointer; lift your thumb to freeze the cursor and comfortably reposition your hand.

### 2. 💻 Laptop Precision Trackpad & Multi-Touch Gestures
The central frosted-glass pad functions exactly like a laptop trackpad:
| Gesture | Action on Laptop |
| :--- | :--- |
| **1-Finger Drag** | Smooth, continuous cursor navigation |
| **1-Finger Tap** | Standard Left Click |
| **Double-Tap & Hold** | Click and Drag (highlight text, move windows) |
| **2-Finger Tap** | Right Click (opens context menu) |
| **2-Finger Drag Up/Down** | Smooth vertical page scrolling |

### 3. 🔊 Physical Volume Buttons as Hardware Mouse Clicks
When the app is open on your phone screen, your phone's physical side buttons act as real mouse buttons:
- **Volume Up Button**: **Left Click** (Press and hold to select, highlight, or drag items).
- **Volume Down Button**: **Right Click** (Open context menus instantly without looking at the screen).

### 4. ⚡ Microsoft Swift Pair & Auto-Discovery
- Equipped with **Microsoft Swift Pair BLE Beacon** (`0x0006`).
- As soon as you tap **"Make Discoverable / Pair with Laptop"**, your Windows 10/11 laptop displays a native system notification toast:
  > *"Air Mouse Pro found. Connect to this device?"*
- Connect with a single click—no digging through Bluetooth menus!

### 5. 🎛️ Surrounding Laptop Controls (HID Consumer Control)
Control your laptop without touching its keyboard:
- **Volume Controls**: Volume Up (`+`), Volume Down (`-`), Instant Audio Mute.
- **Display Brightness**: Increase (`🔆`) or Decrease (`🔅`) laptop screen brightness.
- **Media Playback**: Previous Track (`⏮`), Play / Pause (`⏯`), Next Track (`⏭`).
- **Dedicated Click Bar**: Physical Left Click, Middle Click (Wheel Click), and Right Click buttons.
- **Tactile Scroll Strip**: Dedicated vertical scroll bar for quick document & web browsing.

### 6. ❄️ OxygenOS Frosted Glassmorphism UI
- Styled with modern, neutral white translucent glass cards (`#14FFFFFF` to `#24FFFFFF`).
- Edge-to-edge layout designed with status bar insets so buttons are never obstructed by notches, camera cutouts, or notification bars.
- Tactile haptic feedback on touch gestures and clicks.

---

## 🧠 How It Works Under the Hood

Unlike Wi-Fi mice or remote desktop apps, **Air Connect Pro requires NO server software, NO background client, NO Wi-Fi network, and NO internet connection.**

```
┌─────────────────────────────────┐                 ┌──────────────────────────────┐
│        Android Smartphone       │                 │       Windows / Mac Laptop   │
│                                 │                 │                              │
│  [ Gyroscope / Multi-Touch ]    │   Bluetooth HID │  Recognized as a native      │
│               │                 │  ─────────────► │  Standard Bluetooth Mouse    │
│  [ Native BluetoothHidDevice ]  │   (Offline L2CAP│  & Multimedia Keyboard       │
│  [ Swift Pair BLE Advertiser ]  │    Profile)     │  (Zero Drivers Needed!)      │
└─────────────────────────────────┘                 └──────────────────────────────┘
```

1. **Native Android HID Profile (`BluetoothHidDevice`)**:
   The app registers a custom composite USB-HID descriptor with the Android Bluetooth stack. The descriptor defines two independent reporting interfaces:
   - **Report ID 1 (Mouse)**: Standard 5-button optical mouse with 16-bit relative X/Y coordinates and 8-bit vertical scroll wheel.
   - **Report ID 2 (Consumer Control)**: Multimedia keyboard sending standard volume, mute, display brightness, and playback transport codes.
2. **Swift Pair BLE Advertisement**:
   A dedicated BLE advertiser broadcasts the Microsoft Swift Pair vendor payload (`0x0006`) with pairing capability flags. Windows detects this beacon and initiates pairing automatically.

---

## 🚀 Setup & Pairing Guide

### Step 1: Install the APK
1. Download **[AirConnectPro.apk](https://github.com/KoushikDesu/Air-mouse-controller/releases/download/v1.0/AirConnectPro.apk)** onto your Android smartphone.
2. Tap the downloaded file to install.

### Step 2: Grant Permissions
1. Open **Air Connect Pro**.
2. When prompted, grant:
   - **Nearby Devices / Bluetooth** (`BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_SCAN`).
   - High sampling sensor permission (granted automatically for gyroscope tracking).

### Step 3: Pair with Laptop
#### Method A: Microsoft Swift Pair (Windows 10/11)
1. Turn on Bluetooth on your laptop (**Settings → Bluetooth & devices**).
2. Ensure *"Show notifications to connect using Swift Pair"* is enabled in Windows Bluetooth settings.
3. In the phone app, tap **"Make Discoverable / Pair with Laptop"**.
4. Within seconds, a popup toast appears in the bottom-right corner of your laptop screen:
   > **Air Mouse Pro**  
   > *New Bluetooth mouse found. Connect?*
5. Click **Connect**. Pairing completes in seconds!

#### Method B: Manual Bluetooth Pairing
1. On your laptop, go to **Settings → Bluetooth & devices → Add device → Bluetooth**.
2. Look for **Air Mouse Pro** in the list of available devices.
3. Click to pair.
4. Once connected, the status badge in the app will turn green:  
   🟢 **Connected • Ready**

---

## 🕹️ Controls & Navigation Cheatsheet

```
┌────────────────────────────────────────────────────────┐
│                   AIR CONNECT PRO                      │
│            🟢 Connected • Laptop Connected             │
│   [ Make Discoverable / Pair ]   [ Air Mode: Active ]  │
├────────────────────────────────────────────────────────┤
│                                                        │
│   ┌──────────────────────────────────┐  ┌──────────┐   │
│   │                                  │  │    ▲     │   │
│   │     PRECISION TRACKPAD           │  │    │     │   │
│   │                                  │  │  SCROLL  │   │
│   │  • 1-Finger: Move / Tap Left     │  │  STRIP   │   │
│   │  • 2-Finger Tap: Right Click     │  │    │     │   │
│   │  • 2-Finger Swipe: Scroll Page   │  │    ▼     │   │
│   │                                  │  └──────────┘   │
│   └──────────────────────────────────┘                 │
│                                                        │
│   ┌────────────────────────────────────────────────┐   │
│   │   [ LEFT CLICK ]   [ MIDDLE ]   [ RIGHT CLICK ]│   │
│   └────────────────────────────────────────────────┘   │
│                                                        │
│   ┌────────────────────────────────────────────────┐   │
│   │  [ Vol - ]   [ Vol + ]   [ Mute ]              │   │
│   │  [ 🔅 Dim ]  [ 🔆 Bright ]                     │   │
│   │  [ ⏮ Prev ]  [ ⏯ Play ]   [ ⏭ Next ]          │   │
│   └────────────────────────────────────────────────┘   │
│                                                        │
│   Physical Volume Keys:                                │
│   🔊 Vol Up   = Hardware Left Click (Press & Drag)     │
│   🔉 Vol Down = Hardware Right Click                   │
└────────────────────────────────────────────────────────┘
```

---

## ❓ Troubleshooting & FAQs

#### Q: The laptop says "Paired", but the cursor is not moving.
> **Fix**: Windows occasionally connects audio profiles first. Disconnect and reconnect **Air Mouse Pro** from Windows Bluetooth settings once. Ensure the status indicator in the app shows 🟢 **Connected • Ready**.

#### Q: Gyroscope cursor moves too fast or drifts.
> **Fix**: Hold the phone still on a flat desk for 2 seconds. The dynamic gyroscope bias calibration will instantly reset the zero-point drift.

#### Q: Windows Swift Pair notification didn't show up.
> **Fix**: Go to **Settings → Bluetooth & devices → Devices** on Windows, scroll down and verify that **"Show notifications to connect using Swift Pair"** is checked. Alternatively, use standard manual Bluetooth pairing.

---

## 📄 License
This project is open-source and released under the MIT License.
