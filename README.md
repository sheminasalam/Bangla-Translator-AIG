# Bangla WhatsApp Translator (Native Android)

An independent, privacy-first native Android application engineered to translate Bengali (*বাংলা*) WhatsApp and WhatsApp Business messages into English directly on-device using Android's **AccessibilityService** and **Google ML Kit On-Device Translation**.

---

## 1. User Goal & Core Purpose

When receiving Bengali messages from friends or family on WhatsApp:
- **No copying or pasting** messages into external apps.
- **No forwarding** to AI bots or third-party cloud APIs.
- **Zero cloud API expenses** or token quotas.
- Displays a clean, discreet English translation pill directly beneath or above the original message inside WhatsApp.
- Seamlessly reconciles overlays during fast scrolling.
- Automatically clears overlays when switching conversations or exiting WhatsApp.
- Optionally translates incoming WhatsApp and WhatsApp Business notifications.

---

## 2. Privacy & Security Architecture

```
[WhatsApp / WhatsApp Business]
               ↓
   Android AccessibilityNodeInfo
               ↓
     BengaliDetector (Local)
               ↓
   TranslationCache (In-Memory LRU)
               ↓
  Google ML Kit Translate (Local On-Device)
  (bn_en dictionary stored on /data partition)
               ↓
    OverlayController (Main Thread)
               ↓
TYPE_ACCESSIBILITY_OVERLAY (Rendered directly in WhatsApp)
```

- **Zero Cloud Data Transmission:** Message text is translated 100% locally on your device hardware.
- **No Cloud AI APIs:** Does not use OpenAI, Gemini, Claude, DeepL, or any custom backend for message translation.
- **Explicit Network Usage:** The only internet connection used is by Google Play Services to download the compressed Bengali language pack (~30MB) during initial setup.

---

## 3. Key Architectural Innovations & Mitigations

### A. Conversation Generation / Session Lifecycle (`AtomicLong sessionGeneration`)
Previous iterations frequently suffered from race conditions:
1. User opened Chat A containing Bengali text.
2. Async translation initiated.
3. User rapidly switched to Chat B before translation finished.
4. Old translation popped up inside Chat B.

**Solution:**
- The service assigns an incrementing `sessionGeneration` whenever an active conversation transition or leave event occurs.
- Asynchronous ML Kit callbacks must validate 5 rules before displaying an overlay:
  1. `taskGeneration == sessionGeneration.get()`
  2. `isWhatsAppForeground()` is verified
  3. `appPreferences.isOverlayEnabled` is active
  4. Message `displayKey` is still visible on screen in the active set
  5. Service is attached and healthy

### B. Leaving WhatsApp Safety Watchdog
Android package filtering (`android:packageNames="com.whatsapp,com.whatsapp.w4b"`) means that when the user presses **Home** or switches to another app, the OS will **not** dispatch accessibility events from the launcher (`com.android.launcher3`).
- To prevent ghost overlays on the home screen, `BanglaAccessibilityService` maintains a lightweight 350ms watchdog whenever overlays or translations are pending.
- If `rootInActiveWindow?.packageName` is non-WhatsApp or null, all overlays are instantly purged, pending tasks invalidated, and session generation bumped.

### C. Separate Translation Identity vs Screen Display Identity
- **Cache Key:** `normalizedText` (trims whitespace, collapses internal spaces, preserves casing/punctuation). Bounded to 500 LRU entries.
- **Display Key:** `gen_${sessionGeneration}_${textHash}_${screenX}_${screenY}`.
- Prevents redundant translations when a message shifts by a few pixels during scroll.

### D. Unicode Bengali Detection (`BengaliDetector`)
- Uses Unicode block `U+0980..U+09FF`.
- Computes ratio: `bengaliLetters / totalAlphabeticLetters >= threshold` (default 20%).
- Automatically excludes URLs, timestamps (`12:45 PM`), audio durations (`0:15`), pure emojis (`😂👍`), and phone numbers.
- Handles mixed messages like *"কাল meeting আছে?"*.

### E. Smart Dynamic Overlay Positioning
- Uses `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`.
- Never relies on fixed heights (e.g. 56dp); dynamically measures wrapped text.
- Prefers positioning below the message bubble. If bottom screen clearance is insufficient, repositions above the message bubble.
- Clamps X and Y strictly within screen boundaries and system insets (status bar & navigation bar).

---

## 4. Permissions Overview

| Permission | Purpose | Required / Optional |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | Required to read visible WhatsApp message text bubbles and place overlays | **Required** |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Intercepts incoming WhatsApp notifications to post translated alerts | **Optional** (user toggle) |
| `POST_NOTIFICATIONS` | Needed on Android 13+ (API 33+) to display companion translated notifications | **Optional** |
| `INTERNET` | Used exclusively by Google Play Services / ML Kit to download the local Bengali model | **Setup only** |

---

## 5. How to Build

### Option 1: GitHub Actions CI (Recommended & Automated)

This repository includes a production-ready GitHub Actions workflow at `.github/workflows/build.yml`.

1. Push this repository to GitHub:
   ```bash
   git init
   git add .
   git commit -m "Initial commit of Bangla WhatsApp Translator"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
2. Navigate to the **Actions** tab on your GitHub repository.
3. The workflow **"Build Bangla WhatsApp Translator APK"** will execute automatically on Ubuntu with JDK 17, Android SDK 34, and Gradle 8.7.
4. Once completed, download the compiled **`BanglaWhatsAppTranslator-debug.apk`** artifact directly from the GitHub Actions run summary.

### Option 2: Local Command Line (Gradle)

If you have Gradle 8.7+ and JDK 17 installed:

```bash
# Verify Gradle version
gradle -v

# Run unit tests
gradle test

# Assemble Debug APK
gradle assembleDebug

# Output APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

### Option 3: Android Studio

1. Open Android Studio (Hedgehog 2023.1.1 or newer).
2. Select **File > Open** and choose this project root directory.
3. Android Studio will automatically sync the Kotlin DSL Gradle files.
4. Connect an Android phone or launch an emulator.
5. Click **Run > Run 'app'** (`Shift + F10`).

---

## 6. Setup & Device Instructions

1. **Install APK** onto your Android phone:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. **Open the app** (*Bangla WhatsApp Translator*).
3. **Download Model:**
   - Tap **"Download Language Model"**.
   - Wait ~15-30 seconds while ML Kit downloads the on-device Bengali-English model (~30MB).
   - Status will update to: **"Bengali → English Model Ready"**.
4. **Enable Accessibility:**
   - Tap **"Configure Accessibility"**.
   - Find **Bangla WhatsApp Translator Service** under Installed Apps.
   - Toggle **Allow**.
5. **(Optional) Enable Notification Access:**
   - Turn on the **"WhatsApp Notification Translation"** switch.
   - Tap **"Enable Notification Access"** and grant permission.
6. Open **WhatsApp** or **WhatsApp Business**:
   - Open any conversation containing Bengali messages.
   - Discreet English translation cards will appear directly with the messages!

---

## 7. Known Limitations & Real-Device Considerations

1. **WhatsApp DOM Variations:** WhatsApp occasionally updates internal layout hierarchy. The scanner relies on semantic text properties, visibility, and screen coordinates rather than fragile internal view IDs.
2. **Voice Notes & Image Text:** Audio messages and text inside images (photos) are not processed by Accessibility text nodes. (An optional future OCR module could address images).
3. **Battery Optimizations (OEM Specific):** On Xiaomi (MIUI), Huawei (EMUI), and Samsung (OneUI), aggressive memory managers can terminate background Accessibility services. Users should exclude the app from aggressive battery optimizations.
