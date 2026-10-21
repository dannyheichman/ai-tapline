# Copilot Voice Bridge

Hands-free voice conversations with the **Microsoft 365 Copilot** app on Android, using the model you already picked in Copilot's **text** chat.

Copilot's own voice mode always falls back to the default voice model. This app leaves that alone. It listens with Android speech recognition, types the utterance into the open text chat, waits until the reply stops streaming, and reads the reply with the Android text-to-speech engine you choose (Samsung TTS, Google TTS, or another installed engine).

It does **not** call Microsoft Graph, the Copilot Chat API, or any private Copilot endpoint, and it does not read tokens. Everything happens on the phone by driving the official app through the accessibility service.

`minSdk` is 29. The project is built against Android 15 (API 35) and is meant to run on a Samsung Galaxy S25 Ultra with current One UI.

## Which Copilot package

Microsoft renamed the Microsoft 365 Copilot app to "Microsoft Copilot", but the Android application id of the **work / school** app did not change.

| App | Package | Use it when |
| --- | --- | --- |
| Microsoft 365 Copilot, work or school (Play Store title is now Microsoft Copilot, "the AI built for work") | `com.microsoft.office.officehubrow` | This is the default. Work and school accounts, enterprise data protection, model picker in the text chat. |
| Microsoft Copilot consumer app | `com.microsoft.copilot` | Only if that is the app actually on screen. |

Confirm on the phone before relying on the default:

```bash
adb shell pm path com.microsoft.office.officehubrow
adb shell pm path com.microsoft.copilot
```

Or open **Calibrate**, put Copilot in front, and tap **Refresh node tree**. The dump lists the foreground package. **Use foreground package** writes that id into the selector file. The package name is configuration, not a constant in the driver.

## Install (sideload)

The debug APK is produced by `./gradlew :app:assembleDebug` and by the GitHub Actions workflow `.github/workflows/debug-apk.yml` (artifact `copilot-voice-bridge-debug`).

On the phone, allow installs from your file manager or use adb:

```bash
adb install -r copilot-voice-bridge-debug.apk
```

Work profile (do this when Copilot itself is in the work profile):

```bash
adb shell pm list users
adb install --user WORK_USER_ID -r copilot-voice-bridge-debug.apk
```

A personal-profile accessibility service cannot read a work-profile window. Install and enable this app **in the same profile as Copilot**. The app reports that case; it does not try to cross the boundary.

### Samsung restricted settings

On Android 13 and newer, a sideloaded app cannot enable an accessibility service until restricted settings are allowed:

1. Settings → Apps → Copilot Voice Bridge → menu (⋮) → **Allow restricted settings**.
2. Settings → Accessibility → Installed apps → **Copilot Voice Bridge** → On.
3. In the app, grant the microphone and notifications.
4. Set battery to **Unrestricted** (the Session screen has a button) so One UI does not freeze the session.

There is also an **Add Quick Settings tile** button (Android 13+). The tile and the persistent notification can start and end a session. The on-screen chip (Listening / Interrupt / End) is an accessibility overlay, so it does not need the "display over other apps" permission.

## Use it

1. Open Microsoft 365 Copilot and sign in with the work account.
2. Open a **text** chat. Pick the model there (for example Claude Opus). Leave that chat on screen. Do not start Copilot's microphone.
3. In Copilot Voice Bridge, open **Voice**, choose the TTS engine and voice, set rate and pitch, and tap **Preview voice**.
4. On **Session**, tap **Start session**.

The loop is: listen → on end of speech, type and send → wait until the reply is finished → speak → listen again.

Say **stop session** (also "end session", "stop voice", "cancel session") to end. Phrases are editable in the selector JSON. Tap **End** on the notification, the Quick Settings tile, or the overlay to end without speaking. Tap **Interrupt**, or talk over the reply, to barge in. Echo of the reply itself is ignored so the speaker does not become the next prompt.

## Calibrate

Copilot does not publish stable view ids. Selectors ship as data in `core/src/main/resources/default_driver_config.json` and can be replaced on the phone.

1. Open the Copilot text chat.
2. Calibrate → **Refresh node tree**.
3. Check the hit counts. `chatInput` and `sendButton` should be at least 1. `generatingIndicator` should be **0** while idle and **1** while a reply is streaming. If a Stop button stays matched when nothing is generating, the session will wait forever in the default `BOTH` completion mode. Tighten that rule.
4. Edit the JSON, **Save selectors**, and refresh again.
5. **Share dump** writes a text file. The dump includes on-screen chat text. Treat it as work data. Do not commit dumps; the repo `.gitignore` ignores `dumps/`.

The override is stored in the app's private `files/driver_config.json`. **Reset to built-in selectors** deletes it. Backup is disabled so that file is not uploaded by Android backup.

## Build

JDK 17 or 21, Android SDK 35, build-tools 35.0.0.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or the SDK path on this machine
./gradlew :core:test :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

Unit tests cover reply-completion detection, markdown and citation stripping, assistant-bubble selection, selector matching, the work-profile / Intune decision text, and the conversation loop with fake speech and a fake chat driver.

## Limitations

- This only works while the Copilot **text** chat is the screen the accessibility service can read. If Microsoft changes the layout, update the selectors from a dump. No code change is required for a new resource id.
- Intune app protection, a work-profile wall, or a secure-window flag can hide the chat from accessibility. The Session and Calibrate screens say so when that happens. The app cannot bypass it.
- On-device speech recognition depends on a downloaded offline model (Samsung or Google). The Voice screen prefers that model and retries once with the phone's default recognizer if the offline model is missing. That retry may use the network inside the **speech** app, not inside a Microsoft API. This app does not declare the internet permission.
- Barge-in uses the microphone while the reply plays. A phrase that also appears in the reply is treated as echo and dropped. Headphones make barge-in reliable. The Interrupt button does not depend on the microphone.
- The screen needs to stay on so Copilot's window still has an accessibility tree. The overlay can hold the screen on during a session.
- `ACTION_SET_TEXT` is tried first. If the chat field ignores it, the app pastes from the clipboard and then restores the previous clipboard. A device policy can block that paste.
- The bridge does not switch models, start Copilot's voice mode, or read the model name. The model is whatever that text chat is already using.
- `targetSdk` is 35. On a newer One UI release the app runs with Android's compatibility behavior for apps that target 35.

## Layout

See [ARCHITECTURE.md](ARCHITECTURE.md) for the module boundaries and the table of which file to edit.

No secrets belong in this repository. Selector files and node dumps can contain work chat text; keep them on the device.
