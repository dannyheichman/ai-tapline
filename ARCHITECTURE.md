# Architecture

Hands-free Copilot is a small Android app split so the conversation policy can move to another platform later (a Windows app that drives Copilot's desktop or web UI) without dragging Android APIs along.

```
:app          Compose UI, foreground service, notification, Quick Settings tile
   |          wires the Android adapters into the loop
   +--> :speech        SpeechRecognizer and TextToSpeech
   +--> :automation    AccessibilityService and the Copilot screen driver
   +--> :core          loop, selectors model, markdown, completion detection
```

`:core` is a plain Kotlin/JVM module. It does not depend on Android. `:speech` and `:automation` depend on `:core`. `:app` depends on all three. Gradle versions live in `gradle/libs.versions.toml`.

## The loop

`core/.../loop/HandsFreeConversation.kt` is the product behavior:

1. `ChatDriver.prepare()` brings Copilot forward and reports a blocker (accessibility off, package missing, work profile, protected content, no chat box).
2. `Dictation.listen()` collects one utterance.
3. `StopCommands` drops "stop session" (and the other configured phrases) instead of sending it.
4. `ChatDriver.captureBaseline()` then `submit()`.
5. `ChatDriver.observe()` is polled. `AssistantTextExtractor` drops the previous bubble and the user's own text. `ResponseCompletionDetector` decides the reply is finished.
6. `SpokenTextProcessor` strips markdown and citations. `Speaker.speak()` reads it, while a second listen looks for barge-in via `EchoFilter`.
7. A tap calls `bargeIn()`, which only stops the speaker. The next listen starts on its own.

Change timings, stop phrases, or the target package in the selector JSON (below), not by editing the loop. Change the policy itself — when to speak, what counts as echo, what counts as a stop command — in `:core`, and add a unit test next to the existing ones.

`HandsFreeConversation` takes a `Sleeper` so tests advance time without sleeping. Production uses `SystemSleeper`.

### Ports

`core/.../ports/Ports.kt`

| Port | Android implementation | A future Windows port would use |
| --- | --- | --- |
| `ChatDriver` | `automation/CopilotAutomation.kt` | UI Automation against the Copilot window or web view |
| `Dictation` | `speech/AndroidDictation.kt` | Windows speech recognition |
| `Speaker` | `speech/AndroidSpeaker.kt` | SAPI or any TTS engine |

The Windows port should not need to copy the state machine. Implement the three ports and construct `HandsFreeConversation`.

## Selectors

Shipped defaults: `automation/src/main/assets/copilot_selectors.json`.

Runtime override: `SelectorRepository` (SharedPreferences). The Calibrate screen edits it. `SelectorConfigJson` in `:core` parses and writes the file, so the schema is tested without Android.

Matching and fallbacks: `core/.../loop/ChatControlFinder.kt`.

- Scores resource id, id suffix, content description, text, and class. Higher score wins.
- Input prefers an **editable** match, then the lowest editable field on screen, and only then a non-editable description match (some web composers lie about `editable`).
- Send prefers a selector match, then a clickable control vertically near the composer, preferring one labeled Send.
- A generating/stop node counts only when it is clickable or its text is short, so a paragraph that happens to contain the word "generating" does not freeze the session.
- The assistant candidate is the newest usable text node (`newestMessageEdge` is `bottom` or `top`). Nodes inside `messageList`, when that selector matches, are preferred.

Do not add `com.microsoft...:id/...` checks in Kotlin. Put new ids in the JSON and cover the scoring rule with a `ChatControlFinderTest` if you change it.

`AndroidChatDriver` turns a snapshot into actions: `ACTION_SET_TEXT`, clipboard paste if the text did not stick, `ACTION_CLICK` (walking up to a clickable parent), then `ACTION_IME_ENTER` on API 30+.

## When a reply is "done"

`ResponseCompletionDetector`:

- Empty text never completes.
- A change resets the clock. Whitespace-only changes do not.
- Stable text for `settleMillis` with no generating indicator → speak.
- If a generating indicator was seen and then disappeared, the shorter `indicatorClearedSettleMillis` is enough.
- If the indicator is stuck but the text is stable for `indicatorStuckMillis`, speak anyway.

`AssistantTextExtractor` is the other half: it ignores a node whose text is still the previous answer or the user's utterance, and it peels a long previous answer off the front of a node that grew in place.

## Speech

`VoiceSettingsStore` holds the engine package, voice name, rate, pitch, recognition language, and the on-device preference.

`TtsCatalog` lists installed TTS engines (`TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE`) and the voices of the selected engine. Samsung TTS and Google TTS show up when they are installed; they are not special-cased.

`AndroidDictation` uses `SpeechRecognizer.createOnDeviceSpeechRecognizer` when the platform says on-device recognition exists and the preference is on. Otherwise it uses `createSpeechRecognizer` and `EXTRA_PREFER_OFFLINE`. Retryable errors (silence, no match, busy, network) restart the same session. A final result ends it; the loop opens the next one.

`AndroidSpeaker` chunks text under the engine's practical limit and stops on cancellation so barge-in cuts the audio.

## Accessibility service

`automation/CopilotAccessibilityService.kt` is created by the system. `onServiceConnected` keeps the instance the driver uses and turns on interactive windows, view ids, and not-important views. The manifest entry and `res/xml/accessibility_service_config.xml` live in `:automation` and merge into the app. The service class in that manifest is fully qualified (`app.handsfree.copilot.automation.CopilotAccessibilityService`) because a relative name would be resolved against the app package.

`WindowSnapshotter` copies nodes into `UiNode` (text, ids, bounds, path) and can find a node again by window id plus child path. `NodeTreeDumper` writes the calibration file, including windows that are not Copilot's, so a wrong package id is visible.

`AccessFactsCollector` plus `AccessDiagnoser` (the diagnoser is in `:core` and unit tested) turn those facts into the messages on the Calibrate screen:

- service off or not connected
- package missing
- package only in another profile (work profile)
- window with no nodes, or nodes with no text (Intune / `FLAG_SECURE` style blocking)
- window open but no chat box

The collector checks `LauncherApps` profiles and `UserManager.isManagedProfile` (API 30+). It does not try to punch through a policy that hides the tree.

## App shell

| Piece | Role |
| --- | --- |
| `MainActivity` and `ui/` | Session, voice, and calibration screens |
| `ConversationForegroundService` | Microphone foreground service. Notification actions: Interrupt, Stop. Drops back to an ongoing Start notification when the session ends |
| `SessionTrampolineActivity` | Transparent activity used by the Quick Settings tile and the Start notification, so Android treats the launch as user-initiated |
| `SessionTileService` | Quick Settings tile |
| `BootReceiver` | Re-posts the Start notification after reboot. It does not start the microphone |
| `InterruptOverlay` | Optional "appear on top" Interrupt button while TTS is playing |
| `HandsFreeApplication` / `AppGraph` | One process-wide loop, shared by the UI and the service |

`minSdk` is 29. `compileSdk` and `targetSdk` are 37 (Android 17). Current Jetpack (Compose 1.12, core 1.19) refuses to compile against an older SDK. The UI is edge-to-edge Material 3 and uses the system dynamic colors on Android 12 and newer, which covers a Galaxy S25 Ultra on current One UI.

## Tests

`:core:test` covers:

- response completion (stable text, generating indicator, indicator cleared, stuck indicator, empty, whitespace, no double completion)
- markdown and citation stripping
- stop phrases and echo / barge-in classification
- assistant-text extraction
- selector scoring and fallbacks
- work-profile and Intune diagnosis
- the shipped `copilot_selectors.json` parses and names `com.microsoft.office.officehubrow`
- the loop: happy path, barge-in, prepare failure, timeout

`:speech` and `:automation` are Android libraries. Their behavior that can be expressed without a device lives in `:core`. Do not move a rule back into an Android class just to avoid a test.

## Package ids

| App | Package | Used as |
| --- | --- | --- |
| Microsoft Copilot for work (renamed Microsoft 365 app) | `com.microsoft.office.officehubrow` | Default `targetPackage` |
| Consumer Microsoft Copilot | `com.microsoft.copilot` | Listed under `alternatePackages` for reference only. The driver does not switch to it |

Confirm on a device with the calibration dump's window package line if Microsoft ships a new id. Change `targetPackage` in the JSON (or the Calibrate field) and the `<queries>` entry in `app/src/main/AndroidManifest.xml` if the new id should be visible without `QUERY_ALL_PACKAGES`.

## What not to add

- Graph Copilot Chat, undocumented Copilot HTTP APIs, or token scraping. The point of the project is that the text-chat model stays in the official app.
- Selector literals in Kotlin.
- Secrets. The repository is public. Calibration dumps belong on the device, not in git.
