# Hands-free Copilot

An independent Android companion that adds a hands-free voice loop on top of the **text** chat in the official Microsoft Copilot app. You pick the model in that text chat (Claude, for example). This app dictates into the chat box, waits until the reply stops streaming, and speaks it with the phone's own text-to-speech engine.

It does not call the Microsoft Graph Copilot Chat API, Copilot's web endpoints, or any other Microsoft service, and it does not read Copilot tokens. Speech recognition and speech synthesis are Android's. The only way it touches Copilot is through an accessibility service that drives the screen the way you would: focus the composer, insert text, press Send, and read the new reply.

This project is not affiliated with Microsoft.

## What you need

- Android 10 or newer (minSdk 29). It compiles and targets Android 17 (API 37), which current Jetpack libraries require and which a Galaxy S25 Ultra on the October 2026 One UI train is expected to be running. The app still installs on Android 10 through 16.
- The work Microsoft Copilot app installed for the **same Android user** as this companion. Package id: `com.microsoft.office.officehubrow`. On Google Play the listing is named Microsoft Copilot; the id was kept when the Microsoft 365 app was renamed. The consumer app `com.microsoft.copilot` is a different product and is not the default target.
- A text chat already open, with the model you want selected. Copilot's own voice mode is never used, because that mode ignores the text-chat model.

## Sideload

Debug APK after a local build:

```bash
./gradlew :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

On the phone, install it with your usual sideload path (`adb install -r app-debug.apk`, or copy the file over and open it). Allow installs from that source if Android asks. This is not a Play Store app. On current Samsung One UI, a sideloaded app is blocked from accessibility until you allow restricted settings:

1. Settings → Apps → Hands-free Copilot → the menu (⋮) → **Allow restricted settings**.
2. Settings → Accessibility → Installed apps → **Hands-free Copilot** → turn it on. Read the system warning and confirm.
3. Open Hands-free Copilot and allow the microphone and notifications.
4. Optional: **Tap overlay**, so an Interrupt button can sit on top of Copilot while a reply is read out. Optional: **Battery**, so One UI does not freeze a long session.

The accessibility service is also linked from the Session screen.

## Pick a voice

Open the **Voice** tab.

- Engine: every TTS service the phone reports. On a Galaxy that is usually Samsung TTS (`com.samsung.SMT`) and, if you installed it, Google TTS (`com.google.android.tts`). Nothing is hard-coded; the list is live.
- Voice, rate, and pitch apply to the next reply.
- **Preview voice** speaks a sample with the current settings.
- "Prefer on-device speech recognition" uses Android's on-device `SpeechRecognizer` when the device has one, and otherwise the default recognizer (which may use the network). That network use is Android's speech service, not a Microsoft API.

## Run a session

1. In the Copilot app, open a **text** chat and choose the model. Leave that chat on screen.
2. In Hands-free Copilot, tap **Start session**. You can also tap **Start** on the ongoing notification, or add the **Copilot voice** Quick Settings tile and tap that.
3. The phone brings Copilot forward, listens, and when you finish a phrase it types that phrase and presses Send.
4. It waits until the reply's text has stopped changing (and, when it can see one, until the stop/generating control goes away), strips markdown and citation markers, speaks the reply, then listens again.
5. To cut the readout off, start talking (barge-in) or tap **Interrupt** on the notification, the overlay, or the Session screen. A real utterance becomes the next prompt. Echo of the reply being spoken is ignored.
6. Say **stop session** (also "end session" or "end voice session") to end. Filler such as "please" is fine. A long sentence that merely mentions the phrase is sent to Copilot instead.

## Calibration

Copilot's resource ids and layout are not known to this project and will change. Selectors live in `automation/src/main/assets/copilot_selectors.json` and can be replaced at runtime from the **Calibrate** tab. There are no selector strings in the matching code. If nothing matches, the driver still tries generic fallbacks: the lowest editable field, a Send control beside it, and the newest substantial text that is not your own utterance.

1. Open the Copilot text chat.
2. On Calibrate, tap **Dump Copilot node tree**.
3. Share the file (or pull it from the path shown). It lists each node's class, resource id, text, content description, and bounds.
4. Paste the composer's resource id into `input.resourceIds` or `input.resourceIdSuffixes`, and the Send control's content description into `send`.
5. If the transcript list is reversed, set `newestMessageEdge` to `top`. If you can see a stable id on the message list, put it in `messageList` so chrome outside the list is ignored.
6. Save. **Reset to the shipped selectors** drops the override.

`settleMillis` is how long the reply text must stay unchanged before it is spoken. `indicatorClearedSettleMillis` is the shorter wait used after a stop/generating control disappears. If that control stays on screen but the text is no longer changing, the session still continues after `indicatorStuckMillis` so a bad selector cannot hang it.

The dump contains whatever is on the Copilot screen. Do not commit it.

## Work profile and Intune

Android will not let an accessibility service in the personal profile read an app that lives only in the work profile. If Copilot is deployed by your organization:

- Install **this** companion inside the work profile (Company Portal or the managed Play Store, whatever your admin allows for sideloaded or private apps) and enable its accessibility service **there**.
- The Calibrate screen says so when it can see the package in another user and not in the current one.

Intune app protection can also hide a window or strip text out of the accessibility tree even inside the work profile. The session reports that separately ("screen is blocked" or "text is hidden") and does not pretend to have a reply. There is no bypass in this app. An admin has to allow accessibility for Copilot, or the feature cannot work.

## Limitations

- The model is whatever the open text chat is using. This app cannot change models and does not try to.
- A Copilot update can move the chat box. Use Calibrate when Send starts failing.
- Barge-in depends on the recognizer running while audio is playing. Some devices pause recognition during TTS; the Interrupt button and the notification action still work.
- On-device recognition has to be installed for fully offline dictation. Otherwise Android may use its online recognizer.
- The app cannot see secure windows, flag-protected content, or another user's apps.
- Reply text is chosen with configurable selectors plus a "newest text node" heuristic. A messy layout can pick the wrong node until you calibrate it.
- Very long replies are spoken in chunks because the TTS engine has a length limit.
- This is a sideload tool. It is not the Microsoft Copilot app and it does not add a system-wide assistant.

## Build

JDK 21, Android SDK platform 37 (package `platforms;android-37.1`), build-tools 36.0.0.

```bash
export ANDROID_HOME="$HOME/android-sdk"   # or your SDK path
./gradlew :core:test :app:assembleDebug
```

GitHub Actions (`.github/workflows/build-debug-apk.yml`) runs the unit tests, builds the debug APK, and uploads it as an artifact. Do not commit keystores, dumps, or account data. The repo is public and this project has no secrets.

See [ARCHITECTURE.md](ARCHITECTURE.md) for where to change the loop, the selectors, speech, and the accessibility driver.
